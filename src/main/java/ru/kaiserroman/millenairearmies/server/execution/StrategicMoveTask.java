package ru.kaiserroman.millenairearmies.server.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.millenaire.entity.MillVillager;
import org.millenaire.entity.VillagerNavDriver;
import org.millenaire.goal.GoalContext;
import org.millenaire.goal.ProgressAwareTask;
import org.millenaire.goal.StopReason;
import org.millenaire.goal.TravelPhase;
import ru.kaiserroman.millenairearmies.SarvarMillenaireArmies;

/**
 * A one-shot public-API Millenaire task that owns navigation only until arrival or abandonment.
 * It contains no pathfinder, target finder, or combat behavior of its own.
 */
final class StrategicMoveTask extends ProgressAwareTask implements StrategicRetainedTask {
    private static final ResourceLocation GOAL_ID = ResourceLocation.fromNamespaceAndPath(
            SarvarMillenaireArmies.MOD_ID, "strategic_move");
    private static final Component LABEL = Component.translatable(
            "goal.millenaire_armies.strategic_move");
    private static final double ARRIVE_DISTANCE = 3.0D;
    private static final double WALK_SPEED = 0.5D;
    private static final int PROGRESS_SAMPLE_TICKS = 20;
    private static final double PROGRESS_DISTANCE_SQ = 1.0D;

    private final PackedUnitExecutionState executionState;
    private final OrderExecutionTelemetry telemetry;
    private final int unitHandle;
    private int armyHandle;
    private long revision;
    private long packedTarget;
    private BlockPos target;

    private boolean finished;
    private boolean terminal;
    private boolean cancelled;
    private int sampleTicks;
    private double sampleX;
    private double sampleY;
    private double sampleZ;
    private boolean sampled;
    private int retargetCooldown;

    StrategicMoveTask(
            PackedUnitExecutionState executionState,
            OrderExecutionTelemetry telemetry,
            int unitHandle) {
        this.executionState = executionState;
        this.telemetry = telemetry;
        this.unitHandle = unitHandle;
    }

    void rearm(int armyHandle, long revision, long packedTarget) {
        this.armyHandle = armyHandle;
        this.revision = revision;
        if (target == null || this.packedTarget != packedTarget) {
            this.packedTarget = packedTarget;
            target = BlockPos.of(packedTarget);
        }
        finished = false;
        terminal = false;
        cancelled = false;
        sampleTicks = 0;
        sampleX = 0.0D;
        sampleY = 0.0D;
        sampleZ = 0.0D;
        sampled = false;
        retargetCooldown = 0;
    }

    @Override
    public int unitHandle() {
        return unitHandle;
    }

    @Override
    public int armyHandle() {
        return armyHandle;
    }

    @Override
    public long revision() {
        return revision;
    }

    /** Allocation-free cancellation observed by GoalScheduler on the next normal entity tick. */
    @Override
    public boolean cancel() {
        if (finished) {
            return false;
        }
        cancelled = true;
        finished = true;
        return true;
    }

    @Override
    public ResourceLocation goalId() {
        return GOAL_ID;
    }

    @Override
    public void tick(GoalContext context) {
        MillVillager villager = context.villager();
        VillagerNavDriver navigation = villager.getNavManager();

        // Yield to Millenaire's urgent goal selection without reproducing any target-finding.
        if (villager.getAttackTarget() != null
                || context.village() != null && context.village().isUnderAttack()) {
            executionState.markRetry(unitHandle, armyHandle, revision);
            finished = true;
            BoundedNavigationDelegation.stop(navigation, villager);
            return;
        }

        int previousRetargetCooldown = retargetCooldown;
        retargetCooldown = BoundedNavigationDelegation.retarget(
                navigation, villager, target, WALK_SPEED, retargetCooldown);
        if (retargetCooldown > previousRetargetCooldown) {
            reportProgress();
        }
        if (navigation.isArrivedSameFloor(villager, ARRIVE_DISTANCE)) {
            terminal = true;
            finished = true;
            if (executionState.markArrivedIfCurrent(unitHandle, armyHandle, revision)) {
                telemetry.arrived();
            }
            BoundedNavigationDelegation.stop(navigation, villager);
            return;
        }
        if (navigation.isAbandoned()) {
            terminal = true;
            finished = true;
            if (executionState.markBlockedIfCurrent(unitHandle, armyHandle, revision)) {
                telemetry.blocked();
            }
            BoundedNavigationDelegation.stop(navigation, villager);
            return;
        }

        if (++sampleTicks >= PROGRESS_SAMPLE_TICKS) {
            sampleTicks = 0;
            double x = villager.getX();
            double y = villager.getY();
            double z = villager.getZ();
            if (!sampled) {
                sampled = true;
            } else {
                double dx = x - sampleX;
                double dy = y - sampleY;
                double dz = z - sampleZ;
                if (dx * dx + dy * dy + dz * dz >= PROGRESS_DISTANCE_SQ) {
                    reportProgress();
                }
            }
            sampleX = x;
            sampleY = y;
            sampleZ = z;
        }
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public void stop(GoalContext context, StopReason reason) {
        if (context != null) {
            BoundedNavigationDelegation.stop(
                    context.villager().getNavManager(), context.villager());
        }
        if (!terminal && !cancelled) {
            if (reason == StopReason.IMPOSSIBLE) {
                terminal = true;
                if (executionState.markBlockedIfCurrent(unitHandle, armyHandle, revision)) {
                    telemetry.blocked();
                }
            } else {
                executionState.markRetry(unitHandle, armyHandle, revision);
            }
        }
        finished = true;
    }

    @Override
    public TravelPhase getTravelPhase() {
        return terminal ? TravelPhase.AT_DESTINATION : TravelPhase.TRAVELLING;
    }

    @Override
    public Component getGoalLabel() {
        return LABEL;
    }
}
