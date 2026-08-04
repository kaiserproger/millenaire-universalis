package ru.kaiserroman.millenairearmies.persistence;

import java.util.Arrays;

/** Persistent one-chest supply binding per army. Contains only primitive stable identifiers. */
public final class PackedArmySupplyState {
    private int size;
    private int structuralVersion;
    private long revision;
    private int[] armyHandles = new int[0];
    private int[] dimensionIds = new int[0];
    private long[] chestPositions = new long[0];

    public int size() { return size; }
    public long revision() { return revision; }

    public void reserve(int expected) {
        if (expected < 0) throw new IllegalArgumentException("Negative supply binding capacity");
        ensureCapacity(expected);
    }

    public boolean assign(int armyHandle, int dimensionId, long chestPosition) {
        requireIdentity(armyHandle, dimensionId);
        int row = findArmy(armyHandle);
        if (row >= 0) {
            if (dimensionIds[row] == dimensionId && chestPositions[row] == chestPosition) return false;
            dimensionIds[row] = dimensionId;
            chestPositions[row] = chestPosition;
            incrementRevision();
            return true;
        }
        ensureCapacity(size + 1);
        row = size++;
        armyHandles[row] = armyHandle;
        dimensionIds[row] = dimensionId;
        chestPositions[row] = chestPosition;
        structuralVersion++;
        incrementRevision();
        return true;
    }

    public boolean remove(int armyHandle) {
        int row = findArmy(armyHandle);
        if (row < 0) return false;
        int last = --size;
        if (row != last) {
            armyHandles[row] = armyHandles[last];
            dimensionIds[row] = dimensionIds[last];
            chestPositions[row] = chestPositions[last];
        }
        armyHandles[last] = 0;
        dimensionIds[last] = 0;
        chestPositions[last] = 0L;
        structuralVersion++;
        incrementRevision();
        return true;
    }

    public int findArmy(int armyHandle) {
        for (int row = 0; row < size; row++) {
            if (armyHandles[row] == armyHandle) return row;
        }
        return -1;
    }

    public int armyHandleAt(int row) { checkRow(row); return armyHandles[row]; }
    public int dimensionIdAt(int row) { checkRow(row); return dimensionIds[row]; }
    public long chestPositionAt(int row) { checkRow(row); return chestPositions[row]; }

    public void restoreRow(int armyHandle, int dimensionId, long chestPosition) {
        if (revision != 0L) {
            throw new IllegalStateException("Supply rows must be restored before runtime mutation");
        }
        requireIdentity(armyHandle, dimensionId);
        if (findArmy(armyHandle) >= 0) {
            throw new IllegalArgumentException("Duplicate persisted supply army handle " + armyHandle);
        }
        ensureCapacity(size + 1);
        armyHandles[size] = armyHandle;
        dimensionIds[size] = dimensionId;
        chestPositions[size] = chestPosition;
        size++;
        structuralVersion++;
    }

    public void restoreRevision(long restoredRevision) {
        if (revision != 0L) {
            throw new IllegalStateException("Supply revision can only be restored once");
        }
        if (restoredRevision < size) {
            throw new IllegalArgumentException(
                    "Supply revision " + restoredRevision + " is below row count " + size);
        }
        revision = restoredRevision;
    }

    public Cursor newCursor() { return new Cursor(this); }

    private void incrementRevision() {
        if (revision == Long.MAX_VALUE) throw new IllegalStateException("Supply revision exhausted");
        revision++;
    }

    private void ensureCapacity(int required) {
        if (required <= armyHandles.length) return;
        int current = armyHandles.length;
        int capacity = Math.max(required, current < 8 ? 8 : current + (current >>> 1));
        armyHandles = Arrays.copyOf(armyHandles, capacity);
        dimensionIds = Arrays.copyOf(dimensionIds, capacity);
        chestPositions = Arrays.copyOf(chestPositions, capacity);
    }

    private static void requireIdentity(int armyHandle, int dimensionId) {
        if (armyHandle == 0) throw new IllegalArgumentException("Zero is not a valid army handle");
        if (dimensionId < 0) throw new IllegalArgumentException("Negative supply dimension id");
    }

    private void checkRow(int row) {
        if (row < 0 || row >= size) throw new IndexOutOfBoundsException("Supply row " + row);
    }

    public static final class Cursor {
        private final PackedArmySupplyState owner;
        private int expectedVersion;
        private int next;
        private int row = -1;

        private Cursor(PackedArmySupplyState owner) { this.owner = owner; reset(); }
        public Cursor reset() { expectedVersion = owner.structuralVersion; next = 0; row = -1; return this; }
        public boolean advance() {
            checkVersion();
            if (next >= owner.size) { row = -1; return false; }
            row = next++;
            return true;
        }
        public int armyHandle() { checkActive(); return owner.armyHandles[row]; }
        public int dimensionId() { checkActive(); return owner.dimensionIds[row]; }
        public long chestPosition() { checkActive(); return owner.chestPositions[row]; }
        private void checkActive() { checkVersion(); if (row < 0) throw new IllegalStateException("Inactive cursor"); }
        private void checkVersion() {
            if (expectedVersion != owner.structuralVersion) throw new IllegalStateException("Supply cursor invalidated");
        }
    }
}
