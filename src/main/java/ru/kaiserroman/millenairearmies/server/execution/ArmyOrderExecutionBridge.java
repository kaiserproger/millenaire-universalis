package ru.kaiserroman.millenairearmies.server.execution;

import com.mojang.logging.LogUtils;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
 * <p>Millenaire beta.2's public {@code GoalScheduler.forceTask} sets {@code currentGoal} to null,
 * so its generic urgent-goal preemption loop cannot preempt a forced task.  This bridge therefore
 * yields only for concrete public combat signals available without target-finding: an existing
 * attack target or {@link Village#isUnderAttack()}.  It does not periodically tear down routes;
 * Millenaire retains destination, waypoint traversal, stuck recovery and teleport recovery until
 * arrival, abandonment, a combat signal, or a newly committed order.</p>
 */
public final class ArmyOrderExecutionBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int UNITS_PER_TICK = 64;
    private static final long TELEMETRY_INTERVAL_TICKS = 1_200L;

    private final PackedArmyOrderRevisions orderRevisions = new PackedArmyOrderRevisions();
    private final PackedUnitExecutionState unitStates = new PackedUnitExecutionState();
    private final StrategicMoveTaskPool taskPool = new StrategicMoveTaskPool();
    private final OrderExecutionTelemetry telemetry = new OrderExecutionTelemetry();

    private MinecraftServer server;
    private PackedArmyEcs ecs;
    private PackedUnitMembership memberships;
    private StableDimensionTable dimensions;
    private MillenaireEntityBridge entityBridge;
    private ArmyCommandService commandService;
    private ArmyCommandService.DirtyMarker dirtyMarker;
    private int nextMembershipRow;
    private long nextTelemetryGameTime;
    private long lastLoggedTransitions;

    public boolean start(
            MinecraftServer startingServer,
            PackedArmyEcs persistedEcs,
            PackedUnitMembership persistedMemberships,
            StableDimensionTable persistedDimensions,
            MillenaireEntityBridge loadedEntities,
            ArmyCommandService runningCommandService,
            ArmyCommandService.DirtyMarker persistedDirtyMarker) {
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
        commandService = Objects.requireNonNull(runningCommandService, "runningCommandService");
        dirtyMarker = Objects.requireNonNull(persistedDirtyMarker, "persistedDirtyMarker");
        orderRevisions.reserve(ecs.armySize());
        unitStates.reserve(memberships.size());

        PackedArmyEcs.ArmyCursor cursor = ecs.newArmyCursor();
        while (cursor.advance()) {
            orderRevisions.observe(
                    cursor.handle(),
                    cursor.order(),
                    cursor.targetDimension(),
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
        maybeLogTelemetry(tickingServer.overworld().getGameTime());
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
            if (!(current instanceof StrategicMoveTask task) || task.unitHandle() != unitHandle) {
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
        commandService = null;
        dirtyMarker = null;
        nextMembershipRow = 0;
        nextTelemetryGameTime = 0L;
        lastLoggedTransitions = 0L;
        orderRevisions.clear();
        unitStates.clear();
        taskPool.clear();
        return true;
    }

    public int trackedUnitStates() {
        return unitStates.size();
    }

    public OrderExecutionTelemetry telemetry() {
        return telemetry;
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
        GoalScheduler scheduler = villager.getGoalScheduler();
        if (scheduler == null) {
            return false;
        }

        int orderCode = orderRevisions.orderCode(armyHandle);
        VillagerTask currentTask = scheduler.getCurrentTask();
        if (currentTask instanceof StrategicMoveTask existing
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
                || orderCode > StrategicArmyOrder.LOGISTICS.code()) {
            unitStates.markBlocked(unitHandle, armyHandle, revision);
            return false;
        }

        long packedTarget = orderRevisions.packedTarget(armyHandle);
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

        VillagerGoal currentGoal = scheduler.getCurrentGoal();
        Village boundVillage = entityBridge.villageFor(villager);
        if (villager.isVillagerSleeping()
                || villager.isBaby()
                || villager.isHired()
                || villager.isRaiderEntity()
                || villager.getAttackTarget() != null
                || boundVillage == null
                || boundVillage.isUnderAttack()
                || currentGoal != null && currentGoal.isCombatUrgent()) {
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

        StrategicMoveTask task = taskPool.acquire(
                unitStates,
                telemetry,
                unitHandle,
                armyHandle,
                revision,
                packedTarget);
        try {
            scheduler.forceTask(task, context);
            unitStates.markRunning(unitHandle, armyHandle, revision);
            telemetry.executing();
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

    private void orderCommitted(
            int armyHandle,
            int orderCode,
            int targetDimensionId,
            long packedTargetPosition) {
        orderRevisions.observe(
                armyHandle, orderCode, targetDimensionId, packedTargetPosition);
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
                        revision = orderRevisions.revision(armyHandle);
                        if (revision == 0L) {
                            revision = orderRevisions.observe(
                                    armyHandle,
                                    ecs.armyOrder(armyHandle),
                                    ecs.armyTargetDimension(armyHandle),
                                    ecs.armyPackedTargetPos(armyHandle));
                        }
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
