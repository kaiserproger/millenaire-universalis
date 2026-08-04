package ru.kaiserroman.millenairearmies.server.execution;

import com.mojang.logging.LogUtils;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.millenaire.entity.MillVillager;
import org.millenaire.entity.VillagerNavDriver;
import org.millenaire.goal.GoalContext;
import org.millenaire.goal.GoalScheduler;
import org.millenaire.goal.VillagerGoal;
import org.millenaire.goal.VillagerTask;
import org.millenaire.village.Village;
import org.slf4j.Logger;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.integration.millenaire.FactionProjectionService;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireEntityBridge;
import ru.kaiserroman.millenairearmies.model.ArmyFormation;
import ru.kaiserroman.millenairearmies.model.ArmyTacticalState;
import ru.kaiserroman.millenairearmies.persistence.PackedGarrisonState;
import ru.kaiserroman.millenairearmies.persistence.PackedUnitMembership;
import ru.kaiserroman.millenairearmies.persistence.StableDimensionTable;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandService;
import ru.kaiserroman.millenairearmies.server.service.StrategicArmyOrder;
import ru.kaiserroman.millenairearmies.server.supply.ArmySupplyAccess;

/**
 * Bounded bridge from committed strategic orders to Millenaire's public goal/navigation API.
 *
 * <p>Only already loaded, entity-ticking {@link MillVillager}s are considered.  Each server tick
 * advances a fixed stripe of primitive membership rows.  An unchanged acknowledged revision is a
 * read-only fast path: it creates no task, context, UUID, iterator, or position object.</p>
 *
 * <p>Millenaire beta.2's public {@code GoalScheduler.forceTask} sets {@code currentGoal} to null,
 * so its generic urgent-goal preemption loop cannot preempt a forced task. Movement and logistics
 * therefore yield to concrete combat signals. Strategic attack tasks deliberately keep scheduler
 * ownership and invoke Millenaire's public navigation and combat API themselves, preserving real
 * weapons, swings, projectiles, damage and death while adding formation-level tactics.</p>
 */
public final class ArmyOrderExecutionBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int UNITS_PER_TICK = 64;
    private static final long TELEMETRY_INTERVAL_TICKS = 1_200L;

    private final PackedArmyOrderRevisions orderRevisions = new PackedArmyOrderRevisions();
    private final PackedUnitExecutionState unitStates = new PackedUnitExecutionState();
    private final StrategicMoveTaskPool taskPool = new StrategicMoveTaskPool();
    private final StrategicFollowTaskPool followTaskPool = new StrategicFollowTaskPool();
    private final StrategicAttackTaskPool attackTaskPool = new StrategicAttackTaskPool();
    private final StrategicGarrisonTaskPool garrisonTaskPool = new StrategicGarrisonTaskPool();
    private final ArmyFormationCoordinator formations = new ArmyFormationCoordinator();
    private final ArmyBattleCoordinator battles = new ArmyBattleCoordinator();
    private final PhysicalSiegeCoordinator sieges = new PhysicalSiegeCoordinator();
    private final PhysicalBattleEventLog battleEvents =
            new PhysicalBattleEventLog(ArmiesConfig.BATTLE_EVENT_CAPACITY);
    private final OrderExecutionTelemetry telemetry = new OrderExecutionTelemetry();

    private MinecraftServer server;
    private PackedArmyEcs ecs;
    private PackedUnitMembership memberships;
    private StableDimensionTable dimensions;
    private MillenaireEntityBridge entityBridge;
    private FactionProjectionService factionProjection;
    private ArmyCommandService commandService;
    private PackedGarrisonState garrisons;
    private ArmyCommandService.DirtyMarker dirtyMarker;
    private ArmySupplyAccess supplyAccess = ArmySupplyAccess.NONE;
    private int nextMembershipRow;
    private long nextTelemetryGameTime;
    private long lastLoggedTransitions;

    public boolean start(
            MinecraftServer startingServer,
            PackedArmyEcs persistedEcs,
            PackedUnitMembership persistedMemberships,
            StableDimensionTable persistedDimensions,
            MillenaireEntityBridge loadedEntities,
            FactionProjectionService projectedFactions,
            ArmyCommandService runningCommandService,
            PackedGarrisonState persistedGarrisons,
            ArmyCommandService.DirtyMarker persistedDirtyMarker) {
        return start(
                startingServer, persistedEcs, persistedMemberships, persistedDimensions,
                loadedEntities, projectedFactions, runningCommandService, persistedGarrisons,
                persistedDirtyMarker, ArmySupplyAccess.NONE);
    }

    public boolean start(
            MinecraftServer startingServer,
            PackedArmyEcs persistedEcs,
            PackedUnitMembership persistedMemberships,
            StableDimensionTable persistedDimensions,
            MillenaireEntityBridge loadedEntities,
            FactionProjectionService projectedFactions,
            ArmyCommandService runningCommandService,
            PackedGarrisonState persistedGarrisons,
            ArmyCommandService.DirtyMarker persistedDirtyMarker,
            ArmySupplyAccess persistedSupplyAccess) {
        Objects.requireNonNull(startingServer, "startingServer");
        if (server == startingServer) {
            return false;
        }
        if (server != null) {
            throw new IllegalStateException("Army execution bridge is already running");
        }
        if (!runningCommandService.isRunning() || runningCommandService.ecs() != persistedEcs) {
            throw new IllegalStateException("Command service must own the execution ECS");
        }
        if (!startingServer.isSameThread()) {
            throw new IllegalStateException("Army execution must start on the Minecraft server thread");
        }

        server = startingServer;
        ecs = Objects.requireNonNull(persistedEcs, "persistedEcs");
        memberships = Objects.requireNonNull(persistedMemberships, "persistedMemberships");
        dimensions = Objects.requireNonNull(persistedDimensions, "persistedDimensions");
        entityBridge = Objects.requireNonNull(loadedEntities, "loadedEntities");
        factionProjection = Objects.requireNonNull(projectedFactions, "projectedFactions");
        commandService = Objects.requireNonNull(runningCommandService, "runningCommandService");
        garrisons = Objects.requireNonNull(persistedGarrisons, "persistedGarrisons");
        dirtyMarker = Objects.requireNonNull(persistedDirtyMarker, "persistedDirtyMarker");
        supplyAccess = Objects.requireNonNull(persistedSupplyAccess, "persistedSupplyAccess");
        orderRevisions.reserve(ecs.armySize());
        unitStates.reserve(memberships.size());
        battles.start(ecs, memberships);
        battles.hostilityPolicy((sourceArmy, targetArmy, sourceFaction, targetFaction) ->
                factionProjection.isHostile(sourceFaction, targetFaction));
        sieges.clear();
        battleEvents.clear();

        PackedArmyEcs.ArmyCursor cursor = ecs.newArmyCursor();
        while (cursor.advance()) {
            orderRevisions.observe(
                    cursor.handle(),
                    cursor.order(),
                    cursor.targetDimension(),
                    cursor.state(),
                    cursor.packedTargetPos());
        }
        commandService.installOrderCommitListener(this::orderCommitted);
        nextMembershipRow = 0;
        telemetry.reset();
        nextTelemetryGameTime = startingServer.overworld().getGameTime() + TELEMETRY_INTERVAL_TICKS;
        lastLoggedTransitions = 0L;
        return true;
    }

    /** Advances at most {@value #UNITS_PER_TICK} membership rows. */
    public void tick(MinecraftServer tickingServer) {
        if (server != tickingServer) {
            return;
        }
        if (!tickingServer.isSameThread()) {
            throw new IllegalStateException("Army execution tick escaped the Minecraft server thread");
        }
        long gameTime = tickingServer.overworld().getGameTime();
        maybeLogTelemetry(gameTime);
        formations.tick(gameTime);
        int membershipCount = memberships.size();
        if (membershipCount == 0) {
            nextMembershipRow = 0;
            return;
        }
        if (nextMembershipRow >= membershipCount) {
            nextMembershipRow = 0;
        }

        int work = Math.min(UNITS_PER_TICK, membershipCount);
        boolean projectionChanged = false;
        try {
            for (int processed = 0; processed < work; processed++) {
                if (nextMembershipRow >= membershipCount) {
                    nextMembershipRow = 0;
                }
                int row = nextMembershipRow++;
                try {
                    projectionChanged |= executeRow(row);
                } catch (RuntimeException failure) {
                    failClosedRow(row, failure);
                }
            }
        } finally {
            // Coalesce a whole stripe into one SavedData/revision mutation.  The finally closes
            // the autosave hole even if a later row fails after an earlier row was persisted.
            if (projectionChanged) {
                dirtyMarker.markDirty();
            }
        }
    }

    /** Replays the current revision when the entity obtains a new loaded runtime instance. */
    public void entityJoined(MillVillager villager) {
        if (server == null) {
            return;
        }
        if (!server.isSameThread()) {
            throw new IllegalStateException("Army entity binding escaped the Minecraft server thread");
        }
        long most = villager.getUUID().getMostSignificantBits();
        long least = villager.getUUID().getLeastSignificantBits();
        int unitHandle = memberships.unitHandleForUuid(most, least);
        if (unitHandle != 0) {
            unitStates.invalidate(unitHandle);
        }
    }

    public void entityLeft(MillVillager villager) {
        entityJoined(villager);
        int unitHandle = memberships.unitHandleForUuid(
                villager.getUUID().getMostSignificantBits(),
                villager.getUUID().getLeastSignificantBits());
        if (unitHandle != 0) {
            formations.removeUnit(unitHandle);
            battles.removeUnit(unitHandle);
        }
    }

    /** Records an authoritative physical death without mutating Realm or Simulation state. */
    public void entityDied(MillVillager victim, Entity source) {
        if (server == null || victim == null) {
            return;
        }
        if (!server.isSameThread()) {
            throw new IllegalStateException("Army death projection escaped the Minecraft server thread");
        }
        int targetUnit = memberships.unitHandleForUuid(
                victim.getUUID().getMostSignificantBits(),
                victim.getUUID().getLeastSignificantBits());
        if (targetUnit == 0 || !ecs.isUnitAlive(targetUnit)) {
            return;
        }
        int targetArmy = ecs.unitArmy(targetUnit);
        if (targetArmy == PackedArmyEcs.NO_ARMY || !ecs.isArmyAlive(targetArmy)) {
            return;
        }

        int sourceUnit = 0;
        int sourceArmy = PackedArmyEcs.NO_ARMY;
        int sourceFaction = -1;
        if (source instanceof MillVillager attacker && attacker.getUUID() != null) {
            sourceUnit = memberships.unitHandleForUuid(
                    attacker.getUUID().getMostSignificantBits(),
                    attacker.getUUID().getLeastSignificantBits());
            if (sourceUnit != 0 && ecs.isUnitAlive(sourceUnit)) {
                sourceArmy = ecs.unitArmy(sourceUnit);
                if (sourceArmy != PackedArmyEcs.NO_ARMY && ecs.isArmyAlive(sourceArmy)) {
                    sourceFaction = ecs.armyFaction(sourceArmy);
                }
            }
        }
        battleEvents.append(
                PhysicalBattleEventLog.UNIT_DEFEATED,
                victim.level().getGameTime(),
                sourceArmy,
                targetArmy,
                sourceUnit,
                targetUnit,
                sourceFaction,
                ecs.armyFaction(targetArmy),
                dimensionId(victim.level().dimension().location()),
                victim.blockPosition().asLong(),
                0);
    }

    /** Immediately relinquishes only a task owned by this bridge before persistent release. */
    public void releaseUnit(int unitHandle, long uuidMost, long uuidLeast) {
        if (server == null) {
            return;
        }
        if (!server.isSameThread()) {
            throw new IllegalStateException("Army unit release must run on the server thread");
        }
        try {
            MillVillager villager = entityBridge.findLoaded(uuidMost, uuidLeast);
            if (villager == null || villager.isRemoved()) {
                return;
            }
            GoalScheduler scheduler = villager.getGoalScheduler();
            if (scheduler == null) {
                return;
            }
            VillagerTask current = scheduler.getCurrentTask();
            if (!(current instanceof StrategicRetainedTask task) || task.unitHandle() != unitHandle) {
                return;
            }
            task.cancel();
            GoalContext context = villager.buildGoalContext();
            if (context != null) {
                scheduler.forceStop(context);
            }
        } catch (RuntimeException failure) {
            LOGGER.warn(
                    "Could not immediately relinquish Millenaire task for released unit {}",
                    Integer.toUnsignedString(unitHandle),
                    failure);
        } finally {
            formations.removeUnit(unitHandle);
            battles.removeUnit(unitHandle);
            unitStates.remove(unitHandle);
        }
    }

    public boolean stop(MinecraftServer stoppingServer) {
        if (server != stoppingServer) {
            return false;
        }
        commandService.installOrderCommitListener(ArmyCommandService.ArmyOrderCommitListener.NOOP);
        logTelemetry("stop");
        server = null;
        ecs = null;
        memberships = null;
        dimensions = null;
        entityBridge = null;
        factionProjection = null;
        commandService = null;
        garrisons = null;
        dirtyMarker = null;
        supplyAccess = ArmySupplyAccess.NONE;
        nextMembershipRow = 0;
        nextTelemetryGameTime = 0L;
        lastLoggedTransitions = 0L;
        orderRevisions.clear();
        unitStates.clear();
        taskPool.clear();
        followTaskPool.clear();
        attackTaskPool.clear();
        garrisonTaskPool.clear();
        formations.clear();
        battles.clear();
        sieges.clear();
        battleEvents.clear();
        return true;
    }

    public int trackedUnitStates() {
        return unitStates.size();
    }

    public OrderExecutionTelemetry telemetry() {
        return telemetry;
    }

    /** Read-only neutral event stream for Realm/Simulation adapters. */
    public PhysicalBattleEventLog battleEvents() {
        return battleEvents;
    }

    /** Allocation-free active physical-siege probe used by settlement breach protection. */
    public boolean activeSiegeNear(ServerLevel level, BlockPos position, int radiusBlocks) {
        if (server == null || level == null || position == null || radiusBlocks < 1) return false;
        requireServerThread();
        int dimensionId = dimensionId(level.dimension().location());
        return sieges.activeNear(dimensionId, position.asLong(), radiusBlocks, level.getGameTime());
    }

    public void installHostilityPolicy(ArmyHostilityPolicy replacement) {
        Objects.requireNonNull(replacement, "replacement");
        if (server != null) {
            requireServerThread();
        }
        battles.hostilityPolicy(replacement);
    }

    /** Aggregates acknowledgements for the exact current order without changing execution state. */
    public byte armyExecutionStatus(int armyHandle) {
        if (server == null || ecs == null || memberships == null || !ecs.isArmyAlive(armyHandle)) {
            return ru.kaiserroman.millenairearmies.network.ArmiesProtocol.EXECUTION_BLOCKED;
        }
        long revision = executionRevision(armyHandle);
        int units = 0;
        int accepted = 0;
        int executing = 0;
        int arrived = 0;
        int blocked = 0;
        for (int row = 0; row < memberships.size(); row++) {
            int unit = memberships.unitHandleAt(row);
            if (!ecs.isUnitAlive(unit) || ecs.unitArmy(unit) != armyHandle) {
                continue;
            }
            units++;
            byte status = revision == 0L || unitStates.needsApply(unit, armyHandle, revision)
                    ? PackedUnitExecutionState.PENDING
                    : unitStates.status(unit);
            if (status == PackedUnitExecutionState.RUNNING) {
                executing++;
            } else if (status == PackedUnitExecutionState.ARRIVED) {
                arrived++;
            } else if (status == PackedUnitExecutionState.BLOCKED) {
                blocked++;
            } else {
                accepted++;
            }
        }
        if (units == 0 || accepted > 0) {
            return ru.kaiserroman.millenairearmies.network.ArmiesProtocol.EXECUTION_ACCEPTED;
        }
        if (executing > 0) {
            return ru.kaiserroman.millenairearmies.network.ArmiesProtocol.EXECUTION_EXECUTING;
        }
        if (blocked > 0) {
            return ru.kaiserroman.millenairearmies.network.ArmiesProtocol.EXECUTION_BLOCKED;
        }
        return arrived == units
                ? ru.kaiserroman.millenairearmies.network.ArmiesProtocol.EXECUTION_ARRIVED
                : ru.kaiserroman.millenairearmies.network.ArmiesProtocol.EXECUTION_ACCEPTED;
    }

    private boolean executeRow(int row) {
        int unitHandle = memberships.unitHandleAt(row);
        if (!ecs.isUnitAlive(unitHandle)) {
            return false;
        }
        int armyHandle = ecs.unitArmy(unitHandle);
        if (armyHandle == PackedArmyEcs.NO_ARMY || !ecs.isArmyAlive(armyHandle)) {
            return false;
        }

        long baseRevision = orderRevisions.revision(armyHandle);
        if (baseRevision == 0L) {
            orderRevisions.observe(
                    armyHandle,
                    ecs.armyOrder(armyHandle),
                    ecs.armyTargetDimension(armyHandle),
                    ecs.armyState(armyHandle),
                    ecs.armyPackedTargetPos(armyHandle));
        }
        long revision = executionRevision(armyHandle);
        if (!unitStates.needsApply(unitHandle, armyHandle, revision)) {
            return false;
        }

        MillVillager villager = entityBridge.findLoaded(
                memberships.uuidMostAt(row), memberships.uuidLeastAt(row));
        if (villager == null
                || villager.isRemoved()
                || !(villager.level() instanceof ServerLevel level)
                || !level.isPositionEntityTicking(villager.blockPosition())) {
            return false;
        }
        GoalScheduler scheduler = villager.getGoalScheduler();
        if (scheduler == null) {
            return false;
        }

        int orderCode = orderRevisions.orderCode(armyHandle);
        VillagerTask currentTask = scheduler.getCurrentTask();
        if (currentTask instanceof StrategicRetainedTask existing
                && existing.unitHandle() == unitHandle) {
            if (existing.armyHandle() == armyHandle && existing.revision() == revision) {
                // A combat-yielded task remains installed until the next entity tick. Let the
                // scheduler complete it before replaying the still-pending revision.
                return false;
            }
            // Publish the successor first so stop(COMPLETED) from the cancelled retained task
            // cannot retry its stale revision.  GoalScheduler will release it normally next tick;
            // unlike forceStop this emits no abandonment event/INFO and preserves route ownership
            // until the scheduler performs its ordinary completion cleanup.
            unitStates.markPending(unitHandle, armyHandle, revision);
            existing.cancel();
            return false;
        }

        if (orderCode == StrategicArmyOrder.HOLD.code()) {
            // Any bridge-owned predecessor has already been cancelled above. HOLD never force-stops
            // an unrelated Millenaire task and simply acknowledges the new persistent projection.
            unitStates.markArrived(unitHandle, armyHandle, revision);
            return UnitOrderProjection.update(ecs, unitHandle, orderCode);
        }
        if (orderCode < StrategicArmyOrder.MOVE.code()
                || orderCode > StrategicArmyOrder.GUARD.code()) {
            unitStates.markBlocked(unitHandle, armyHandle, revision);
            return false;
        }

        boolean strategicFollow = orderCode == StrategicArmyOrder.FOLLOW.code();
        long packedTarget = orderRevisions.packedTarget(armyHandle);
        if (!strategicFollow) {
            int targetX = PackedArmyEcs.unpackBlockX(packedTarget);
            int targetZ = PackedArmyEcs.unpackBlockZ(packedTarget);
            if (!OrderExecutionPolicy.targetInLevel(
                            dimensions,
                            orderRevisions.targetDimensionId(armyHandle),
                            level.dimension().location())
                    || !OrderExecutionPolicy.targetWithinBuildHeight(
                            packedTarget, level.getMinBuildHeight(), level.getMaxBuildHeight())
                    || !level.getWorldBorder().isWithinBounds(targetX + 0.5D, targetZ + 0.5D)) {
                // The committed state remains visible, but unknown/cross-dimension/out-of-bounds
                // targets are acknowledged as blocked and never delegated to navigation.
                unitStates.markBlocked(unitHandle, armyHandle, revision);
                telemetry.blocked();
                return UnitOrderProjection.update(ecs, unitHandle, orderCode);
            }
        }

        VillagerGoal currentGoal = scheduler.getCurrentGoal();
        Village boundVillage = entityBridge.villageFor(villager);
        boolean strategicAttack = orderCode == StrategicArmyOrder.ATTACK.code();
        boolean strategicSiege = orderCode == StrategicArmyOrder.SIEGE.code();
        boolean strategicGarrison = orderCode == StrategicArmyOrder.GARRISON.code();
        boolean strategicGuard = orderCode == StrategicArmyOrder.GUARD.code();
        int garrisonRow = strategicGarrison ? garrisons.findArmy(armyHandle) : -1;
        if (strategicGarrison && garrisonRow < 0) {
            unitStates.markBlocked(unitHandle, armyHandle, revision);
            telemetry.blocked();
            return UnitOrderProjection.update(ecs, unitHandle, orderCode);
        }
        boolean strategicCombat = strategicAttack || strategicSiege || strategicGarrison || strategicGuard;
        if (villager.isVillagerSleeping()
                || villager.isBaby()
                || villager.isHired() && !hiredByController(villager, armyHandle)
                || villager.isRaiderEntity()
                || boundVillage == null
                || strategicCombat
                        && villager.getAttackTarget() != null
                        && !(villager.getAttackTarget() instanceof MillVillager)
                || !strategicCombat && villager.getAttackTarget() != null
                || !strategicCombat && boundVillage.isUnderAttack()
                || !strategicCombat && currentGoal != null && currentGoal.isCombatUrgent()) {
            return false;
        }
        GoalContext context = villager.buildGoalContext();
        if (context == null) {
            return false;
        }
        VillagerNavDriver navigation = villager.getNavManager();
        if (navigation == null) {
            unitStates.markBlocked(unitHandle, armyHandle, revision);
            telemetry.blocked();
            return false;
        }

        VillagerTask task;
        if (strategicFollow) {
            if (!commandService.controllers().hasController(armyHandle)) {
                unitStates.markBlocked(unitHandle, armyHandle, revision);
                telemetry.blocked();
                return UnitOrderProjection.update(ecs, unitHandle, orderCode);
            }
            task = followTaskPool.acquire(
                    unitStates,
                    telemetry,
                    unitHandle,
                    armyHandle,
                    revision,
                    commandService.controllers().uuidMost(armyHandle),
                    commandService.controllers().uuidLeast(armyHandle));
        } else if (strategicAttack || strategicSiege) {
            task = attackTaskPool.acquire(
                    unitStates,
                    telemetry,
                    formations,
                    battles,
                    entityBridge,
                    factionProjection,
                    sieges,
                    battleEvents,
                    supplyAccess,
                    unitHandle,
                    armyHandle,
                    revision,
                    packedTarget,
                    ArmyFormation.fromState(orderRevisions.armyState(armyHandle)).code(),
                    ecs.armyUnitCount(armyHandle),
                    ecs.armyFaction(armyHandle),
                    orderRevisions.targetDimensionId(armyHandle),
                    strategicSiege,
                    ArmyTacticalState.shieldWall(orderRevisions.armyState(armyHandle)),
                    ArmyTacticalState.fireAtWill(orderRevisions.armyState(armyHandle)));
        } else if (orderCode == StrategicArmyOrder.GARRISON.code()) {
            task = garrisonTaskPool.acquire(
                    unitStates,
                    telemetry,
                    formations,
                    battles,
                    entityBridge,
                    factionProjection,
                    supplyAccess,
                    unitHandle,
                    armyHandle,
                    revision,
                    garrisons.musterPositionAt(garrisonRow),
                    garrisons.guardRadiusAt(garrisonRow),
                    ArmyFormation.fromState(orderRevisions.armyState(armyHandle)).code(),
                    ecs.armyUnitCount(armyHandle),
                    ecs.armyFaction(armyHandle),
                    garrisons.supplyPercentAt(garrisonRow),
                    garrisons.readinessPercentAt(garrisonRow),
                    garrisons.moralePercentAt(garrisonRow),
                    ArmyTacticalState.shieldWall(orderRevisions.armyState(armyHandle)),
                    ArmyTacticalState.fireAtWill(orderRevisions.armyState(armyHandle)));
        } else if (strategicGuard) {
            task = garrisonTaskPool.acquire(
                    unitStates,
                    telemetry,
                    formations,
                    battles,
                    entityBridge,
                    factionProjection,
                    supplyAccess,
                    unitHandle,
                    armyHandle,
                    revision,
                    packedTarget,
                    24,
                    ArmyFormation.fromState(orderRevisions.armyState(armyHandle)).code(),
                    ecs.armyUnitCount(armyHandle),
                    ecs.armyFaction(armyHandle),
                    100,
                    100,
                    100,
                    ArmyTacticalState.shieldWall(orderRevisions.armyState(armyHandle)),
                    ArmyTacticalState.fireAtWill(orderRevisions.armyState(armyHandle)));
        } else {
            task = taskPool.acquire(
                    unitStates,
                    telemetry,
                    unitHandle,
                    armyHandle,
                    revision,
                    packedTarget);
        }
        try {
            scheduler.forceTask(task, context);
            // forceTask only installs the task. A loaded entity may still be temporarily excluded
            // from entity AI ticks by an external activation policy, so prime exactly one bounded
            // public task tick before acknowledging execution. This establishes the Millenaire
            // navigation/combat state without implementing either subsystem in Armies.
            task.tick(context);
            if (!task.isFinished()) {
                unitStates.markRunning(unitHandle, armyHandle, revision);
                telemetry.executing();
            }
            return UnitOrderProjection.update(ecs, unitHandle, orderCode);
        } catch (RuntimeException failure) {
            unitStates.markBlocked(unitHandle, armyHandle, revision);
            telemetry.blocked();
            LOGGER.warn(
                    "Could not delegate army {} revision {} to loaded Millenaire unit {}",
                    Integer.toUnsignedString(armyHandle),
                    revision,
                    Integer.toUnsignedString(unitHandle),
                    failure);
            return false;
        }
    }

    private long executionRevision(int armyHandle) {
        long orderRevision = orderRevisions.revision(armyHandle);
        if (orderRevision == 0L) {
            return 0L;
        }
        if (ecs.armyOrder(armyHandle) != StrategicArmyOrder.GARRISON.code()) {
            return orderRevision;
        }
        int row = garrisons.findArmy(armyHandle);
        if (row < 0) {
            return orderRevision;
        }
        long garrisonRevision = garrisons.revisionAt(row);
        return Long.MAX_VALUE - orderRevision < garrisonRevision
                ? Long.MAX_VALUE
                : orderRevision + garrisonRevision;
    }

    private int dimensionId(ResourceLocation dimension) {
        for (int id = 0; id < dimensions.size(); id++) {
            if (dimensions.matches(id, dimension)) {
                return id;
            }
        }
        return -1;
    }

    private boolean hiredByController(MillVillager villager, int armyHandle) {
        if (!villager.isHired() || !commandService.controllers().hasController(armyHandle)) {
            return false;
        }
        UUID hiredBy = villager.getHiredBy();
        return hiredBy != null
                && hiredBy.getMostSignificantBits() == commandService.controllers().uuidMost(armyHandle)
                && hiredBy.getLeastSignificantBits() == commandService.controllers().uuidLeast(armyHandle);
    }

    private void orderCommitted(
            int armyHandle,
            int orderCode,
            int targetDimensionId,
            long packedTargetPosition) {
        orderRevisions.observe(
                armyHandle,
                orderCode,
                targetDimensionId,
                ecs.armyState(armyHandle),
                packedTargetPosition);
        telemetry.accepted();
    }

    private void failClosedRow(int row, RuntimeException failure) {
        int unitHandle = 0;
        int armyHandle = PackedArmyEcs.NO_ARMY;
        long revision = 0L;
        try {
            if (row >= 0 && row < memberships.size()) {
                unitHandle = memberships.unitHandleAt(row);
                if (ecs.isUnitAlive(unitHandle)) {
                    armyHandle = ecs.unitArmy(unitHandle);
                    if (armyHandle != PackedArmyEcs.NO_ARMY && ecs.isArmyAlive(armyHandle)) {
                        if (orderRevisions.revision(armyHandle) == 0L) {
                            orderRevisions.observe(
                                    armyHandle,
                                    ecs.armyOrder(armyHandle),
                                    ecs.armyTargetDimension(armyHandle),
                                    ecs.armyState(armyHandle),
                                    ecs.armyPackedTargetPos(armyHandle));
                        }
                        revision = executionRevision(armyHandle);
                        if (revision > 0L) {
                            unitStates.markBlocked(unitHandle, armyHandle, revision);
                            telemetry.blocked();
                        }
                    }
                }
            }
        } catch (RuntimeException quarantineFailure) {
            failure.addSuppressed(quarantineFailure);
        }
        LOGGER.warn(
                "Army order row failed closed: unit={} army={} revision={}",
                Integer.toUnsignedString(unitHandle),
                Integer.toUnsignedString(armyHandle),
                revision,
                failure);
    }

    private void requireServerThread() {
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException("Army order execution escaped the Minecraft server thread");
        }
    }

    private void maybeLogTelemetry(long gameTime) {
        if (gameTime < nextTelemetryGameTime) {
            return;
        }
        nextTelemetryGameTime = gameTime + TELEMETRY_INTERVAL_TICKS;
        if (telemetry.transitionCount() != lastLoggedTransitions) {
            logTelemetry("periodic");
        }
    }

    private void logTelemetry(String reason) {
        long transitions = telemetry.transitionCount();
        if (transitions == 0L && "stop".equals(reason)) {
            return;
        }
        LOGGER.info(
                "[BANNEROK_ARMY_ORDER_EXECUTION] reason={} accepted={} executing={} arrived={} blocked={} tracked_units={}",
                reason,
                telemetry.acceptedCount(),
                telemetry.executingCount(),
                telemetry.arrivedCount(),
                telemetry.blockedCount(),
                unitStates.size());
        lastLoggedTransitions = transitions;
    }
}
