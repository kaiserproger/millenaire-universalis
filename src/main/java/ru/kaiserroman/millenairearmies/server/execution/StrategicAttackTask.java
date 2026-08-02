package ru.kaiserroman.millenairearmies.server.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.entity.MillVillager;
import org.millenaire.entity.VillagerNavDriver;
import org.millenaire.goal.GoalContext;
import org.millenaire.goal.ProgressAwareTask;
import org.millenaire.goal.StopReason;
import org.millenaire.goal.TravelPhase;
import ru.kaiserroman.millenairearmies.SarvarMillenaireArmies;

/** Retained task that drives real Millenaire entities through real navigation and combat. */
final class StrategicAttackTask extends ProgressAwareTask implements StrategicRetainedTask {
    private static final ResourceLocation GOAL_ID = ResourceLocation.fromNamespaceAndPath(
            SarvarMillenaireArmies.MOD_ID, "strategic_attack");
    private static final Component LABEL = Component.translatable(
            "goal.millenaire_armies.strategic_attack");
    private static final double WALK_SPEED = 0.72D;
    private static final double ARRIVAL_DISTANCE_SQ = 9.0D;
    private static final int REPATH_TICKS = 10;

    private final PackedUnitExecutionState executionState;
    private final PhysicalBattleCoordinator battles;
    private final int unitHandle;
    private int armyHandle;
    private long revision;
    private long packedTarget;
    private BlockPos target;
    private boolean finished;
    private boolean terminal;
    private boolean cancelled;
    private int repathTicks;

    StrategicAttackTask(
            PackedUnitExecutionState executionState,
            PhysicalBattleCoordinator battles,
            int unitHandle) {
        this.executionState = executionState;
        this.battles = battles;
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
        repathTicks = 0;
    }

    @Override
    public int unitHandle() { return unitHandle; }

    @Override
    public int armyHandle() { return armyHandle; }

    @Override
    public long revision() { return revision; }

    @Override
    public boolean cancel() {
        if (finished) return false;
        cancelled = true;
        finished = true;
        return true;
    }

    @Override
    public ResourceLocation goalId() { return GOAL_ID; }

    @Override
    public void tick(GoalContext context) {
        MillVillager villager = context.villager();
        VillagerNavDriver navigation = villager.getNavManager();
        if (navigation == null || !(villager.level() instanceof ServerLevel level)) {
            terminal = true;
            finished = true;
            executionState.markBlockedIfCurrent(unitHandle, armyHandle, revision);
            return;
        }

        MillVillager enemy = battles.acquireEnemy(villager, armyHandle, unitHandle, packedTarget);
        if (enemy != null) {
            if (villager.getAttackTarget() != enemy) {
                villager.setAttackTarget(enemy);
            }
            if (enemy.getAttackTarget() == null) {
                enemy.setAttackTarget(villager);
                enemy.ensureCombatWeaponEquipped();
            }
            villager.ensureCombatWeaponEquipped();
            if (++repathTicks >= REPATH_TICKS || navigation.getDestination() == null) {
                repathTicks = 0;
                navigation.navigateTo(villager, enemy.blockPosition(), WALK_SPEED);
                reportProgress();
            }
            if (villager.performAttack(enemy)) {
                battles.attackPerformed(armyHandle, unitHandle, enemy);
                reportProgress();
            }
            return;
        }

        if (villager.getAttackTarget() != null) villager.setAttackTarget(null);
        PhysicalBattleCoordinator.CaptureResult result =
                battles.tryCapture(villager, armyHandle, packedTarget, level);
        if (result == PhysicalBattleCoordinator.CaptureResult.CAPTURED
                || result == PhysicalBattleCoordinator.CaptureResult.FIELD_CLEARED) {
            terminal = true;
            finished = true;
            executionState.markArrivedIfCurrent(unitHandle, armyHandle, revision);
            navigation.stop(villager);
            return;
        }
        if (result == PhysicalBattleCoordinator.CaptureResult.BLOCKED) {
            terminal = true;
            finished = true;
            executionState.markBlockedIfCurrent(unitHandle, armyHandle, revision);
            navigation.stop(villager);
            return;
        }

        double dx = villager.getX() - (target.getX() + 0.5D);
        double dy = villager.getY() - target.getY();
        double dz = villager.getZ() - (target.getZ() + 0.5D);
        if (dx * dx + dy * dy + dz * dz > ARRIVAL_DISTANCE_SQ
                && (++repathTicks >= REPATH_TICKS || navigation.getDestination() == null)) {
            repathTicks = 0;
            navigation.navigateTo(villager, target, WALK_SPEED);
            reportProgress();
        }
        if (navigation.isAbandoned()) {
            executionState.markRetry(unitHandle, armyHandle, revision);
            navigation.stop(villager);
            repathTicks = REPATH_TICKS;
        }
    }

    @Override
    public boolean isFinished() { return finished; }

    @Override
    public void stop(GoalContext context, StopReason reason) {
        if (context != null) {
            MillVillager villager = context.villager();
            villager.setAttackTarget(null);
            if (villager.getNavManager() != null) villager.getNavManager().stop(villager);
        }
        if (!terminal && !cancelled) {
            if (reason == StopReason.IMPOSSIBLE) {
                executionState.markBlockedIfCurrent(unitHandle, armyHandle, revision);
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
    public Component getGoalLabel() { return LABEL; }
}
