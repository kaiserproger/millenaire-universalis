package ru.kaiserroman.millenairearmies.server.execution;

import java.util.Arrays;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;

/**
 * Bounded per-army physical siege progress publisher.
 *
 * <p>It owns no settlement, war or realm state. A secured event means only that the attacking
 * entities assembled around the selected objective and no hostile army member is currently assigned
 * in contact. Simulation decides whether that physical fact changes a strategic siege.</p>
 */
final class PhysicalSiegeCoordinator {
    private static final int MIN_GROWTH = 16;
    private static final long REPORT_INTERVAL_TICKS = 100L;
    private static final int PROGRESS_DELTA = 5;
    private static final int SECURED_PROGRESS = 80;
    private static final long CLEAR_CONFIRM_TICKS = 100L;

    private int size;
    private int[] armyHandles = new int[0];
    private long[] revisions = new long[0];
    private int[] lastProgress = new int[0];
    private int[] dimensionIds = new int[0];
    private long[] packedObjectives = new long[0];
    private long[] lastReportTimes = new long[0];
    private long[] nextReportTimes = new long[0];
    private long[] clearSincePlusOne = new long[0];
    private byte[] flags = new byte[0];
    private int[] slotToRow = new int[0];

    void report(
            int armyHandle,
            long revision,
            int sourceFaction,
            int dimensionId,
            long packedObjective,
            int progress,
            boolean defendersEngaged,
            long gameTime,
            PhysicalBattleEventLog events) {
        if (armyHandle == PackedArmyEcs.NO_ARMY || revision <= 0L || sourceFaction < 0
                || dimensionId < 0 || progress < 0 || progress > 100 || gameTime < 0L
                || events == null) {
            throw new IllegalArgumentException("Physical siege report inputs must be complete");
        }
        int row = activate(armyHandle, revision);
        dimensionIds[row] = dimensionId;
        packedObjectives[row] = packedObjective;
        lastReportTimes[row] = gameTime;
        boolean started = (flags[row] & 1) != 0;
        boolean secured = (flags[row] & 2) != 0;
        if (!started) {
            flags[row] |= 1;
            events.append(
                    PhysicalBattleEventLog.SIEGE_STARTED,
                    gameTime,
                    armyHandle,
                    PackedArmyEcs.NO_ARMY,
                    0,
                    0,
                    sourceFaction,
                    -1,
                    dimensionId,
                    packedObjective,
                    progress);
        }
        if (gameTime >= nextReportTimes[row]
                || Math.abs(progress - lastProgress[row]) >= PROGRESS_DELTA) {
            lastProgress[row] = progress;
            nextReportTimes[row] = saturatedAdd(gameTime, REPORT_INTERVAL_TICKS);
            events.append(
                    PhysicalBattleEventLog.SIEGE_PROGRESS,
                    gameTime,
                    armyHandle,
                    PackedArmyEcs.NO_ARMY,
                    0,
                    0,
                    sourceFaction,
                    -1,
                    dimensionId,
                    packedObjective,
                    progress);
        }
        if (defendersEngaged || progress < SECURED_PROGRESS) {
            clearSincePlusOne[row] = 0L;
        } else if (clearSincePlusOne[row] == 0L) {
            clearSincePlusOne[row] = saturatedAdd(gameTime, 1L);
        } else if (!secured) {
            long clearSince = clearSincePlusOne[row] - 1L;
            if (gameTime - clearSince >= CLEAR_CONFIRM_TICKS) {
                flags[row] |= 2;
                events.append(
                        PhysicalBattleEventLog.SIEGE_SECURED,
                        gameTime,
                        armyHandle,
                        PackedArmyEcs.NO_ARMY,
                        0,
                        0,
                        sourceFaction,
                        -1,
                        dimensionId,
                        packedObjective,
                        progress);
            }
        }
    }

    boolean activeNear(
            int dimensionId,
            long packedPosition,
            int radiusBlocks,
            long gameTime) {
        if (dimensionId < 0 || radiusBlocks < 1 || gameTime < 0L) return false;
        long radiusSq = (long) radiusBlocks * radiusBlocks;
        int x = PackedArmyEcs.unpackBlockX(packedPosition);
        int y = PackedArmyEcs.unpackBlockY(packedPosition);
        int z = PackedArmyEcs.unpackBlockZ(packedPosition);
        for (int row = 0; row < size; row++) {
            if ((flags[row] & 1) == 0 || dimensionIds[row] != dimensionId
                    || gameTime - lastReportTimes[row] > REPORT_INTERVAL_TICKS * 2L) {
                continue;
            }
            long dx = (long) PackedArmyEcs.unpackBlockX(packedObjectives[row]) - x;
            long dy = (long) PackedArmyEcs.unpackBlockY(packedObjectives[row]) - y;
            long dz = (long) PackedArmyEcs.unpackBlockZ(packedObjectives[row]) - z;
            if (dx * dx + dy * dy + dz * dz <= radiusSq) return true;
        }
        return false;
    }

    void clear() {
        Arrays.fill(armyHandles, 0, size, 0);
        Arrays.fill(revisions, 0, size, 0L);
        Arrays.fill(lastProgress, 0, size, 0);
        Arrays.fill(dimensionIds, 0, size, 0);
        Arrays.fill(packedObjectives, 0, size, 0L);
        Arrays.fill(lastReportTimes, 0, size, 0L);
        Arrays.fill(nextReportTimes, 0, size, 0L);
        Arrays.fill(clearSincePlusOne, 0, size, 0L);
        Arrays.fill(flags, 0, size, (byte) 0);
        Arrays.fill(slotToRow, 0);
        size = 0;
    }

    private int activate(int armyHandle, long revision) {
        int slot = PackedArmyEcs.handleSlotIndex(armyHandle);
        ensureSlotCapacity(slot + 1);
        int row = slotToRow[slot] - 1;
        if (row < 0) {
            ensureCapacity(size + 1);
            row = size++;
            slotToRow[slot] = row + 1;
        }
        if (armyHandles[row] != armyHandle || revisions[row] != revision) {
            armyHandles[row] = armyHandle;
            revisions[row] = revision;
            lastProgress[row] = 0;
            nextReportTimes[row] = 0L;
            clearSincePlusOne[row] = 0L;
            flags[row] = 0;
        }
        return row;
    }

    private void ensureCapacity(int required) {
        if (required <= armyHandles.length) {
            return;
        }
        int capacity = grow(armyHandles.length, required);
        armyHandles = Arrays.copyOf(armyHandles, capacity);
        revisions = Arrays.copyOf(revisions, capacity);
        lastProgress = Arrays.copyOf(lastProgress, capacity);
        dimensionIds = Arrays.copyOf(dimensionIds, capacity);
        packedObjectives = Arrays.copyOf(packedObjectives, capacity);
        lastReportTimes = Arrays.copyOf(lastReportTimes, capacity);
        nextReportTimes = Arrays.copyOf(nextReportTimes, capacity);
        clearSincePlusOne = Arrays.copyOf(clearSincePlusOne, capacity);
        flags = Arrays.copyOf(flags, capacity);
    }

    private void ensureSlotCapacity(int required) {
        if (required <= slotToRow.length) {
            return;
        }
        slotToRow = Arrays.copyOf(slotToRow, grow(slotToRow.length, required));
    }

    private static int grow(int current, int required) {
        return Math.max(required, current < MIN_GROWTH ? MIN_GROWTH : current + (current >>> 1));
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
