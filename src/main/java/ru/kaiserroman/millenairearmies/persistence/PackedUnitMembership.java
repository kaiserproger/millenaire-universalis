package ru.kaiserroman.millenairearmies.persistence;

import java.util.Arrays;

/** Primitive unit-handle to persistent MillVillager UUID association; no entity is retained. */
public final class PackedUnitMembership {
    private static final int MIN_GROWTH = 8;
    private int size;
    private int structuralVersion;
    private int[] unitHandles = new int[0];
    private long[] uuidMost = new long[0];
    private long[] uuidLeast = new long[0];

    public int size() {
        return size;
    }

    /**
     * Direct packed-row access for bounded server-thread systems.  The row is deliberately not a
     * stable identity: callers must re-read {@link #size()} after any structural membership
     * mutation and keep only the opaque unit handle.
     */
    public int unitHandleAt(int row) {
        checkRow(row);
        return unitHandles[row];
    }

    public long uuidMostAt(int row) {
        checkRow(row);
        return uuidMost[row];
    }

    public long uuidLeastAt(int row) {
        checkRow(row);
        return uuidLeast[row];
    }

    /** Reserves packed rows so a later command batch can bind without growing arrays. */
    public void reserve(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Negative membership capacity: " + capacity);
        }
        ensureCapacity(capacity);
    }

    public void bind(int unitHandle, long mostSignificantBits, long leastSignificantBits) {
        if (unitHandle == 0) {
            throw new IllegalArgumentException("Cannot bind the zero unit handle");
        }
        int handleRow = -1;
        int uuidRow = -1;
        for (int row = 0; row < size; row++) {
            if (unitHandles[row] == unitHandle) {
                handleRow = row;
            }
            if (uuidMost[row] == mostSignificantBits && uuidLeast[row] == leastSignificantBits) {
                uuidRow = row;
            }
        }
        if (uuidRow >= 0 && uuidRow != handleRow) {
            throw new IllegalArgumentException("Villager UUID is already bound to another unit");
        }
        if (handleRow >= 0) {
            uuidMost[handleRow] = mostSignificantBits;
            uuidLeast[handleRow] = leastSignificantBits;
            return;
        }
        ensureCapacity(size + 1);
        unitHandles[size] = unitHandle;
        uuidMost[size] = mostSignificantBits;
        uuidLeast[size] = leastSignificantBits;
        size++;
        structuralVersion++;
    }

    /** Returns the opaque unit handle for a villager UUID, or zero when it is not bound. */
    public int unitHandleForUuid(long mostSignificantBits, long leastSignificantBits) {
        for (int row = 0; row < size; row++) {
            if (uuidMost[row] == mostSignificantBits && uuidLeast[row] == leastSignificantBits) {
                return unitHandles[row];
            }
        }
        return 0;
    }

    /** Removes one unit association with swap-remove and no allocation. */
    public boolean unbindUnit(int unitHandle) {
        for (int row = 0; row < size; row++) {
            if (unitHandles[row] == unitHandle) {
                removeAt(row);
                return true;
            }
        }
        return false;
    }

    /** Removes one villager association with swap-remove and no allocation. */
    public boolean unbindUuid(long mostSignificantBits, long leastSignificantBits) {
        for (int row = 0; row < size; row++) {
            if (uuidMost[row] == mostSignificantBits && uuidLeast[row] == leastSignificantBits) {
                removeAt(row);
                return true;
            }
        }
        return false;
    }

    public boolean read(int unitHandle, UuidBits destination) {
        for (int row = 0; row < size; row++) {
            if (unitHandles[row] == unitHandle) {
                destination.most = uuidMost[row];
                destination.least = uuidLeast[row];
                return true;
            }
        }
        return false;
    }

    public UuidBits newUuidBits() {
        return new UuidBits();
    }

    public Cursor newCursor() {
        return new Cursor(this);
    }

    private void removeAt(int row) {
        int last = --size;
        if (row != last) {
            unitHandles[row] = unitHandles[last];
            uuidMost[row] = uuidMost[last];
            uuidLeast[row] = uuidLeast[last];
        }
        unitHandles[last] = 0;
        uuidMost[last] = 0L;
        uuidLeast[last] = 0L;
        structuralVersion++;
    }

    private void ensureCapacity(int required) {
        if (required <= unitHandles.length) {
            return;
        }
        int current = unitHandles.length;
        int capacity = Math.max(required, current < MIN_GROWTH ? MIN_GROWTH : current + (current >>> 1));
        unitHandles = Arrays.copyOf(unitHandles, capacity);
        uuidMost = Arrays.copyOf(uuidMost, capacity);
        uuidLeast = Arrays.copyOf(uuidLeast, capacity);
    }

    private void checkRow(int row) {
        if (row < 0 || row >= size) {
            throw new IndexOutOfBoundsException("Membership row " + row + " of " + size);
        }
    }

    public static final class UuidBits {
        private long most;
        private long least;

        private UuidBits() {
        }

        public long most() {
            return most;
        }

        public long least() {
            return least;
        }
    }

    public static final class Cursor {
        private final PackedUnitMembership owner;
        private int expectedStructuralVersion;
        private int nextRow;
        private int row = -1;

        private Cursor(PackedUnitMembership owner) {
            this.owner = owner;
            reset();
        }

        public Cursor reset() {
            expectedStructuralVersion = owner.structuralVersion;
            nextRow = 0;
            row = -1;
            return this;
        }

        public boolean advance() {
            if (expectedStructuralVersion != owner.structuralVersion) {
                throw new IllegalStateException("Membership cursor invalidated by structural change; reset it");
            }
            if (nextRow == owner.size) {
                row = -1;
                return false;
            }
            row = nextRow++;
            return true;
        }

        public int unitHandle() { checkActive(); return owner.unitHandles[row]; }
        public long uuidMost() { checkActive(); return owner.uuidMost[row]; }
        public long uuidLeast() { checkActive(); return owner.uuidLeast[row]; }

        private void checkActive() {
            if (expectedStructuralVersion != owner.structuralVersion || row < 0) {
                throw new IllegalStateException("Membership cursor is not on a valid row");
            }
        }
    }
}
