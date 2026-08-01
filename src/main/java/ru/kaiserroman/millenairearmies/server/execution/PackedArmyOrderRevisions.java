package ru.kaiserroman.millenairearmies.server.execution;

import java.util.Arrays;

/**
 * Runtime projection of committed army orders.
 *
 * <p>The persisted ECS remains authoritative.  This table adds a monotonic runtime revision so a
 * large unit roster can observe a changed order incrementally without comparing or allocating
 * command objects.  Rows are keyed by the full generational army handle.</p>
 */
public final class PackedArmyOrderRevisions {
    private static final int MIN_GROWTH = 8;

    private int size;
    private int[] armyHandles = new int[0];
    private int[] orderCodes = new int[0];
    private long[] packedTargets = new long[0];
    private long[] revisions = new long[0];

    public void reserve(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Negative order projection capacity");
        }
        ensureCapacity(capacity);
    }

    /**
     * Observes the current committed value.  A new row starts at revision one; an actual value
     * change increments it; an identical observation is a no-op.
     */
    public long observe(int armyHandle, int orderCode, long packedTarget) {
        if (armyHandle == 0) {
            throw new IllegalArgumentException("Zero is not a valid army handle");
        }
        int row = indexOf(armyHandle);
        if (row < 0) {
            ensureCapacity(size + 1);
            row = size++;
            armyHandles[row] = armyHandle;
            orderCodes[row] = orderCode;
            packedTargets[row] = packedTarget;
            revisions[row] = 1L;
            return 1L;
        }
        if (orderCodes[row] == orderCode && packedTargets[row] == packedTarget) {
            return revisions[row];
        }
        if (revisions[row] == Long.MAX_VALUE) {
            throw new IllegalStateException("Army order revision space exhausted for " + armyHandle);
        }
        orderCodes[row] = orderCode;
        packedTargets[row] = packedTarget;
        return ++revisions[row];
    }

    public long revision(int armyHandle) {
        int row = indexOf(armyHandle);
        return row < 0 ? 0L : revisions[row];
    }

    public int orderCode(int armyHandle) {
        int row = indexOf(armyHandle);
        if (row < 0) {
            throw new IllegalArgumentException("Unknown army order projection: " + armyHandle);
        }
        return orderCodes[row];
    }

    public long packedTarget(int armyHandle) {
        int row = indexOf(armyHandle);
        if (row < 0) {
            throw new IllegalArgumentException("Unknown army order projection: " + armyHandle);
        }
        return packedTargets[row];
    }

    public int size() {
        return size;
    }

    public void clear() {
        Arrays.fill(armyHandles, 0, size, 0);
        Arrays.fill(orderCodes, 0, size, 0);
        Arrays.fill(packedTargets, 0, size, 0L);
        Arrays.fill(revisions, 0, size, 0L);
        size = 0;
    }

    private int indexOf(int armyHandle) {
        for (int row = 0; row < size; row++) {
            if (armyHandles[row] == armyHandle) {
                return row;
            }
        }
        return -1;
    }

    private void ensureCapacity(int required) {
        if (required <= armyHandles.length) {
            return;
        }
        int current = armyHandles.length;
        int capacity = Math.max(required, current < MIN_GROWTH ? MIN_GROWTH : current + (current >>> 1));
        armyHandles = Arrays.copyOf(armyHandles, capacity);
        orderCodes = Arrays.copyOf(orderCodes, capacity);
        packedTargets = Arrays.copyOf(packedTargets, capacity);
        revisions = Arrays.copyOf(revisions, capacity);
    }
}
