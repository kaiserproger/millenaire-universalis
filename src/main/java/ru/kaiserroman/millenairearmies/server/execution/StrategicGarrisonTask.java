package ru.kaiserroman.millenairearmies.server.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import org.millenaire.entity.MillVillager;
import org.millenaire.entity.VillagerNavDriver;
import org.millenaire.goal.GoalContext;
import org.millenaire.goal.ProgressAwareTask;
import org.millenaire.goal.StopReason;
import org.millenaire.goal.TravelPhase;
import ru.kaiserroman.millenairearmies.UniversalisIds;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.integration.millenaire.FactionProjectionService;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireEntityBridge;
import ru.kaiserroman.millenairearmies.server.supply.ArmySupplyAccess;

/**
 * Continuous defensive task around a settlement muster post.
 *
 * <p>It never loads a chunk or teleports a resident. A unit outside the configured radius returns
 * through Millenaire navigation. Inside the radius it holds its formation slot and may physically
 * fight only hostile Millenaire residents that are also inside the same defensive area.</p>
 */
final class StrategicGarrisonTask extends ProgressAwareTask implements StrategicRetainedTask {
    private static final ResourceLocation GOAL_ID = ResourceLocation.fromNamespaceAndPath(
            UniversalisIds.MOD_ID, "garrison_guard");
    private static final Component LABEL = Component.translatable(
            "goal.millenaire_armies.garrison_guard");
    private static final double MELEE_RANGE_SQ = 4.0D;
    private static final double RANGED_MIN_RANGE_SQ = 5.5D * 5.5D;
    private static final double RANGED_MAX_RANGE_SQ = 20.0D * 20.0D;
    private static final int TARGET_SCAN_BUDGET = 32;
    private static final int TARGET_SCAN_COOLDOWN = 10;

    private final PackedUnitExecutionState executionState;
    private final OrderExecutionTelemetry telemetry;
    private final ArmyFormationCoordinator formations;
    private final ArmyBattleCoordinator battles;
    private final MillenaireEntityBridge entities;
    private final FactionProjectionService factions;
    private final ArmySupplyAccess supplies;
    private final ArmyFormationCoordinator.Plan formationPlan = new ArmyFormationCoordinator.Plan();
    private final ArmyBattleCoordinator.BattlePlan battlePlan = new ArmyBattleCoordinator.BattlePlan();
    private final MillenaireEntityBridge.CombatSearch search = new MillenaireEntityBridge.CombatSearch();
    private final int unitHandle;

    private int armyHandle;
    private long revision;
    private long packedMuster;
    private int guardRadius;
    private int formationCode;
    private int expectedUnits;
    private int sourceFaction;
    private int supplyPercent;
    private int readinessPercent;
    private int moralePercent;
    private boolean shieldWall;
    private boolean fireAtWill;
    private boolean finished;
    private boolean cancelled;
    private boolean terminal;
    private boolean insideRadius;
    private int retargetCooldown;
    private int targetScanCooldown;

    StrategicGarrisonTask(
            PackedUnitExecutionState executionState,
            OrderExecutionTelemetry telemetry,
            ArmyFormationCoordinator formations,
            ArmyBattleCoordinator battles,
            MillenaireEntityBridge entities,
            FactionProjectionService factions,
            ArmySupplyAccess supplies,
            int unitHandle) {
        this.executionState = executionState;
        this.telemetry = telemetry;
        this.formations = formations;
        this.battles = battles;
        this.entities = entities;
        this.factions = factions;
        this.supplies = supplies;
        this.unitHandle = unitHandle;
    }

    void rearm(
            int armyHandle,
            long revision,
            long packedMuster,
            int guardRadius,
            int formationCode,
            int expectedUnits,
            int sourceFaction,
            int supplyPercent,
            int readinessPercent,
            int moralePercent,
            boolean shieldWall,
            boolean fireAtWill) {
        this.armyHandle = armyHandle;
        this.revision = revision;
        this.packedMuster = packedMuster;
        this.guardRadius = Math.max(1, guardRadius);
        this.formationCode = formationCode;
        this.expectedUnits = Math.max(1, expectedUnits);
        this.sourceFaction = sourceFaction;
        this.supplyPercent = clamp(supplyPercent);
        this.readinessPercent = clamp(readinessPercent);
        this.moralePercent = clamp(moralePercent);
        this.shieldWall = shieldWall;
        this.fireAtWill = fireAtWill;
        finished = false;
        cancelled = false;
        terminal = false;
        insideRadius = false;
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
        if (navigation == null) {
            terminal = true;
            finished = true;
            if (executionState.markBlockedIfCurrent(unitHandle, armyHandle, revision)) {
                telemetry.blocked();
            }
            return;
        }
        maintainShield(villager, shieldWall);
        double musterX = PackedArmyEcs.unpackBlockX(packedMuster) + 0.5D;
        double musterZ = PackedArmyEcs.unpackBlockZ(packedMuster) + 0.5D;
        insideRadius = !GarrisonExecutionPolicy.outsideRadius(
                villager.getX(), villager.getZ(), musterX, musterZ, guardRadius);
        if (!insideRadius || GarrisonExecutionPolicy.mustRegroup(readinessPercent, moralePercent)) {
            clearForeignTarget(villager);
            navigateToFormation(villager, navigation, context.villager().level().getGameTime());
            return;
        }

        long gameTime = villager.level().getGameTime();
        formations.plan(
                armyHandle,
                unitHandle,
                revision,
                formationCode,
                expectedUnits,
                packedMuster,
                villager.getX(),
                villager.getY(),
                villager.getZ(),
                gameTime,
                formationPlan);

        if (GarrisonExecutionPolicy.mayAcquireTarget(readinessPercent, moralePercent)
                && formationPlan.canEngage()) {
            boolean allowAcquire = targetScanCooldown <= 0;
            if (allowAcquire) {
                targetScanCooldown = TARGET_SCAN_COOLDOWN;
            } else {
                targetScanCooldown--;
            }
            double radiusSq = (double) guardRadius * guardRadius;
            battles.plan(
                    armyHandle,
                    unitHandle,
                    revision,
                    sourceFaction,
                    expectedUnits,
                    villager,
                    formationPlan,
                    musterX,
                    musterZ,
                    radiusSq,
                    Math.min(radiusSq, 38.0D * 38.0D),
                    TARGET_SCAN_BUDGET,
                    allowAcquire,
                    entities,
                    factions,
                    search,
                    battlePlan);
            MillVillager target = battlePlan.target();
            if (target != null && GarrisonExecutionPolicy.targetInsideRadius(
                    target.getX(), target.getZ(), musterX, musterZ, guardRadius)) {
                tickPhysicalCombat(villager, navigation, target);
                if (navigation.isAbandoned()) {
                    recoverNavigation(villager, navigation);
                }
                return;
            }
        }

        clearForeignTarget(villager);
        navigateToFormation(villager, navigation, gameTime);
    }

    private void navigateToFormation(MillVillager villager, VillagerNavDriver navigation, long gameTime) {
        formations.plan(
                armyHandle,
                unitHandle,
                revision,
                formationCode,
                expectedUnits,
                packedMuster,
                villager.getX(),
                villager.getY(),
                villager.getZ(),
                gameTime,
                formationPlan);
        villager.setSprinting(false);
        BlockPos slot = BlockPos.of(formationPlan.packedPosition());
        double musterX = PackedArmyEcs.unpackBlockX(packedMuster) + 0.5D;
        double musterZ = PackedArmyEcs.unpackBlockZ(packedMuster) + 0.5D;
        if (GarrisonExecutionPolicy.outsideRadius(
                slot.getX() + 0.5D, slot.getZ() + 0.5D, musterX, musterZ, guardRadius)) {
            slot = BlockPos.of(packedMuster);
        }
        navigateTo(
                navigation,
                villager,
                slot,
                GarrisonExecutionPolicy.movementSpeed(readinessPercent, supplyPercent));
        if (navigation.isAbandoned()) {
            recoverNavigation(villager, navigation);
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
        boolean rangedPolicy = fireAtWill
                && villager.getMainHandItem().getItem() instanceof ProjectileWeaponItem;
        if (rangedPolicy) {
            maintainShield(villager, false);
            if (distanceSq <= MELEE_RANGE_SQ
                    || distanceSq >= RANGED_MIN_RANGE_SQ
                            && distanceSq <= RANGED_MAX_RANGE_SQ
                            && villager.hasLineOfSight(target)) {
                stopForAttack(villager, navigation);
                attack(villager, target, true);
                return;
            }
            villager.setSprinting(false);
            navigateTo(navigation, villager, tacticalPosition(target), 0.56D);
            return;
        }
        if (distanceSq <= MELEE_RANGE_SQ) {
            stopForAttack(villager, navigation);
            attack(villager, target, false);
            return;
        }
        maintainShield(villager, shieldWall);
        villager.setSprinting(!shieldWall && distanceSq > 36.0D && readinessPercent >= 60);
        navigateTo(navigation, villager, tacticalPosition(target), shieldWall ? 0.45D : 0.62D);
    }

    private void attack(MillVillager villager, MillVillager target, boolean ranged) {
        if (ranged && !supplies.hasArrow(armyHandle, villager)) {
            return;
        }
        if (villager.performAttack(target)) {
            if (ranged && !supplies.consumeArrow(armyHandle, villager)) {
                return;
            }
            reportProgress();
        }
    }

    private BlockPos tacticalPosition(MillVillager target) {
        double musterX = PackedArmyEcs.unpackBlockX(packedMuster) + 0.5D;
        double musterZ = PackedArmyEcs.unpackBlockZ(packedMuster) + 0.5D;
        if (GarrisonExecutionPolicy.targetInsideRadius(
                battlePlan.approachX(), battlePlan.approachZ(), musterX, musterZ, guardRadius)) {
            return BlockPos.containing(battlePlan.approachX(), target.getY(), battlePlan.approachZ());
        }
        return target.blockPosition();
    }

    private void navigateTo(
            VillagerNavDriver navigation,
            MillVillager villager,
            BlockPos target,
            double speed) {
        int previous = retargetCooldown;
        retargetCooldown = BoundedNavigationDelegation.retarget(
                navigation, villager, target, speed, retargetCooldown);
        if (retargetCooldown > previous) {
            reportProgress();
        }
    }

    private void stopForAttack(MillVillager villager, VillagerNavDriver navigation) {
        BoundedNavigationDelegation.stop(navigation, villager);
        maintainShield(villager, false);
        villager.setSprinting(false);
        retargetCooldown = 0;
    }

    private static void maintainShield(MillVillager villager, boolean raised) {
        boolean hasShield = villager.getOffhandItem().getItem() instanceof ShieldItem;
        if (raised && hasShield) {
            if (!villager.isUsingItem()) {
                villager.startUsingItem(InteractionHand.OFF_HAND);
            }
        } else if (villager.isUsingItem()) {
            villager.stopUsingItem();
        }
    }

    private void clearForeignTarget(MillVillager villager) {
        if (villager.getAttackTarget() instanceof MillVillager) {
            villager.setAttackTarget(null);
        }
        battles.removeUnit(unitHandle);
    }

    private void recoverNavigation(MillVillager villager, VillagerNavDriver navigation) {
        BoundedNavigationDelegation.stop(navigation, villager);
        clearForeignTarget(villager);
        maintainShield(villager, false);
        villager.setSprinting(false);
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
            maintainShield(villager, false);
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
        return insideRadius ? TravelPhase.AT_DESTINATION : TravelPhase.TRAVELLING;
    }

    @Override
    public Component getGoalLabel() {
        return LABEL;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
