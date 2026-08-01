package ru.kaiserroman.millenairearmies.server.execution;

import com.mojang.logging.LogUtils;
import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.entity.MillVillager;
import org.millenaire.goal.GoalContext;
import org.millenaire.goal.GoalScheduler;
import org.millenaire.goal.VillagerGoal;
import org.millenaire.goal.VillagerTask;
import org.millenaire.village.Village;
import org.slf4j.Logger;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireEntityBridge;
import ru.kaiserroman.millenairearmies.persistence.PackedUnitMembership;
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

    private final PackedArmyOrderRevisions orderRevisions = new PackedArmyOrderRevisions();
    private final PackedUnitExecutionState unitStates = new PackedUnitExecutionState();
    private final StrategicMoveTaskPool taskPool = new StrategicMoveTaskPool();

    private MinecraftServer server;
    private PackedArmyEcs ecs;
    private PackedUnitMembership memberships;
    private MillenaireEntityBridge entityBridge;
    private ArmyCommandService commandService;
    private ArmyCommandService.DirtyMarker dirtyMarker;
    private int nextMembershipRow;

    public boolean start(
            MinecraftServer startingServer,
            PackedArmyEcs persistedEcs,
            PackedUnitMembership persistedMemberships,
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

        server = startingServer;
        ecs = Objects.requireNonNull(persistedEcs, "persistedEcs");
        memberships = Objects.requireNonNull(persistedMemberships, "persistedMemberships");
        entityBridge = Objects.requireNonNull(loadedEntities, "loadedEntities");
        commandService = Objects.requireNonNull(runningCommandService, "runningCommandService");
        dirtyMarker = Objects.requireNonNull(persistedDirtyMarker, "persistedDirtyMarker");
        orderRevisions.reserve(ecs.armySize());
        unitStates.reserve(memberships.size());

        PackedArmyEcs.ArmyCursor cursor = ecs.newArmyCursor();
        while (cursor.advance()) {
            orderRevisions.observe(cursor.handle(), cursor.order(), cursor.packedTargetPos());
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
        entityJoined(villager);
    }

    public boolean stop(MinecraftServer stoppingServer) {
        if (server != stoppingServer) {
            return false;
        }
        commandService.installOrderCommitListener(ArmyCommandService.ArmyOrderCommitListener.NOOP);
        server = null;
        ecs = null;
        memberships = null;
        entityBridge = null;
        commandService = null;
        dirtyMarker = null;
        nextMembershipRow = 0;
        orderRevisions.clear();
        unitStates.clear();
        taskPool.clear();
        return true;
    }

    public int trackedUnitStates() {
        return unitStates.size();
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
                    armyHandle, ecs.armyOrder(armyHandle), ecs.armyPackedTargetPos(armyHandle));
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
            unitStates.markTerminal(unitHandle, armyHandle, revision);
            return UnitOrderProjection.update(ecs, unitHandle, orderCode);
        }
        if (orderCode < StrategicArmyOrder.MOVE.code()
                || orderCode > StrategicArmyOrder.LOGISTICS.code()) {
            unitStates.markTerminal(unitHandle, armyHandle, revision);
            return false;
        }

        long packedTarget = orderRevisions.packedTarget(armyHandle);
        int targetX = PackedArmyEcs.unpackBlockX(packedTarget);
        int targetZ = PackedArmyEcs.unpackBlockZ(packedTarget);
        if (!OrderExecutionPolicy.targetWithinBuildHeight(
                        packedTarget, level.getMinBuildHeight(), level.getMaxBuildHeight())
                || !level.getWorldBorder().isWithinBounds(targetX + 0.5D, targetZ + 0.5D)) {
            // The committed strategic state remains visible, but an invalid coordinate is never
            // delegated to navigation. The optional runtime interprets targets in this unit's
            // current dimension because phase2 persistence has no dimension column yet.
            unitStates.markTerminal(unitHandle, armyHandle, revision);
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

        StrategicMoveTask task = taskPool.acquire(
                unitStates,
                unitHandle,
                armyHandle,
                revision,
                packedTarget);
        try {
            scheduler.forceTask(task, context);
            unitStates.markRunning(unitHandle, armyHandle, revision);
            return UnitOrderProjection.update(ecs, unitHandle, orderCode);
        } catch (RuntimeException failure) {
            unitStates.markRetry(unitHandle, armyHandle, revision);
            LOGGER.warn(
                    "Could not delegate army {} revision {} to loaded Millenaire unit {}",
                    Integer.toUnsignedString(armyHandle),
                    revision,
                    Integer.toUnsignedString(unitHandle),
                    failure);
            return false;
        }
    }

    private void orderCommitted(int armyHandle, int orderCode, long packedTargetPosition) {
        orderRevisions.observe(armyHandle, orderCode, packedTargetPosition);
    }
}
