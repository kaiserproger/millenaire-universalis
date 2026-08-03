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
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.integration.millenaire.FactionProjectionService;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireEntityBridge;

/**
 * Retained physical attack task: assemble, advance in formation and execute real Millenaire combat.
 *
 * <p>The task owns tactical movement because a forced task occupies Millenaire's scheduler. It does
 * not emulate damage: weapon selection, melee swings, arrows, cooldown, knockback, hurt and death
 * all pass through {@link MillVillager}'s public combat API.</p>
 */
final class StrategicAttackTask extends ProgressAwareTask implements StrategicRetainedTask {
    private static final ResourceLocation GOAL_ID = ResourceLocation.fromNamespaceAndPath(
            SarvarMillenaireArmies.MOD_ID, "strategic_attack");
    private static final Component LABEL = Component.translatable(
            "goal.millenaire_armies.strategic_attack");
    private static final double FORMATION_SPEED = 0.58D;
    private static final double LINE_SPEED = 0.63D;
    private static final double FLANK_SPEED = 0.72D;
    private static final double RESERVE_SPEED = 0.66D;
    private static final double RANGED_SPEED = 0.60D;
    private static final double OBJECTIVE_RADIUS_SQ = 28.0D * 28.0D;
    private static final double TARGET_RANGE_SQ = 38.0D * 38.0D;
    private static final double MELEE_RANGE_SQ = 4.0D;
    private static final double RANGED_MIN_RANGE_SQ = 5.5D * 5.5D;
    private static final double RANGED_MAX_RANGE_SQ = 20.0D * 20.0D;
    private static final int TARGET_SCAN_BUDGET = 40;
    private static final int TARGET_SCAN_COOLDOWN = 8;

    private final PackedUnitExecutionState executionState;
    private final OrderExecutionTelemetry telemetry;
    private final ArmyFormationCoordinator formations;
    private final ArmyBattleCoordinator battles;
    private final MillenaireEntityBridge entities;
    private final FactionProjectionService factions;
    private final ArmyFormationCoordinator.Plan formationPlan = new ArmyFormationCoordinator.Plan();
    private final ArmyBattleCoordinator.BattlePlan battlePlan = new ArmyBattleCoordinator.BattlePlan();
    private final MillenaireEntityBridge.CombatSearch search = new MillenaireEntityBridge.CombatSearch();
    private final int unitHandle;

    private int armyHandle;
    private long revision;
    private long packedObjective;
    private int formationCode;
    private int expectedUnits;
    private int sourceFaction;
    private boolean finished;
    private boolean terminal;
    private boolean cancelled;
    private int retargetCooldown;
    private int targetScanCooldown;

    StrategicAttackTask(
            PackedUnitExecutionState executionState,
            OrderExecutionTelemetry telemetry,
            ArmyFormationCoordinator formations,
            ArmyBattleCoordinator battles,
            MillenaireEntityBridge entities,
            FactionProjectionService factions,
            int unitHandle) {
        this.executionState = executionState;
        this.telemetry = telemetry;
        this.formations = formations;
        this.battles = battles;
        this.entities = entities;
        this.factions = factions;
        this.unitHandle = unitHandle;
    }

    void rearm(
            int armyHandle,
            long revision,
            long packedObjective,
            int formationCode,
            int expectedUnits,
            int sourceFaction) {
        this.armyHandle = armyHandle;
        this.revision = revision;
        this.packedObjective = packedObjective;
        this.formationCode = formationCode;
        this.expectedUnits = Math.max(1, expectedUnits);
        this.sourceFaction = sourceFaction;
        finished = false;
        terminal = false;
        cancelled = false;
        retargetCooldown = 0;
        targetScanCooldown = 0;
        search.reset();
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
        long gameTime = villager.level().getGameTime();

        formations.plan(
                armyHandle,
                unitHandle,
                revision,
                formationCode,
                expectedUnits,
                packedObjective,
                villager.getX(),
                villager.getY(),
                villager.getZ(),
                gameTime,
                formationPlan);

        if (formationPlan.canEngage()) {
            boolean allowAcquire = targetScanCooldown <= 0;
            if (allowAcquire) {
                targetScanCooldown = TARGET_SCAN_COOLDOWN;
            } else {
                targetScanCooldown--;
            }
            double objectiveX = PackedArmyEcs.unpackBlockX(packedObjective) + 0.5D;
            double objectiveZ = PackedArmyEcs.unpackBlockZ(packedObjective) + 0.5D;
            battles.plan(
                    armyHandle,
                    unitHandle,
                    revision,
                    sourceFaction,
                    expectedUnits,
                    villager,
                    formationPlan,
                    objectiveX,
                    objectiveZ,
                    OBJECTIVE_RADIUS_SQ,
                    TARGET_RANGE_SQ,
                    TARGET_SCAN_BUDGET,
                    allowAcquire,
                    entities,
                    factions,
                    search,
                    battlePlan);
            if (battlePlan.retreat()) {
                if (villager.getAttackTarget() instanceof MillVillager) {
                    villager.setAttackTarget(null);
                }
                villager.setSprinting(true);
                navigateTo(
                        navigation,
                        villager,
                        BlockPos.containing(
                                battlePlan.approachX(),
                                villager.getY(),
                                battlePlan.approachZ()),
                        FLANK_SPEED);
                if (navigation.isAbandoned()) {
                    recoverCombatNavigation(villager, navigation);
                }
                return;
            }
            if (battlePlan.target() != null) {
                tickPhysicalCombat(villager, navigation, battlePlan.target());
                if (navigation.isAbandoned()) {
                    recoverCombatNavigation(villager, navigation);
                }
                return;
            }
        }

        if (villager.getAttackTarget() instanceof MillVillager) {
            villager.setAttackTarget(null);
        }
        villager.setSprinting(false);
        navigateTo(
                navigation,
                villager,
                BlockPos.of(formationPlan.packedPosition()),
                FORMATION_SPEED);
        if (navigation.isAbandoned()) {
            terminal = true;
            finished = true;
            if (executionState.markBlockedIfCurrent(unitHandle, armyHandle, revision)) {
                telemetry.blocked();
            }
            BoundedNavigationDelegation.stop(navigation, villager);
        }
    }

    private void tickPhysicalCombat(
            MillVillager villager,
            VillagerNavDriver navigation,
            MillVillager target) {
        if (villager.getAttackTarget() != target) {
            villager.setAttackTarget(target);
        }
        villager.getLookControl().setLookAt(target, 30.0F, 30.0F);
        villager.ensureCombatWeaponEquipped();
        double distanceSq = villager.distanceToSqr(target);
        byte role = battlePlan.role();

        if (role == ArmyBattleCoordinator.ROLE_RANGED) {
            if (distanceSq <= MELEE_RANGE_SQ) {
                stopForAttack(villager, navigation);
                attack(villager, target);
                return;
            }
            if (distanceSq >= RANGED_MIN_RANGE_SQ
                    && distanceSq <= RANGED_MAX_RANGE_SQ
                    && villager.hasLineOfSight(target)) {
                stopForAttack(villager, navigation);
                attack(villager, target);
                return;
            }
            villager.setSprinting(distanceSq < RANGED_MIN_RANGE_SQ || distanceSq > 144.0D);
            navigateTo(navigation, villager, tacticalPosition(target), RANGED_SPEED);
            return;
        }

        if (distanceSq <= MELEE_RANGE_SQ) {
            stopForAttack(villager, navigation);
            attack(villager, target);
            return;
        }

        double speed = switch (role) {
            case ArmyBattleCoordinator.ROLE_LEFT_FLANK,
                    ArmyBattleCoordinator.ROLE_RIGHT_FLANK -> FLANK_SPEED;
            case ArmyBattleCoordinator.ROLE_RESERVE -> RESERVE_SPEED;
            default -> LINE_SPEED;
        };
        villager.setSprinting(distanceSq > 36.0D);
        navigateTo(navigation, villager, tacticalPosition(target), speed);
    }

    private void attack(MillVillager villager, MillVillager target) {
        if (villager.performAttack(target)) {
            reportProgress();
        }
    }

    private BlockPos tacticalPosition(MillVillager target) {
        return BlockPos.containing(
                battlePlan.approachX(),
                target.getY(),
                battlePlan.approachZ());
    }

    private void navigateTo(
            VillagerNavDriver navigation,
            MillVillager villager,
            BlockPos target,
            double speed) {
        int previousRetargetCooldown = retargetCooldown;
        retargetCooldown = BoundedNavigationDelegation.retarget(
                navigation, villager, target, speed, retargetCooldown);
        if (retargetCooldown > previousRetargetCooldown) {
            reportProgress();
        }
    }

    private void stopForAttack(MillVillager villager, VillagerNavDriver navigation) {
        BoundedNavigationDelegation.stop(navigation, villager);
        villager.setSprinting(false);
        retargetCooldown = 0;
    }

    private void recoverCombatNavigation(
            MillVillager villager,
            VillagerNavDriver navigation) {
        BoundedNavigationDelegation.stop(navigation, villager);
        villager.setAttackTarget(null);
        villager.setSprinting(false);
        battles.removeUnit(unitHandle);
        retargetCooldown = 0;
        targetScanCooldown = 0;
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public void stop(GoalContext context, StopReason reason) {
        battles.removeUnit(unitHandle);
        if (context != null) {
            MillVillager villager = context.villager();
            villager.setAttackTarget(null);
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

    @Override
    public TravelPhase getTravelPhase() {
        return terminal ? TravelPhase.AT_DESTINATION : TravelPhase.TRAVELLING;
    }

    @Override
    public Component getGoalLabel() {
        return LABEL;
    }
}
