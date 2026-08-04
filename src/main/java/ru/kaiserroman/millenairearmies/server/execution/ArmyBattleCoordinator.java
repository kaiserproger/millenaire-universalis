package ru.kaiserroman.millenairearmies.server.execution;

import java.util.Arrays;
import net.minecraft.world.entity.LivingEntity;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillagerType;
import org.millenaire.entity.MillVillager;
import org.millenaire.village.Village;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.integration.millenaire.FactionProjectionService;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireEntityBridge;
import ru.kaiserroman.millenairearmies.persistence.PackedUnitMembership;

/**
 * Server-thread tactical director shared by physical attack tasks.
 *
 * <p>The director does not deal damage and does not replace entity navigation. It keeps stable
 * target assignments, limits dog-piling, derives front/flank/reserve/ranged roles from formation
 * slots and gives every real {@link MillVillager} an approach point around its assigned opponent.
 * The retained task then uses Millenaire's public combat API for visible pursuit, swings and arrows.</p>
 */
final class ArmyBattleCoordinator implements MillenaireEntityBridge.CombatTargetScorer {
    static final byte ROLE_LINE = 0;
    static final byte ROLE_LEFT_FLANK = 1;
    static final byte ROLE_RIGHT_FLANK = 2;
    static final byte ROLE_RESERVE = 3;
    static final byte ROLE_RANGED = 4;

    static final byte PHASE_APPROACH = 0;
    static final byte PHASE_CONTACT = 1;
    static final byte PHASE_ENGAGED = 2;
    static final byte PHASE_PRESSURE = 3;
    static final byte PHASE_ROUT = 4;

    private static final int MIN_GROWTH = 32;
    private static final double TARGET_LEASH_SQ = 42.0D * 42.0D;
    private static final double FLANK_THRESHOLD = 4.0D;
    private static final double RESERVE_DEPTH = -4.0D;
    private static final double MELEE_STANDOFF = 1.35D;
    private static final double FLANK_STANDOFF = 1.65D;
    private static final double RANGED_STANDOFF = 13.0D;
    private static final double ROUT_DISTANCE = 15.0D;

    private int armySize;
    private int[] armyHandles = new int[0];
    private long[] armyRevisions = new long[0];
    private int[] armyAssignedCounts = new int[0];
    private int[] armySlotToRow = new int[0];

    private int unitSize;
    private int[] unitHandles = new int[0];
    private int[] unitArmies = new int[0];
    private long[] unitRevisions = new long[0];
    private byte[] unitRoles = new byte[0];
    private MillVillager[] unitTargets = new MillVillager[0];
    private boolean[] unitActive = new boolean[0];
    private int[] unitSlotToRow = new int[0];

    private int pressureSize;
    private int[] pressureArmies = new int[0];
    private long[] pressureTargetMost = new long[0];
    private long[] pressureTargetLeast = new long[0];
    private int[] pressureCounts = new int[0];

    private PackedArmyEcs ecs;
    private PackedUnitMembership memberships;
    private ArmyHostilityPolicy hostilityPolicy = ArmyHostilityPolicy.DENY_ALL;

    // Mutable scoring context. The coordinator is server-thread only and scans synchronously.
    private int scoringArmy;
    private byte scoringRole;
    private double scoringDesiredRight;
    private double scoringObjectiveX;
    private double scoringObjectiveZ;
    private double scoringForwardX;
    private double scoringForwardZ;
    private MillVillager scoringSource;

    void start(PackedArmyEcs persistedEcs, PackedUnitMembership persistedMemberships) {
        ecs = persistedEcs;
        memberships = persistedMemberships;
    }

    void hostilityPolicy(ArmyHostilityPolicy replacement) {
        hostilityPolicy = java.util.Objects.requireNonNull(replacement, "replacement");
    }

    BattlePlan plan(
            int armyHandle,
            int unitHandle,
            long revision,
            int sourceFaction,
            int expectedUnits,
            MillVillager source,
            ArmyFormationCoordinator.Plan formation,
            double objectiveX,
            double objectiveZ,
            double objectiveRadiusSq,
            double sourceRangeSq,
            int scanBudget,
            boolean allowAcquire,
            MillenaireEntityBridge entities,
            FactionProjectionService factions,
            MillenaireEntityBridge.CombatSearch search,
            BattlePlan out) {
        if (armyHandle == 0 || unitHandle == 0 || revision <= 0L
                || source == null || formation == null || entities == null
                || factions == null || search == null || out == null) {
            throw new IllegalArgumentException("Battle plan inputs must be complete");
        }

        int armyRow = activateArmy(armyHandle, revision);
        int unitRow = activateUnit(
                armyHandle,
                unitHandle,
                revision,
                roleFor(source, formation, expectedUnits));
        int morale = morale(
                formation.cohesionPercent(),
                formation.activeUnits(),
                expectedUnits,
                source.getHealth(),
                source.getMaxHealth());
        byte phase = phase(
                morale,
                formation.canEngage(),
                armyAssignedCounts[armyRow],
                formation.activeUnits());
        MillVillager target = unitTargets[unitRow];
        if (!validTarget(source, target, sourceFaction, objectiveX, objectiveZ, entities, factions)) {
            assignTarget(unitRow, null);
            target = null;
        }
        if (target == null && source.getAttackTarget() instanceof MillVillager immediate
                && validTarget(source, immediate, sourceFaction, objectiveX, objectiveZ, entities, factions)) {
            target = immediate;
            assignTarget(unitRow, target);
        }

        out.morale = morale;
        out.phase = phase;
        out.retreat = phase == PHASE_ROUT;
        out.targetArmy = PackedArmyEcs.NO_ARMY;
        out.targetUnit = 0;
        out.targetFaction = -1;
        if (out.retreat) {
            assignTarget(unitRow, null);
            out.target = null;
            out.role = unitRoles[unitRow];
            out.holdFormation = false;
            double stagger = ((formation.formationSlot() & 3) - 1.5D) * 1.4D;
            double rightX = -formation.forwardZ();
            double rightZ = formation.forwardX();
            out.approachX = formation.anchorX()
                    - formation.forwardX() * ROUT_DISTANCE
                    + rightX * stagger;
            out.approachZ = formation.anchorZ()
                    - formation.forwardZ() * ROUT_DISTANCE
                    + rightZ * stagger;
            return out;
        }

        boolean reserveHeld = target == null
                && unitRoles[unitRow] == ROLE_RESERVE
                && formation.cohesionPercent() >= 45
                && phase != PHASE_PRESSURE
                && armyAssignedCounts[armyRow] >= Math.max(2, formation.activeUnits() * 55 / 100);

        if (target == null && allowAcquire && !reserveHeld) {
            scoringArmy = armyHandle;
            scoringRole = unitRoles[unitRow];
            scoringDesiredRight = formation.rightOffset();
            scoringObjectiveX = objectiveX;
            scoringObjectiveZ = objectiveZ;
            scoringForwardX = formation.forwardX();
            scoringForwardZ = formation.forwardZ();
            scoringSource = source;
            target = entities.findHostileTarget(
                    source,
                    sourceFaction,
                    factions,
                    objectiveX,
                    objectiveZ,
                    objectiveRadiusSq,
                    sourceRangeSq,
                    scanBudget,
                    search,
                    this);
            assignTarget(unitRow, target);
        }

        out.target = target;
        out.role = unitRoles[unitRow];
        out.holdFormation = target == null;
        if (target == null) {
            out.approachX = PackedArmyEcs.unpackBlockX(formation.packedPosition()) + 0.5D;
            out.approachZ = PackedArmyEcs.unpackBlockZ(formation.packedPosition()) + 0.5D;
            return out;
        }

        out.targetUnit = unitFor(target);
        out.targetArmy = out.targetUnit == 0 || !ecs.isUnitAlive(out.targetUnit)
                ? PackedArmyEcs.NO_ARMY
                : ecs.unitArmy(out.targetUnit);
        out.targetFaction = out.targetArmy == PackedArmyEcs.NO_ARMY || !ecs.isArmyAlive(out.targetArmy)
                ? -1
                : ecs.armyFaction(out.targetArmy);

        double forwardX = formation.forwardX();
        double forwardZ = formation.forwardZ();
        double rightX = -forwardZ;
        double rightZ = forwardX;
        double lateral;
        double standoff;
        switch (out.role) {
            case ROLE_LEFT_FLANK -> {
                lateral = -FLANK_STANDOFF;
                standoff = 0.35D;
            }
            case ROLE_RIGHT_FLANK -> {
                lateral = FLANK_STANDOFF;
                standoff = 0.35D;
            }
            case ROLE_RANGED -> {
                lateral = clamp(formation.rightOffset() * 0.22D, -4.5D, 4.5D);
                standoff = RANGED_STANDOFF;
            }
            default -> {
                lateral = clamp(formation.rightOffset() * 0.12D, -0.9D, 0.9D);
                standoff = MELEE_STANDOFF;
            }
        }
        out.approachX = target.getX() - forwardX * standoff + rightX * lateral;
        out.approachZ = target.getZ() - forwardZ * standoff + rightZ * lateral;
        return out;
    }

    @Override
    public double score(
            MillVillager candidate,
            int targetFaction,
            double sourceDistanceSq,
            double objectiveDistanceSq) {
        int candidateArmy = armyFor(candidate);
        boolean targetArmyAlive = candidateArmy != PackedArmyEcs.NO_ARMY && ecs.isArmyAlive(candidateArmy);
        boolean hostile = targetArmyAlive && hostilityPolicy.hostile(
                scoringArmy,
                candidateArmy,
                ecs.armyFaction(scoringArmy),
                ecs.armyFaction(candidateArmy));
        if (!BattleTargetPolicy.valid(
                scoringArmy, candidateArmy, targetArmyAlive, hostile)) {
            return Double.POSITIVE_INFINITY;
        }
        int pressure = pressure(scoringArmy, candidate);
        int maximumPressure = maximumPressure(candidate, scoringRole == ROLE_RANGED);
        if (pressure >= maximumPressure) {
            return Double.POSITIVE_INFINITY;
        }

        double distance = Math.sqrt(sourceDistanceSq);
        double objectiveDx = candidate.getX() - scoringObjectiveX;
        double objectiveDz = candidate.getZ() - scoringObjectiveZ;
        double rightX = -scoringForwardZ;
        double rightZ = scoringForwardX;
        double targetRight = objectiveDx * rightX + objectiveDz * rightZ;
        double score = distance * 0.55D + objectiveDistanceSq * 0.004D + pressure * 16.0D;

        switch (scoringRole) {
            case ROLE_LEFT_FLANK -> {
                if (targetRight > 1.0D) score += 28.0D;
                score -= Math.min(11.0D, Math.abs(targetRight) * 0.65D);
            }
            case ROLE_RIGHT_FLANK -> {
                if (targetRight < -1.0D) score += 28.0D;
                score -= Math.min(11.0D, Math.abs(targetRight) * 0.65D);
            }
            case ROLE_RANGED -> {
                score += Math.abs(distance - RANGED_STANDOFF) * 1.8D;
                if (distance < 5.0D) score += 36.0D;
            }
            case ROLE_RESERVE -> score += distance * 0.35D;
            default -> score += Math.abs(targetRight - scoringDesiredRight) * 0.38D;
        }

        LivingEntity enemyTarget = candidate.getAttackTarget();
        if (enemyTarget == scoringSource) {
            score -= 20.0D;
        } else if (enemyTarget instanceof MillVillager) {
            score -= 5.0D;
        }
        float maximumHealth = candidate.getMaxHealth();
        if (maximumHealth > 0.0F) {
            score -= (1.0D - candidate.getHealth() / maximumHealth) * 4.0D;
        }
        return score;
    }

    boolean removeUnit(int unitHandle) {
        int row = unitRow(unitHandle);
        if (row < 0 || !unitActive[row]) return false;
        assignTarget(row, null);
        unitActive[row] = false;
        return true;
    }

    void clear() {
        Arrays.fill(armyHandles, 0, armySize, 0);
        Arrays.fill(armyRevisions, 0, armySize, 0L);
        Arrays.fill(armyAssignedCounts, 0, armySize, 0);
        Arrays.fill(armySlotToRow, 0);
        Arrays.fill(unitHandles, 0, unitSize, 0);
        Arrays.fill(unitActive, 0, unitSize, false);
        Arrays.fill(unitTargets, 0, unitSize, null);
        Arrays.fill(unitSlotToRow, 0);
        Arrays.fill(pressureArmies, 0, pressureSize, 0);
        Arrays.fill(pressureTargetMost, 0, pressureSize, 0L);
        Arrays.fill(pressureTargetLeast, 0, pressureSize, 0L);
        Arrays.fill(pressureCounts, 0, pressureSize, 0);
        armySize = 0;
        unitSize = 0;
        pressureSize = 0;
        ecs = null;
        memberships = null;
        hostilityPolicy = ArmyHostilityPolicy.DENY_ALL;
    }

    static byte roleFor(
            boolean archer,
            double rightOffset,
            double forwardOffset,
            int expectedUnits) {
        if (archer) return ROLE_RANGED;
        if (expectedUnits >= 10 && forwardOffset <= RESERVE_DEPTH) return ROLE_RESERVE;
        if (rightOffset <= -FLANK_THRESHOLD) return ROLE_LEFT_FLANK;
        if (rightOffset >= FLANK_THRESHOLD) return ROLE_RIGHT_FLANK;
        return ROLE_LINE;
    }

    static int morale(
            int cohesionPercent,
            int activeUnits,
            int expectedUnits,
            float health,
            float maximumHealth) {
        int boundedExpected = Math.max(1, expectedUnits);
        int strengthPercent = Math.max(0, Math.min(100, activeUnits * 100 / boundedExpected));
        int healthPercent = maximumHealth <= 0.0F
                ? 0
                : Math.max(0, Math.min(100, Math.round(health * 100.0F / maximumHealth)));
        int morale = cohesionPercent * 45 / 100
                + strengthPercent * 35 / 100
                + healthPercent * 20 / 100;
        return Math.max(0, Math.min(100, morale));
    }

    static byte phase(int morale, boolean canEngage, int assignedUnits, int activeUnits) {
        if (morale < 22) return PHASE_ROUT;
        if (!canEngage) return PHASE_APPROACH;
        if (activeUnits <= 0 || assignedUnits <= 0) return PHASE_CONTACT;
        int engagementPercent = assignedUnits * 100 / Math.max(1, activeUnits);
        if (morale < 48 || engagementPercent >= 72) return PHASE_PRESSURE;
        return PHASE_ENGAGED;
    }

    private static byte roleFor(
            MillVillager villager,
            ArmyFormationCoordinator.Plan formation,
            int expectedUnits) {
        VillagerType type = villager.getVillagerTypeId() == null
                ? null
                : ModCultures.getVillagerType(villager.getVillagerTypeId());
        return roleFor(
                type != null && type.isArcher(),
                formation.rightOffset(),
                formation.forwardOffset(),
                expectedUnits);
    }

    private int unitFor(MillVillager villager) {
        if (villager == null || villager.getUUID() == null || ecs == null || memberships == null) {
            return 0;
        }
        return memberships.unitHandleForUuid(
                villager.getUUID().getMostSignificantBits(),
                villager.getUUID().getLeastSignificantBits());
    }

    private int armyFor(MillVillager villager) {
        int unit = unitFor(villager);
        return unit != 0 && ecs.isUnitAlive(unit) ? ecs.unitArmy(unit) : PackedArmyEcs.NO_ARMY;
    }

    private boolean validTarget(
            MillVillager source,
            MillVillager target,
            int sourceFaction,
            double objectiveX,
            double objectiveZ,
            MillenaireEntityBridge entities,
            FactionProjectionService factions) {
        if (target == null
                || target.isRemoved()
                || !target.isAlive()
                || target.level() != source.level()
                || source.distanceToSqr(target) > TARGET_LEASH_SQ) {
            return false;
        }
        double objectiveDx = target.getX() - objectiveX;
        double objectiveDz = target.getZ() - objectiveZ;
        if (objectiveDx * objectiveDx + objectiveDz * objectiveDz > TARGET_LEASH_SQ) {
            return false;
        }
        int targetArmy = armyFor(target);
        int sourceArmy = armyFor(source);
        boolean targetArmyAlive = targetArmy != PackedArmyEcs.NO_ARMY && ecs.isArmyAlive(targetArmy);
        boolean hostile = targetArmyAlive && hostilityPolicy.hostile(
                sourceArmy,
                targetArmy,
                sourceFaction,
                ecs.armyFaction(targetArmy));
        return BattleTargetPolicy.valid(sourceArmy, targetArmy, targetArmyAlive, hostile);
    }

    private int activateArmy(int armyHandle, long revision) {
        int slot = PackedArmyEcs.handleSlotIndex(armyHandle);
        ensureArmySlotCapacity(slot + 1);
        int row = armySlotToRow[slot] - 1;
        if (row < 0) {
            ensureArmyCapacity(armySize + 1);
            row = armySize++;
            armySlotToRow[slot] = row + 1;
        }
        if (armyHandles[row] != armyHandle || armyRevisions[row] != revision) {
            armyHandles[row] = armyHandle;
            armyRevisions[row] = revision;
            armyAssignedCounts[row] = 0;
        }
        return row;
    }

    private int activateUnit(int armyHandle, int unitHandle, long revision, byte role) {
        int slot = PackedArmyEcs.handleSlotIndex(unitHandle);
        ensureUnitSlotCapacity(slot + 1);
        int row = unitSlotToRow[slot] - 1;
        if (row < 0) {
            ensureUnitCapacity(unitSize + 1);
            row = unitSize++;
            unitSlotToRow[slot] = row + 1;
        }
        if (unitActive[row]
                && unitHandles[row] == unitHandle
                && unitArmies[row] == armyHandle
                && unitRevisions[row] == revision) {
            unitRoles[row] = role;
            return row;
        }
        if (unitActive[row]) assignTarget(row, null);
        unitHandles[row] = unitHandle;
        unitArmies[row] = armyHandle;
        unitRevisions[row] = revision;
        unitRoles[row] = role;
        unitTargets[row] = null;
        unitActive[row] = true;
        return row;
    }

    private void assignTarget(int unitRow, MillVillager replacement) {
        MillVillager previous = unitTargets[unitRow];
        if (previous == replacement) return;
        int armyRow = armyRow(unitArmies[unitRow]);
        if (previous != null) {
            changePressure(unitArmies[unitRow], previous, -1);
            if (armyRow >= 0 && armyAssignedCounts[armyRow] > 0) armyAssignedCounts[armyRow]--;
        }
        unitTargets[unitRow] = replacement;
        if (replacement != null) {
            changePressure(unitArmies[unitRow], replacement, 1);
            if (armyRow >= 0 && armyAssignedCounts[armyRow] != Integer.MAX_VALUE) {
                armyAssignedCounts[armyRow]++;
            }
        }
    }

    int assignedUnits(int armyHandle) {
        int assigned = 0;
        for (int row = 0; row < unitSize; row++) {
            if (unitActive[row] && unitArmies[row] == armyHandle && unitTargets[row] != null) {
                assigned++;
            }
        }
        return assigned;
    }

    private int pressure(int armyHandle, MillVillager target) {
        if (target == null || target.getUUID() == null) return 0;
        int row = pressureRow(
                armyHandle,
                target.getUUID().getMostSignificantBits(),
                target.getUUID().getLeastSignificantBits());
        return row < 0 ? 0 : pressureCounts[row];
    }

    private void changePressure(int armyHandle, MillVillager target, int delta) {
        if (target == null || target.getUUID() == null || delta == 0) return;
        long most = target.getUUID().getMostSignificantBits();
        long least = target.getUUID().getLeastSignificantBits();
        int row = pressureRow(armyHandle, most, least);
        if (row < 0) {
            if (delta < 0) return;
            ensurePressureCapacity(pressureSize + 1);
            row = pressureSize++;
            pressureArmies[row] = armyHandle;
            pressureTargetMost[row] = most;
            pressureTargetLeast[row] = least;
        }
        pressureCounts[row] += delta;
        if (pressureCounts[row] <= 0) removePressureRow(row);
    }

    private int pressureRow(int armyHandle, long most, long least) {
        for (int row = 0; row < pressureSize; row++) {
            if (pressureArmies[row] == armyHandle
                    && pressureTargetMost[row] == most
                    && pressureTargetLeast[row] == least) {
                return row;
            }
        }
        return -1;
    }

    private void removePressureRow(int row) {
        int last = --pressureSize;
        if (row != last) {
            pressureArmies[row] = pressureArmies[last];
            pressureTargetMost[row] = pressureTargetMost[last];
            pressureTargetLeast[row] = pressureTargetLeast[last];
            pressureCounts[row] = pressureCounts[last];
        }
        pressureArmies[last] = 0;
        pressureTargetMost[last] = 0L;
        pressureTargetLeast[last] = 0L;
        pressureCounts[last] = 0;
    }

    private int unitRow(int unitHandle) {
        if (unitHandle == 0) return -1;
        int slot = PackedArmyEcs.handleSlotIndex(unitHandle);
        if (slot >= unitSlotToRow.length) return -1;
        int row = unitSlotToRow[slot] - 1;
        return row >= 0 && unitHandles[row] == unitHandle ? row : -1;
    }

    private int armyRow(int armyHandle) {
        if (armyHandle == 0) return -1;
        int slot = PackedArmyEcs.handleSlotIndex(armyHandle);
        if (slot >= armySlotToRow.length) return -1;
        int row = armySlotToRow[slot] - 1;
        return row >= 0 && armyHandles[row] == armyHandle ? row : -1;
    }

    private static int maximumPressure(MillVillager target, boolean ranged) {
        int base = Math.max(1, Math.min(4, (int) Math.ceil(target.getMaxHealth() / 20.0F)));
        return ranged ? base + 2 : base;
    }

    private void ensureArmyCapacity(int required) {
        if (required <= armyHandles.length) return;
        int capacity = grow(armyHandles.length, required);
        armyHandles = Arrays.copyOf(armyHandles, capacity);
        armyRevisions = Arrays.copyOf(armyRevisions, capacity);
        armyAssignedCounts = Arrays.copyOf(armyAssignedCounts, capacity);
    }

    private void ensureArmySlotCapacity(int required) {
        if (required <= armySlotToRow.length) return;
        armySlotToRow = Arrays.copyOf(armySlotToRow, grow(armySlotToRow.length, required));
    }

    private void ensureUnitCapacity(int required) {
        if (required <= unitHandles.length) return;
        int capacity = grow(unitHandles.length, required);
        unitHandles = Arrays.copyOf(unitHandles, capacity);
        unitArmies = Arrays.copyOf(unitArmies, capacity);
        unitRevisions = Arrays.copyOf(unitRevisions, capacity);
        unitRoles = Arrays.copyOf(unitRoles, capacity);
        unitTargets = Arrays.copyOf(unitTargets, capacity);
        unitActive = Arrays.copyOf(unitActive, capacity);
    }

    private void ensureUnitSlotCapacity(int required) {
        if (required <= unitSlotToRow.length) return;
        unitSlotToRow = Arrays.copyOf(unitSlotToRow, grow(unitSlotToRow.length, required));
    }

    private void ensurePressureCapacity(int required) {
        if (required <= pressureArmies.length) return;
        int capacity = grow(pressureArmies.length, required);
        pressureArmies = Arrays.copyOf(pressureArmies, capacity);
        pressureTargetMost = Arrays.copyOf(pressureTargetMost, capacity);
        pressureTargetLeast = Arrays.copyOf(pressureTargetLeast, capacity);
        pressureCounts = Arrays.copyOf(pressureCounts, capacity);
    }

    private static int grow(int current, int required) {
        return Math.max(required, current < MIN_GROWTH ? MIN_GROWTH : current + (current >>> 1));
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    static final class BattlePlan {
        private MillVillager target;
        private int targetArmy;
        private int targetUnit;
        private int targetFaction;
        private byte role;
        private byte phase;
        private int morale;
        private boolean retreat;
        private boolean holdFormation;
        private double approachX;
        private double approachZ;

        MillVillager target() {
            return target;
        }

        int targetArmy() {
            return targetArmy;
        }

        int targetUnit() {
            return targetUnit;
        }

        int targetFaction() {
            return targetFaction;
        }

        byte role() {
            return role;
        }

        byte phase() {
            return phase;
        }

        int morale() {
            return morale;
        }

        boolean retreat() {
            return retreat;
        }

        boolean holdFormation() {
            return holdFormation;
        }

        double approachX() {
            return approachX;
        }

        double approachZ() {
            return approachZ;
        }
    }
}
