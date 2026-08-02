package ru.kaiserroman.millenairearmies.server.execution;

import java.util.Arrays;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.model.ArmyFormation;

/**
 * Primitive, server-thread formation gate shared by retained physical attack tasks.
 *
 * <p>The coordinator owns no level, entity, path, UUID, or combat state. Tasks report their real
 * positions, receive one moving slot, and may acquire a combat target only after the formation has
 * assembled and advanced into contact. Damage, knockback, death and capture remain real entity
 * events handled elsewhere.</p>
 */
public final class ArmyFormationCoordinator {
    public static final byte ASSEMBLING = 0;
    public static final byte ADVANCING = 1;

    private static final int MIN_GROWTH = 16;
    private static final long ASSEMBLY_GRACE_TICKS = 30L;
    private static final long SINGLE_UNIT_GRACE_TICKS = 10L;
    private static final long ASSEMBLY_TIMEOUT_TICKS = 200L;
    private static final double STAGING_DISTANCE = 18.0D;
    private static final double MIN_STAGING_DISTANCE = 6.0D;
    private static final double ADVANCE_PER_TICK = 0.14D;
    private static final double ENGAGEMENT_DISTANCE = 8.5D;
    private static final double ASSEMBLY_READY_DISTANCE_SQ = 2.75D * 2.75D;
    private static final double ADVANCE_READY_DISTANCE_SQ = 5.0D * 5.0D;

    private int armySize;
    private int[] armyHandles = new int[0];
    private long[] armyRevisions = new long[0];
    private int[] armyFormationCodes = new int[0];
    private int[] armyExpectedUnits = new int[0];
    private int[] armyActiveUnits = new int[0];
    private int[] armyReadyUnits = new int[0];
    private int[] armyNextSlots = new int[0];
    private long[] armyTargets = new long[0];
    private long[] armyStartedTicks = new long[0];
    private long[] armyLastAdvanceTicks = new long[0];
    private double[] armyTargetX = new double[0];
    private double[] armyTargetZ = new double[0];
    private double[] armyAnchorX = new double[0];
    private double[] armyAnchorZ = new double[0];
    private double[] armyForwardX = new double[0];
    private double[] armyForwardZ = new double[0];
    private byte[] armyPhases = new byte[0];
    private boolean[] armyInitialized = new boolean[0];
    private int[] armySlotToRow = new int[0];

    private int unitSize;
    private int[] unitHandles = new int[0];
    private int[] unitArmies = new int[0];
    private long[] unitRevisions = new long[0];
    private int[] unitFormationSlots = new int[0];
    private boolean[] unitActive = new boolean[0];
    private boolean[] unitReady = new boolean[0];
    private int[] unitSlotToRow = new int[0];

    private long formationAdvances;
    private long cohesionPauses;

    public void reserve(int armies, int units) {
        if (armies < 0 || units < 0) {
            throw new IllegalArgumentException("Negative formation coordinator capacity");
        }
        ensureArmyCapacity(armies);
        ensureUnitCapacity(units);
    }

    /** Advances every active formation at most once for the supplied server game tick. */
    public void tick(long gameTime) {
        for (int row = 0; row < armySize; row++) {
            if (!armyInitialized[row]
                    || armyPhases[row] != ADVANCING
                    || gameTime <= armyLastAdvanceTicks[row]) {
                continue;
            }
            armyLastAdvanceTicks[row] = gameTime;
            int active = armyActiveUnits[row];
            if (active == 0) continue;
            if (active > 2 && armyReadyUnits[row] * 2 < active) {
                cohesionPauses++;
                continue;
            }

            double dx = armyTargetX[row] - armyAnchorX[row];
            double dz = armyTargetZ[row] - armyAnchorZ[row];
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance <= ENGAGEMENT_DISTANCE) continue;
            double step = Math.min(ADVANCE_PER_TICK, distance - ENGAGEMENT_DISTANCE);
            armyAnchorX[row] += dx / distance * step;
            armyAnchorZ[row] += dz / distance * step;
        }
    }

    /** Updates one real unit's cohesion report and writes its moving slot into {@code out}. */
    public Plan plan(
            int armyHandle,
            int unitHandle,
            long revision,
            int formationCode,
            int expectedUnits,
            long packedTarget,
            double unitX,
            double unitY,
            double unitZ,
            long gameTime,
            Plan out) {
        if (armyHandle == 0 || unitHandle == 0 || revision <= 0L || out == null) {
            throw new IllegalArgumentException("Formation plan identity must be non-zero");
        }
        if (!ArmyFormation.isValidCode(formationCode)) formationCode = ArmyFormation.LINE.code();
        int armyRow = prepareArmy(
                armyHandle,
                revision,
                formationCode,
                Math.max(1, expectedUnits),
                packedTarget,
                gameTime);
        initializeAnchor(armyRow, unitX, unitZ);
        int unitRow = activateUnit(armyRow, armyHandle, unitHandle, revision);
        int slot = unitFormationSlots[unitRow];

        double right = rightOffset(formationCode, slot, armyExpectedUnits[armyRow]);
        double forward = forwardOffset(formationCode, slot, armyExpectedUnits[armyRow]);
        double rightX = -armyForwardZ[armyRow];
        double rightZ = armyForwardX[armyRow];
        double plannedX = armyAnchorX[armyRow] + rightX * right + armyForwardX[armyRow] * forward;
        double plannedZ = armyAnchorZ[armyRow] + rightZ * right + armyForwardZ[armyRow] * forward;

        double dx = unitX - plannedX;
        double dz = unitZ - plannedZ;
        double readyDistance = armyPhases[armyRow] == ASSEMBLING
                ? ASSEMBLY_READY_DISTANCE_SQ
                : ADVANCE_READY_DISTANCE_SQ;
        updateReady(armyRow, unitRow, dx * dx + dz * dz <= readyDistance);
        maybeBeginAdvance(armyRow, gameTime);

        double targetDx = armyTargetX[armyRow] - armyAnchorX[armyRow];
        double targetDz = armyTargetZ[armyRow] - armyAnchorZ[armyRow];
        out.packedPosition = PackedArmyEcs.packBlockPos(
                floorBlock(plannedX), floorBlock(unitY), floorBlock(plannedZ));
        out.phase = armyPhases[armyRow];
        out.canEngage = out.phase == ADVANCING
                && targetDx * targetDx + targetDz * targetDz
                        <= (ENGAGEMENT_DISTANCE + 1.5D) * (ENGAGEMENT_DISTANCE + 1.5D);
        out.cohesionPercent = armyActiveUnits[armyRow] == 0
                ? 0
                : armyReadyUnits[armyRow] * 100 / armyActiveUnits[armyRow];
        return out;
    }

    /** Removes an unloaded/dead unit from current cohesion counters without scanning an army. */
    public boolean removeUnit(int unitHandle) {
        int unitRow = unitRow(unitHandle);
        if (unitRow < 0 || !unitActive[unitRow]) return false;
        detachUnit(unitRow);
        return true;
    }

    public int trackedArmies() {
        return armySize;
    }

    public int trackedUnits() {
        return unitSize;
    }

    public long formationAdvances() {
        return formationAdvances;
    }

    public long cohesionPauses() {
        return cohesionPauses;
    }

    public void clear() {
        Arrays.fill(armyHandles, 0, armySize, 0);
        Arrays.fill(armySlotToRow, 0);
        Arrays.fill(unitHandles, 0, unitSize, 0);
        Arrays.fill(unitActive, 0, unitSize, false);
        Arrays.fill(unitReady, 0, unitSize, false);
        Arrays.fill(unitSlotToRow, 0);
        armySize = 0;
        unitSize = 0;
        formationAdvances = 0L;
        cohesionPauses = 0L;
    }

    private int prepareArmy(
            int armyHandle,
            long revision,
            int formationCode,
            int expectedUnits,
            long packedTarget,
            long gameTime) {
        int slot = PackedArmyEcs.handleSlotIndex(armyHandle);
        ensureArmySlotCapacity(slot + 1);
        int row = armySlotToRow[slot] - 1;
        if (row < 0) {
            ensureArmyCapacity(armySize + 1);
            row = armySize++;
            armySlotToRow[slot] = row + 1;
        }
        if (armyHandles[row] == armyHandle
                && armyRevisions[row] == revision
                && armyFormationCodes[row] == formationCode
                && armyTargets[row] == packedTarget) {
            armyExpectedUnits[row] = expectedUnits;
            return row;
        }

        armyHandles[row] = armyHandle;
        armyRevisions[row] = revision;
        armyFormationCodes[row] = formationCode;
        armyExpectedUnits[row] = expectedUnits;
        armyActiveUnits[row] = 0;
        armyReadyUnits[row] = 0;
        armyNextSlots[row] = 0;
        armyTargets[row] = packedTarget;
        armyStartedTicks[row] = gameTime;
        armyLastAdvanceTicks[row] = gameTime;
        armyTargetX[row] = PackedArmyEcs.unpackBlockX(packedTarget) + 0.5D;
        armyTargetZ[row] = PackedArmyEcs.unpackBlockZ(packedTarget) + 0.5D;
        armyAnchorX[row] = 0.0D;
        armyAnchorZ[row] = 0.0D;
        armyForwardX[row] = 0.0D;
        armyForwardZ[row] = 1.0D;
        armyPhases[row] = ASSEMBLING;
        armyInitialized[row] = false;
        return row;
    }

    private void initializeAnchor(int armyRow, double unitX, double unitZ) {
        if (armyInitialized[armyRow]) return;
        double dx = armyTargetX[armyRow] - unitX;
        double dz = armyTargetZ[armyRow] - unitZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 0.001D) {
            dx = 0.0D;
            dz = 1.0D;
            distance = 1.0D;
        }
        armyForwardX[armyRow] = dx / distance;
        armyForwardZ[armyRow] = dz / distance;
        double staging = Math.min(STAGING_DISTANCE, Math.max(MIN_STAGING_DISTANCE, distance * 0.55D));
        armyAnchorX[armyRow] = armyTargetX[armyRow] - armyForwardX[armyRow] * staging;
        armyAnchorZ[armyRow] = armyTargetZ[armyRow] - armyForwardZ[armyRow] * staging;
        armyInitialized[armyRow] = true;
    }

    private int activateUnit(int armyRow, int armyHandle, int unitHandle, long revision) {
        int slot = PackedArmyEcs.handleSlotIndex(unitHandle);
        ensureUnitSlotCapacity(slot + 1);
        int row = unitSlotToRow[slot] - 1;
        if (row < 0) {
            ensureUnitCapacity(unitSize + 1);
            row = unitSize++;
            unitSlotToRow[slot] = row + 1;
        }
        if (unitHandles[row] == unitHandle
                && unitArmies[row] == armyHandle
                && unitRevisions[row] == revision
                && unitActive[row]) {
            return row;
        }
        if (unitActive[row]) detachUnit(row);
        unitHandles[row] = unitHandle;
        unitArmies[row] = armyHandle;
        unitRevisions[row] = revision;
        unitFormationSlots[row] = armyNextSlots[armyRow]++;
        unitActive[row] = true;
        unitReady[row] = false;
        armyActiveUnits[armyRow]++;
        return row;
    }

    private void updateReady(int armyRow, int unitRow, boolean ready) {
        if (unitReady[unitRow] == ready) return;
        unitReady[unitRow] = ready;
        armyReadyUnits[armyRow] += ready ? 1 : -1;
    }

    private void maybeBeginAdvance(int armyRow, long gameTime) {
        if (armyPhases[armyRow] != ASSEMBLING || armyActiveUnits[armyRow] == 0) return;
        long elapsed = Math.max(0L, gameTime - armyStartedTicks[armyRow]);
        long grace = armyActiveUnits[armyRow] == 1
                ? SINGLE_UNIT_GRACE_TICKS
                : ASSEMBLY_GRACE_TICKS;
        int required = Math.max(1, (armyActiveUnits[armyRow] * 70 + 99) / 100);
        if (elapsed >= ASSEMBLY_TIMEOUT_TICKS
                || elapsed >= grace && armyReadyUnits[armyRow] >= required) {
            armyPhases[armyRow] = ADVANCING;
            formationAdvances++;
        }
    }

    private void detachUnit(int unitRow) {
        int armyRow = armyRow(unitArmies[unitRow]);
        if (armyRow >= 0 && armyRevisions[armyRow] == unitRevisions[unitRow]) {
            armyActiveUnits[armyRow] = Math.max(0, armyActiveUnits[armyRow] - 1);
            if (unitReady[unitRow]) {
                armyReadyUnits[armyRow] = Math.max(0, armyReadyUnits[armyRow] - 1);
            }
        }
        unitActive[unitRow] = false;
        unitReady[unitRow] = false;
    }

    private int armyRow(int armyHandle) {
        if (armyHandle == 0) return -1;
        int slot = PackedArmyEcs.handleSlotIndex(armyHandle);
        if (slot >= armySlotToRow.length) return -1;
        int row = armySlotToRow[slot] - 1;
        return row >= 0 && armyHandles[row] == armyHandle ? row : -1;
    }

    private int unitRow(int unitHandle) {
        if (unitHandle == 0) return -1;
        int slot = PackedArmyEcs.handleSlotIndex(unitHandle);
        if (slot >= unitSlotToRow.length) return -1;
        int row = unitSlotToRow[slot] - 1;
        return row >= 0 && unitHandles[row] == unitHandle ? row : -1;
    }

    private static double rightOffset(int formation, int slot, int expectedUnits) {
        return switch (formation) {
            case 1 -> centeredColumn(slot, expectedUnits, 2, 2.2D);
            case 2 -> slot == 0 ? 0.0D : ((slot & 1) == 0 ? 1.0D : -1.0D)
                    * ((slot + 1) / 2) * 2.15D;
            case 3 -> centeredColumn(slot, expectedUnits, squareWidth(expectedUnits), 2.25D);
            case 4 -> centeredColumn(slot, expectedUnits, skirmishWidth(expectedUnits), 3.4D);
            default -> centeredColumn(slot, expectedUnits, Math.min(12, expectedUnits), 2.2D);
        };
    }

    private static double forwardOffset(int formation, int slot, int expectedUnits) {
        return switch (formation) {
            case 1 -> -(slot / 2) * 2.4D;
            case 2 -> slot == 0 ? 0.0D : -((slot + 1) / 2) * 1.85D;
            case 3 -> -(slot / squareWidth(expectedUnits)) * 2.25D;
            case 4 -> -(slot / skirmishWidth(expectedUnits)) * 3.0D;
            default -> -(slot / Math.min(12, expectedUnits)) * 2.4D;
        };
    }

    private static double centeredColumn(int slot, int expectedUnits, int width, double spacing) {
        int row = slot / width;
        int column = slot % width;
        int remaining = Math.max(1, expectedUnits - row * width);
        int rowWidth = Math.min(width, remaining);
        return (column - (rowWidth - 1) * 0.5D) * spacing;
    }

    private static int squareWidth(int expectedUnits) {
        return Math.max(1, (int) Math.ceil(Math.sqrt(expectedUnits)));
    }

    private static int skirmishWidth(int expectedUnits) {
        return Math.max(1, Math.min(10, (int) Math.ceil(Math.sqrt(expectedUnits * 2.0D))));
    }

    private static int floorBlock(double coordinate) {
        return (int) Math.floor(coordinate);
    }

    private void ensureArmyCapacity(int required) {
        if (required <= armyHandles.length) return;
        int capacity = grow(armyHandles.length, required);
        armyHandles = Arrays.copyOf(armyHandles, capacity);
        armyRevisions = Arrays.copyOf(armyRevisions, capacity);
        armyFormationCodes = Arrays.copyOf(armyFormationCodes, capacity);
        armyExpectedUnits = Arrays.copyOf(armyExpectedUnits, capacity);
        armyActiveUnits = Arrays.copyOf(armyActiveUnits, capacity);
        armyReadyUnits = Arrays.copyOf(armyReadyUnits, capacity);
        armyNextSlots = Arrays.copyOf(armyNextSlots, capacity);
        armyTargets = Arrays.copyOf(armyTargets, capacity);
        armyStartedTicks = Arrays.copyOf(armyStartedTicks, capacity);
        armyLastAdvanceTicks = Arrays.copyOf(armyLastAdvanceTicks, capacity);
        armyTargetX = Arrays.copyOf(armyTargetX, capacity);
        armyTargetZ = Arrays.copyOf(armyTargetZ, capacity);
        armyAnchorX = Arrays.copyOf(armyAnchorX, capacity);
        armyAnchorZ = Arrays.copyOf(armyAnchorZ, capacity);
        armyForwardX = Arrays.copyOf(armyForwardX, capacity);
        armyForwardZ = Arrays.copyOf(armyForwardZ, capacity);
        armyPhases = Arrays.copyOf(armyPhases, capacity);
        armyInitialized = Arrays.copyOf(armyInitialized, capacity);
    }

    private void ensureUnitCapacity(int required) {
        if (required <= unitHandles.length) return;
        int capacity = grow(unitHandles.length, required);
        unitHandles = Arrays.copyOf(unitHandles, capacity);
        unitArmies = Arrays.copyOf(unitArmies, capacity);
        unitRevisions = Arrays.copyOf(unitRevisions, capacity);
        unitFormationSlots = Arrays.copyOf(unitFormationSlots, capacity);
        unitActive = Arrays.copyOf(unitActive, capacity);
        unitReady = Arrays.copyOf(unitReady, capacity);
    }

    private void ensureArmySlotCapacity(int required) {
        if (required <= armySlotToRow.length) return;
        armySlotToRow = Arrays.copyOf(armySlotToRow, grow(armySlotToRow.length, required));
    }

    private void ensureUnitSlotCapacity(int required) {
        if (required <= unitSlotToRow.length) return;
        unitSlotToRow = Arrays.copyOf(unitSlotToRow, grow(unitSlotToRow.length, required));
    }

    private static int grow(int current, int required) {
        return Math.max(required, current < MIN_GROWTH ? MIN_GROWTH : current + (current >>> 1));
    }

    /** Caller-owned output reused by one retained task. */
    public static final class Plan {
        private long packedPosition;
        private byte phase;
        private boolean canEngage;
        private int cohesionPercent;

        public long packedPosition() {
            return packedPosition;
        }

        public byte phase() {
            return phase;
        }

        public boolean canEngage() {
            return canEngage;
        }

        public int cohesionPercent() {
            return cohesionPercent;
        }
    }
}
