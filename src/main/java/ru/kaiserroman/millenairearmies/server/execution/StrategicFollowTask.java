package ru.kaiserroman.millenairearmies.server.execution;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.millenaire.entity.MillVillager;
import org.millenaire.entity.VillagerNavDriver;
import org.millenaire.goal.GoalContext;
import org.millenaire.goal.ProgressAwareTask;
import org.millenaire.goal.StopReason;
import org.millenaire.goal.TravelPhase;
import ru.kaiserroman.millenairearmies.UniversalisIds;

/** Retained public-navigation task that continuously follows the army controller. */
final class StrategicFollowTask extends ProgressAwareTask implements StrategicRetainedTask {
    private static final ResourceLocation GOAL_ID = ResourceLocation.fromNamespaceAndPath(
            UniversalisIds.MOD_ID, "strategic_follow");
    private static final Component LABEL = Component.translatable(
            "goal.millenaire_armies.strategic_follow");
    private static final double WALK_SPEED = 0.62D;
    private static final double STOP_DISTANCE_SQ = 3.5D * 3.5D;
    private static final int RETARGET_TICKS = 8;

    private final PackedUnitExecutionState executionState;
    private final OrderExecutionTelemetry telemetry;
    private final int unitHandle;
    private int armyHandle;
    private long revision;
    private long ownerMost;
    private long ownerLeast;
    private UUID ownerUuid;
    private boolean finished;
    private boolean terminal;
    private boolean cancelled;
    private int retargetCooldown;

    StrategicFollowTask(
            PackedUnitExecutionState executionState,
            OrderExecutionTelemetry telemetry,
            int unitHandle) {
        this.executionState = executionState;
        this.telemetry = telemetry;
        this.unitHandle = unitHandle;
    }

    void rearm(int armyHandle, long revision, long ownerMost, long ownerLeast) {
        this.armyHandle = armyHandle;
        this.revision = revision;
        if (ownerUuid == null || this.ownerMost != ownerMost || this.ownerLeast != ownerLeast) {
            this.ownerMost = ownerMost;
            this.ownerLeast = ownerLeast;
            ownerUuid = new UUID(ownerMost, ownerLeast);
        }
        finished = false;
        terminal = false;
        cancelled = false;
        retargetCooldown = 0;
    }

    @Override public int unitHandle() { return unitHandle; }
    @Override public int armyHandle() { return armyHandle; }
    @Override public long revision() { return revision; }

    @Override
    public boolean cancel() {
        if (finished) return false;
        cancelled = true;
        finished = true;
        return true;
    }

    @Override public ResourceLocation goalId() { return GOAL_ID; }

    @Override
    public void tick(GoalContext context) {
        MillVillager villager = context.villager();
        VillagerNavDriver navigation = villager.getNavManager();
        if (villager.getAttackTarget() != null
                || context.village() != null && context.village().isUnderAttack()) {
            executionState.markRetry(unitHandle, armyHandle, revision);
            finished = true;
            BoundedNavigationDelegation.stop(navigation, villager);
            return;
        }
        MinecraftServer server = villager.getServer();
        ServerPlayer owner = server == null ? null : server.getPlayerList().getPlayer(ownerUuid);
        if (owner == null || owner.isRemoved() || owner.level() != villager.level()) {
            terminal = true;
            finished = true;
            if (executionState.markBlockedIfCurrent(unitHandle, armyHandle, revision)) {
                telemetry.blocked();
            }
            BoundedNavigationDelegation.stop(navigation, villager);
            return;
        }
        double distanceSq = villager.distanceToSqr(owner);
        if (distanceSq <= STOP_DISTANCE_SQ) {
            BoundedNavigationDelegation.stop(navigation, villager);
            villager.setSprinting(false);
            retargetCooldown = 0;
            reportProgress();
            return;
        }
        int lane = Math.floorMod(unitHandle, 5) - 2;
        double yaw = Math.toRadians(owner.getYRot());
        double backX = Math.sin(yaw) * 3.0D;
        double backZ = -Math.cos(yaw) * 3.0D;
        double rightX = Math.cos(yaw) * lane * 1.4D;
        double rightZ = Math.sin(yaw) * lane * 1.4D;
        BlockPos target = BlockPos.containing(
                owner.getX() + backX + rightX,
                owner.getY(),
                owner.getZ() + backZ + rightZ);
        if (retargetCooldown <= 0) {
            navigation.navigateTo(villager, target, WALK_SPEED);
            retargetCooldown = RETARGET_TICKS;
            reportProgress();
        } else {
            retargetCooldown--;
        }
        villager.setSprinting(distanceSq > 18.0D * 18.0D);
        if (navigation.isAbandoned()) {
            terminal = true;
            finished = true;
            if (executionState.markBlockedIfCurrent(unitHandle, armyHandle, revision)) {
                telemetry.blocked();
            }
            BoundedNavigationDelegation.stop(navigation, villager);
        }
    }

    @Override public boolean isFinished() { return finished; }

    @Override
    public void stop(GoalContext context, StopReason reason) {
        if (context != null) {
            MillVillager villager = context.villager();
            villager.setSprinting(false);
            BoundedNavigationDelegation.stop(villager.getNavManager(), villager);
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

    @Override public TravelPhase getTravelPhase() { return TravelPhase.TRAVELLING; }
    @Override public Component getGoalLabel() { return LABEL; }
}
