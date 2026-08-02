package ru.kaiserroman.millenairearmies.server.execution;

import com.mojang.logging.LogUtils;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import org.millenaire.entity.MillVillager;
import org.millenaire.entity.VillagerNavDriver;
import org.millenaire.goal.GoalContext;
import org.millenaire.goal.GoalScheduler;
import org.millenaire.goal.VillagerGoal;
import org.millenaire.goal.VillagerTask;
import org.millenaire.village.Village;
import org.slf4j.Logger;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireEntityBridge;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireVillageIndex;
import ru.kaiserroman.millenairearmies.model.ArmyFormation;
import ru.kaiserroman.millenairearmies.persistence.PackedUnitMembership;
import ru.kaiserroman.millenairearmies.persistence.StableDimensionTable;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandService;
import ru.kaiserroman.millenairearmies.server.service.StrategicArmyOrder;

/**
 * Bounded bridge from committed strategic orders to Millenaire's public goal/navigation API.
 *
 * <p>Only already loaded, entity-ticking {@link MillVillager}s are considered.  Each server tick
 * advances a fixed stripe of primitive membership rows.  An unchanged acknowledged revision is a
 * read-only fast path: it creates no task, context, UUID, iterator, or position object.</p>
 *
 * <p>Move/rally/logistics tasks yield to Millenaire combat. ATTACK installs a separate retained
 * task which selects only real loaded entities and invokes Millenaire's own navigation and combat
 * methods; health, arrows, deaths and knockback remain Minecraft entity state, never packed
 * strategic arithmetic.</p>
 */
public final class ArmyOrderExecutionBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int UNITS_PER_TICK = 64;

    private final PackedArmyOrderRevisions orderRevisions = new PackedArmyOrderRevisions();
    private final PackedUnitExecutionState unitStates = new PackedUnitExecutionState();
    private final StrategicMoveTaskPool taskPool = new StrategicMoveTaskPool();
    private final StrategicAttackTaskPool attackTaskPool = new StrategicAttackTaskPool();
    private final ArmyFormationCoordinator formations = new ArmyFormationCoordinator();

    private MinecraftServer server;
    private PackedArmyEcs ecs;
    private PackedUnitMembership memberships;
    private StableDimensionTable dimensions;
    private MillenaireEntityBridge entityBridge;
    private ArmyCommandService commandService;
    private ArmyCommandService.DirtyMarker dirtyMarker;
    private PhysicalBattleCoordinator battleCoordinator;
    private int nextMembershipRow;

    public boolean start(
            MinecraftServer startingServer,
            PackedArmyEcs persistedEcs,
            PackedUnitMembership persistedMemberships,
            StableDimensionTable persistedDimensions,
            MillenaireEntityBridge loadedEntities,
            ArmyCommandService runningCommandService,
            ArmyCommandService.DirtyMarker persistedDirtyMarker) {
        return start(
                startingServer,
                persistedEcs,
                persistedMemberships,
                persistedDimensions,
                loadedEntities,
                new MillenaireVillageIndex(),
                runningCommandService,
                persistedDirtyMarker);
    }

    public boolean start(
            MinecraftServer startingServer,
            PackedArmyEcs persistedEcs,
            PackedUnitMembership persistedMemberships,
            StableDimensionTable persistedDimensions,
            MillenaireEntityBridge loadedEntities,
            MillenaireVillageIndex villageIndex,
            ArmyCommandService runningCommandService,
            ArmyCommandService.DirtyMarker persistedDirtyMarker) {
        return start(
                startingServer,
                persistedEcs,
                persistedMemberships,
                persistedDimensions,
                loadedEntities,
                villageIndex,
                runningCommandService,
                persistedDirtyMarker,
                RealmCapturePolicy.ALLOW_ALL);
    }

    public boolean start(
            MinecraftServer startingServer,
            PackedArmyEcs persistedEcs,
            PackedUnitMembership persistedMemberships,
            StableDimensionTable persistedDimensions,
            MillenaireEntityBridge loadedEntities,
            MillenaireVillageIndex villageIndex,
            ArmyCommandService runningCommandService,
            ArmyCommandService.DirtyMarker persistedDirtyMarker,
            RealmCapturePolicy capturePolicy) {
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

        server = startingServer;
        ecs = Objects.requireNonNull(persistedEcs, "persistedEcs");
        memberships = Objects.requireNonNull(persistedMemberships, "persistedMemberships");
        dimensions = Objects.requireNonNull(persistedDimensions, "persistedDimensions");
        entityBridge = Objects.requireNonNull(loadedEntities, "loadedEntities");
        commandService = Objects.requireNonNull(runningCommandService, "runningCommandService");
        dirtyMarker = Objects.requireNonNull(persistedDirtyMarker, "persistedDirtyMarker");
        battleCoordinator = new PhysicalBattleCoordinator(
                startingServer,
                ecs,
                memberships,
                runningCommandService.controllers(),
                entityBridge,
                Objects.requireNonNull(villageIndex, "villageIndex"),
                dirtyMarker,
                Objects.requireNonNull(capturePolicy, "capturePolicy"));
        orderRevisions.reserve(ecs.armySize());
        unitStates.reserve(memberships.size());
        formations.reserve(ecs.armySize(), memberships.size());

        PackedArmyEcs.ArmyCursor cursor = ecs.newArmyCursor();
        while (cursor.advance()) {
            orderRevisions.observe(
                    cursor.handle(),
                    cursor.order(),
                    cursor.state(),
                    cursor.targetDimension(),
                    cursor.packedTargetPos());
        }
        commandService.installOrderCommitListener(this::orderCommitted);
        nextMembershipRow = 0;
        return true;
    }

    /** Advances at most {@value #UNITS_PER_TICK} membership rows. */
    public void tick(MinecraftServer tickingServer) {
        if (server != tickingServer) {
            return;
        }
        formations.tick(tickingServer.overworld().getGameTime());
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
                projectionChanged |= executeRow(row);
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
        long most = villager.getUUID().getMostSignificantBits();
        long least = villager.getUUID().getLeastSignificantBits();
        int unitHandle = memberships.unitHandleForUuid(most, least);
        if (unitHandle != 0) {
            unitStates.invalidate(unitHandle);
        }
    }

    public void entityLeft(MillVillager villager) {
        if (server == null) return;
        long most = villager.getUUID().getMostSignificantBits();
        long least = villager.getUUID().getLeastSignificantBits();
        int unitHandle = memberships.unitHandleForUuid(most, least);
        if (unitHandle != 0) {
            unitStates.invalidate(unitHandle);
            formations.removeUnit(unitHandle);
        }
    }

    public boolean stop(MinecraftServer stoppingServer) {
        if (server != stoppingServer) {
            return false;
        }
        commandService.installOrderCommitListener(ArmyCommandService.ArmyOrderCommitListener.NOOP);
        if (battleCoordinator != null) {
            LOGGER.info(
                    "[BANNEROK_PHYSICAL_BATTLE_METRICS] target_acquisitions={} attack_actions={} damage_events={} damage_millihearts={} deaths={} captures={} formation_advances={} cohesion_pauses={}",
                    battleCoordinator.targetAcquisitions(),
                    battleCoordinator.attackActions(),
                    battleCoordinator.damageEvents(),
                    battleCoordinator.damageMilliHearts(),
                    battleCoordinator.deaths(),
                    battleCoordinator.captures(),
                    formations.formationAdvances(),
                    formations.cohesionPauses());
        }
        server = null;
        ecs = null;
        memberships = null;
        dimensions = null;
        entityBridge = null;
        commandService = null;
        dirtyMarker = null;
        battleCoordinator = null;
        nextMembershipRow = 0;
        orderRevisions.clear();
        unitStates.clear();
        taskPool.clear();
        attackTaskPool.clear();
        formations.clear();
        return true;
    }

    public void entityDamaged(MillVillager victim, LivingEntity source, float healthDamage) {
        if (battleCoordinator != null) battleCoordinator.damaged(victim, source, healthDamage);
    }

    public boolean entityDied(MillVillager villager) {
        if (memberships != null) {
            int unitHandle = memberships.unitHandleForUuid(
                    villager.getUUID().getMostSignificantBits(),
                    villager.getUUID().getLeastSignificantBits());
            if (unitHandle != 0) formations.removeUnit(unitHandle);
        }
        return battleCoordinator != null && battleCoordinator.died(villager);
    }

    public PhysicalBattleCoordinator battleCoordinator() {
        return battleCoordinator;
    }

    public int trackedUnitStates() {
        return unitStates.size();
    }

    /** Aggregates acknowledgements for the exact current order without changing execution state. */
    public byte armyExecutionStatus(int armyHandle) {
        if (server == null || ecs == null || memberships == null || !ecs.isArmyAlive(armyHandle)) {
            return ru.kaiserroman.millenairearmies.network.ArmiesProtocol.EXECUTION_BLOCKED;
        }
        long revision = orderRevisions.revision(armyHandle);
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

        long revision = orderRevisions.revision(armyHandle);
        if (revision == 0L) {
            revision = orderRevisions.observe(
                    armyHandle,
                    ecs.armyOrder(armyHandle),
                    ecs.armyState(armyHandle),
                    ecs.armyTargetDimension(armyHandle),
                    ecs.armyPackedTargetPos(armyHandle));
        }
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
        if (!level.dimension().location().equals(
                dimensions.name(orderRevisions.targetDimensionId(armyHandle)))) {
            unitStates.markBlocked(unitHandle, armyHandle, revision);
            return UnitOrderProjection.update(ecs, unitHandle, orderRevisions.orderCode(armyHandle));
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
                || orderCode > StrategicArmyOrder.ATTACK.code()) {
            unitStates.markBlocked(unitHandle, armyHandle, revision);
            return false;
        }

        long packedTarget = orderRevisions.packedTarget(armyHandle);
        int targetX = PackedArmyEcs.unpackBlockX(packedTarget);
        int targetZ = PackedArmyEcs.unpackBlockZ(packedTarget);
        if (!OrderExecutionPolicy.targetWithinBuildHeight(
                        packedTarget, level.getMinBuildHeight(), level.getMaxBuildHeight())
                || !level.getWorldBorder().isWithinBounds(targetX + 0.5D, targetZ + 0.5D)) {
            // The committed strategic state remains visible, but an invalid coordinate is never
            // delegated to navigation. Dimension equality was already enforced above.
            unitStates.markBlocked(unitHandle, armyHandle, revision);
            return UnitOrderProjection.update(ecs, unitHandle, orderCode);
        }

        VillagerGoal currentGoal = scheduler.getCurrentGoal();
        Village boundVillage = entityBridge.villageFor(villager);
        boolean attackOrder = orderCode == StrategicArmyOrder.ATTACK.code();
        if (villager.isVillagerSleeping()
                || villager.isBaby()
                || villager.isRaiderEntity()
                || boundVillage == null
                || !attackOrder && (villager.isHired()
                        || villager.getAttackTarget() != null
                        || boundVillage.isUnderAttack()
                        || currentGoal != null && currentGoal.isCombatUrgent())) {
            return false;
        }
        GoalContext context = villager.buildGoalContext();
        if (context == null) {
            return false;
        }
        VillagerNavDriver navigation = villager.getNavManager();
        if (navigation == null) {
            unitStates.markBlocked(unitHandle, armyHandle, revision);
            return false;
        }

        VillagerTask task;
        if (attackOrder) {
            if (villager.isVillagerSleeping() || villager.isBaby() || battleCoordinator == null) {
                return false;
            }
            task = attackTaskPool.acquire(
                    unitStates,
                    battleCoordinator,
                    formations,
                    unitHandle,
                    armyHandle,
                    revision,
                    ArmyFormation.fromState(orderRevisions.armyState(armyHandle)).code(),
                    ecs.armyUnitCount(armyHandle),
                    packedTarget);
        } else {
            task = taskPool.acquire(
                    unitStates,
                    unitHandle,
                    armyHandle,
                    revision,
                    packedTarget);
        }
        try {
            scheduler.forceTask(task, context);
            unitStates.markRunning(unitHandle, armyHandle, revision);
            return UnitOrderProjection.update(ecs, unitHandle, orderCode);
        } catch (RuntimeException failure) {
            unitStates.markBlocked(unitHandle, armyHandle, revision);
            LOGGER.warn(
                    "Could not delegate army {} revision {} to loaded Millenaire unit {}",
                    Integer.toUnsignedString(armyHandle),
                    revision,
                    Integer.toUnsignedString(unitHandle),
                    failure);
            return false;
        }
    }

    private void orderCommitted(
            int armyHandle,
            int orderCode,
            int armyState,
            int targetDimensionId,
            long packedTargetPosition) {
        orderRevisions.observe(
                armyHandle, orderCode, armyState, targetDimensionId, packedTargetPosition);
    }
}
