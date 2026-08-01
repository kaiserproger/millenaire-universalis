package ru.kaiserroman.millenairearmies.server.execution;

import java.util.Arrays;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;

/** Primitive per-unit order acknowledgement state; all hot-path transitions are allocation-free. */
public final class PackedUnitExecutionState {
    public static final byte PENDING = 0;
    public static final byte RUNNING = 1;
    public static final byte ARRIVED = 2;
    public static final byte BLOCKED = 3;
    public static final byte TERMINAL = ARRIVED;

    private static final int MIN_GROWTH = 16;

    private int size;
    private int[] unitHandles = new int[0];
    private int[] armyHandles = new int[0];
    private long[] revisions = new long[0];
    private byte[] statuses = new byte[0];
    /** ECS slot -> dense row + 1. The full generational handle is still checked on every lookup. */
    private int[] slotToRow = new int[0];

    public void reserve(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Negative unit execution capacity");
        }
        ensureCapacity(capacity);
    }

    /** True when this unit has not yet acknowledged the exact army/revision pair. */
    public boolean needsApply(int unitHandle, int armyHandle, long revision) {
        int row = indexOf(unitHandle);
        return row < 0
                || armyHandles[row] != armyHandle
                || revisions[row] != revision
                || statuses[row] == PENDING;
    }

    public void markRunning(int unitHandle, int armyHandle, long revision) {
        put(unitHandle, armyHandle, revision, RUNNING);
    }

    /**
     * Publishes a newer revision before cancelling its predecessor.  A late callback from the old
     * retained task then fails its exact army/revision guard and cannot rewind this pending work.
     */
    public void markPending(int unitHandle, int armyHandle, long revision) {
        put(unitHandle, armyHandle, revision, PENDING);
    }

    public void markTerminal(int unitHandle, int armyHandle, long revision) {
        markArrived(unitHandle, armyHandle, revision);
    }

    public void markArrived(int unitHandle, int armyHandle, long revision) {
        put(unitHandle, armyHandle, revision, ARRIVED);
    }

    public void markBlocked(int unitHandle, int armyHandle, long revision) {
        put(unitHandle, armyHandle, revision, BLOCKED);
    }

    /** Completes only the task that still owns the exact current revision. */
    public boolean markTerminalIfCurrent(int unitHandle, int armyHandle, long revision) {
        return markIfCurrent(unitHandle, armyHandle, revision, ARRIVED);
    }

    public boolean markArrivedIfCurrent(int unitHandle, int armyHandle, long revision) {
        return markIfCurrent(unitHandle, armyHandle, revision, ARRIVED);
    }

    public boolean markBlockedIfCurrent(int unitHandle, int armyHandle, long revision) {
        return markIfCurrent(unitHandle, armyHandle, revision, BLOCKED);
    }

    private boolean markIfCurrent(int unitHandle, int armyHandle, long revision, byte status) {
        int row = indexOf(unitHandle);
        if (row < 0 || armyHandles[row] != armyHandle || revisions[row] != revision) {
            return false;
        }
        statuses[row] = status;
        return true;
    }

    /** Marks an interrupted execution for a bounded later retry, but never rewinds a newer order. */
    public boolean markRetry(int unitHandle, int armyHandle, long revision) {
        int row = indexOf(unitHandle);
        if (row < 0 || armyHandles[row] != armyHandle || revisions[row] != revision) {
            return false;
        }
        statuses[row] = PENDING;
        return true;
    }

    /** Forces the current order to be replayed after an entity unload/reload boundary. */
    public boolean invalidate(int unitHandle) {
        int row = indexOf(unitHandle);
        if (row < 0) {
            return false;
        }
        statuses[row] = PENDING;
        return true;
    }

    public byte status(int unitHandle) {
        int row = indexOf(unitHandle);
        return row < 0 ? PENDING : statuses[row];
    }

    public int size() {
        return size;
    }

    public void clear() {
        Arrays.fill(unitHandles, 0, size, 0);
        Arrays.fill(armyHandles, 0, size, 0);
        Arrays.fill(revisions, 0, size, 0L);
        Arrays.fill(statuses, 0, size, PENDING);
        Arrays.fill(slotToRow, 0);
        size = 0;
    }

    private void put(int unitHandle, int armyHandle, long revision, byte status) {
        if (unitHandle == 0 || armyHandle == 0 || revision <= 0L) {
            throw new IllegalArgumentException("Execution identity must be non-zero");
        }
        int slot = PackedArmyEcs.handleSlotIndex(unitHandle);
        ensureSlotCapacity(slot + 1);
        int row = slotToRow[slot] - 1;
        if (row < 0) {
            ensureCapacity(size + 1);
            row = size++;
            slotToRow[slot] = row + 1;
        }
        // A slot reused with a new generation supersedes its stale runtime acknowledgement.
        unitHandles[row] = unitHandle;
        armyHandles[row] = armyHandle;
        revisions[row] = revision;
        statuses[row] = status;
    }

    private int indexOf(int unitHandle) {
        int slot = PackedArmyEcs.handleSlotIndex(unitHandle);
        if (slot >= slotToRow.length) {
            return -1;
        }
        int row = slotToRow[slot] - 1;
        return row >= 0 && unitHandles[row] == unitHandle ? row : -1;
    }

    private void ensureCapacity(int required) {
        if (required <= unitHandles.length) {
            return;
        }
        int current = unitHandles.length;
        int capacity = Math.max(required, current < MIN_GROWTH ? MIN_GROWTH : current + (current >>> 1));
        unitHandles = Arrays.copyOf(unitHandles, capacity);
        armyHandles = Arrays.copyOf(armyHandles, capacity);
        revisions = Arrays.copyOf(revisions, capacity);
        statuses = Arrays.copyOf(statuses, capacity);
    }

    private void ensureSlotCapacity(int required) {
        if (required <= slotToRow.length) {
            return;
        }
        int current = slotToRow.length;
        int capacity = Math.max(required, current < MIN_GROWTH ? MIN_GROWTH : current + (current >>> 1));
        slotToRow = Arrays.copyOf(slotToRow, capacity);
    }
}
