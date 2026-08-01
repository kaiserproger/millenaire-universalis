package ru.kaiserroman.millenairearmies.persistence;

import java.util.Arrays;
import ru.kaiserroman.millenairearmies.ArmiesConfig;

/**
 * Primitive unit-handle to persistent MillVillager UUID association; no entity is retained.
 *
 * <p>Packed rows retain deterministic insertion/swap-removal order for persistence and cursors.
 * Two primitive open-addressed indices map unit handles and UUID pairs to those rows without
 * allocating wrappers or changing the serialized representation.</p>
 */
public final class PackedUnitMembership {
    private static final int MIN_GROWTH = 8;
    private static final int MIN_INDEX_CAPACITY = 16;
    private static final int MAX_MEMBERSHIPS = (1 << 20) - 1;

    private int size;
    private int structuralVersion;
    private final boolean indexed;
    private int[] unitHandles = new int[0];
    private long[] uuidMost = new long[0];
    private long[] uuidLeast = new long[0];

    /** Occupancy and value are combined as packed row + 1; zero means empty. */
    private int[] handleIndexRows = new int[0];
    private int[] handleIndexKeys = new int[0];
    private int[] uuidIndexRows = new int[0];
    private long[] uuidIndexMost = new long[0];
    private long[] uuidIndexLeast = new long[0];
    private int indexMaxFill;

    public PackedUnitMembership() {
        this(ArmiesConfig.MEMBERSHIP_PRIMITIVE_INDEX);
    }

    /** Explicit constructor for differential tests and isolated experimental fixtures. */
    public PackedUnitMembership(boolean indexed) {
        this.indexed = indexed;
    }

    public int size() {
        return size;
    }

    boolean usesPrimitiveIndex() {
        return indexed;
    }

    /**
     * Direct packed-row access for bounded server-thread systems. The row is deliberately not a
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

    /** Reserves rows and both primitive indices so later command batches do not grow storage. */
    public void reserve(int capacity) {
        if (capacity < 0 || capacity > MAX_MEMBERSHIPS) {
            throw new IllegalArgumentException(
                    "Membership capacity is outside 0.." + MAX_MEMBERSHIPS + ": " + capacity);
        }
        ensureRowCapacity(capacity);
        ensureIndexCapacity(capacity);
    }

    public void bind(int unitHandle, long mostSignificantBits, long leastSignificantBits) {
        if (unitHandle == 0) {
            throw new IllegalArgumentException("Cannot bind the zero unit handle");
        }

        int handleRow = findHandleRow(unitHandle);
        int uuidRow = findUuidRow(mostSignificantBits, leastSignificantBits);
        if (uuidRow >= 0 && uuidRow != handleRow) {
            throw new IllegalArgumentException("Villager UUID is already bound to another unit");
        }
        if (handleRow >= 0) {
            if (uuidMost[handleRow] == mostSignificantBits
                    && uuidLeast[handleRow] == leastSignificantBits) {
                return;
            }
            if (indexed) {
                removeUuidIndex(uuidMost[handleRow], uuidLeast[handleRow]);
            }
            uuidMost[handleRow] = mostSignificantBits;
            uuidLeast[handleRow] = leastSignificantBits;
            if (indexed) {
                putUuidIndex(mostSignificantBits, leastSignificantBits, handleRow);
            }
            return;
        }

        ensureRowCapacity(size + 1);
        ensureIndexCapacity(size + 1);
        int row = size;
        unitHandles[row] = unitHandle;
        uuidMost[row] = mostSignificantBits;
        uuidLeast[row] = leastSignificantBits;
        if (indexed) {
            putHandleIndex(unitHandle, row);
            putUuidIndex(mostSignificantBits, leastSignificantBits, row);
        }
        size++;
        structuralVersion++;
    }

    /** Returns the opaque unit handle for a villager UUID, or zero when it is not bound. */
    public int unitHandleForUuid(long mostSignificantBits, long leastSignificantBits) {
        int row = findUuidRow(mostSignificantBits, leastSignificantBits);
        return row < 0 ? 0 : unitHandles[row];
    }

    /** Removes one unit association with swap-remove and no allocation. */
    public boolean unbindUnit(int unitHandle) {
        int row = findHandleRow(unitHandle);
        if (row < 0) {
            return false;
        }
        removeAt(row);
        return true;
    }

    /** Removes one villager association with swap-remove and no allocation. */
    public boolean unbindUuid(long mostSignificantBits, long leastSignificantBits) {
        int row = findUuidRow(mostSignificantBits, leastSignificantBits);
        if (row < 0) {
            return false;
        }
        removeAt(row);
        return true;
    }

    public boolean read(int unitHandle, UuidBits destination) {
        int row = findHandleRow(unitHandle);
        if (row < 0) {
            return false;
        }
        destination.most = uuidMost[row];
        destination.least = uuidLeast[row];
        return true;
    }

    public UuidBits newUuidBits() {
        return new UuidBits();
    }

    public Cursor newCursor() {
        return new Cursor(this);
    }

    private void removeAt(int row) {
        int removedHandle = unitHandles[row];
        long removedMost = uuidMost[row];
        long removedLeast = uuidLeast[row];
        if (indexed) {
            removeHandleIndex(removedHandle);
            removeUuidIndex(removedMost, removedLeast);
        }

        int last = --size;
        if (row != last) {
            int movedHandle = unitHandles[last];
            long movedMost = uuidMost[last];
            long movedLeast = uuidLeast[last];
            unitHandles[row] = movedHandle;
            uuidMost[row] = movedMost;
            uuidLeast[row] = movedLeast;
            if (indexed) {
                updateHandleIndexRow(movedHandle, row);
                updateUuidIndexRow(movedMost, movedLeast, row);
            }
        }
        unitHandles[last] = 0;
        uuidMost[last] = 0L;
        uuidLeast[last] = 0L;
        structuralVersion++;
    }

    private int findHandleRow(int unitHandle) {
        if (!indexed) {
            return linearFindHandleRow(unitHandle);
        }
        if (unitHandle == 0 || handleIndexRows.length == 0) {
            return -1;
        }
        int mask = handleIndexRows.length - 1;
        int slot = mixHandle(unitHandle) & mask;
        while (handleIndexRows[slot] != 0) {
            if (handleIndexKeys[slot] == unitHandle) {
                return handleIndexRows[slot] - 1;
            }
            slot = slot + 1 & mask;
        }
        return -1;
    }

    private int findUuidRow(long most, long least) {
        if (!indexed) {
            return linearFindUuidRow(most, least);
        }
        if (uuidIndexRows.length == 0) {
            return -1;
        }
        int mask = uuidIndexRows.length - 1;
        int slot = mixUuid(most, least) & mask;
        while (uuidIndexRows[slot] != 0) {
            if (uuidIndexMost[slot] == most && uuidIndexLeast[slot] == least) {
                return uuidIndexRows[slot] - 1;
            }
            slot = slot + 1 & mask;
        }
        return -1;
    }

    private void putHandleIndex(int unitHandle, int row) {
        int mask = handleIndexRows.length - 1;
        int slot = mixHandle(unitHandle) & mask;
        while (handleIndexRows[slot] != 0) {
            if (handleIndexKeys[slot] == unitHandle) {
                handleIndexRows[slot] = row + 1;
                return;
            }
            slot = slot + 1 & mask;
        }
        handleIndexKeys[slot] = unitHandle;
        handleIndexRows[slot] = row + 1;
    }

    private void putUuidIndex(long most, long least, int row) {
        int mask = uuidIndexRows.length - 1;
        int slot = mixUuid(most, least) & mask;
        while (uuidIndexRows[slot] != 0) {
            if (uuidIndexMost[slot] == most && uuidIndexLeast[slot] == least) {
                uuidIndexRows[slot] = row + 1;
                return;
            }
            slot = slot + 1 & mask;
        }
        uuidIndexMost[slot] = most;
        uuidIndexLeast[slot] = least;
        uuidIndexRows[slot] = row + 1;
    }

    private void updateHandleIndexRow(int unitHandle, int row) {
        int mask = handleIndexRows.length - 1;
        int slot = mixHandle(unitHandle) & mask;
        while (handleIndexRows[slot] != 0) {
            if (handleIndexKeys[slot] == unitHandle) {
                handleIndexRows[slot] = row + 1;
                return;
            }
            slot = slot + 1 & mask;
        }
        throw new AssertionError("Missing handle index for moved membership row");
    }

    private void updateUuidIndexRow(long most, long least, int row) {
        int mask = uuidIndexRows.length - 1;
        int slot = mixUuid(most, least) & mask;
        while (uuidIndexRows[slot] != 0) {
            if (uuidIndexMost[slot] == most && uuidIndexLeast[slot] == least) {
                uuidIndexRows[slot] = row + 1;
                return;
            }
            slot = slot + 1 & mask;
        }
        throw new AssertionError("Missing UUID index for moved membership row");
    }

    private void removeHandleIndex(int unitHandle) {
        int mask = handleIndexRows.length - 1;
        int slot = mixHandle(unitHandle) & mask;
        while (handleIndexRows[slot] != 0) {
            if (handleIndexKeys[slot] == unitHandle) {
                shiftHandleIndex(slot);
                return;
            }
            slot = slot + 1 & mask;
        }
        throw new AssertionError("Missing handle index for removed membership row");
    }

    private void removeUuidIndex(long most, long least) {
        int mask = uuidIndexRows.length - 1;
        int slot = mixUuid(most, least) & mask;
        while (uuidIndexRows[slot] != 0) {
            if (uuidIndexMost[slot] == most && uuidIndexLeast[slot] == least) {
                shiftUuidIndex(slot);
                return;
            }
            slot = slot + 1 & mask;
        }
        throw new AssertionError("Missing UUID index for removed membership row");
    }

    private void shiftHandleIndex(int hole) {
        int mask = handleIndexRows.length - 1;
        int last = hole;
        for (;;) {
            int slot = last + 1 & mask;
            while (handleIndexRows[slot] != 0) {
                int home = mixHandle(handleIndexKeys[slot]) & mask;
                if (last <= slot ? last >= home || home > slot : last >= home && home > slot) {
                    break;
                }
                slot = slot + 1 & mask;
            }
            if (handleIndexRows[slot] == 0) {
                handleIndexKeys[last] = 0;
                handleIndexRows[last] = 0;
                return;
            }
            handleIndexKeys[last] = handleIndexKeys[slot];
            handleIndexRows[last] = handleIndexRows[slot];
            last = slot;
        }
    }

    private void shiftUuidIndex(int hole) {
        int mask = uuidIndexRows.length - 1;
        int last = hole;
        for (;;) {
            int slot = last + 1 & mask;
            while (uuidIndexRows[slot] != 0) {
                int home = mixUuid(uuidIndexMost[slot], uuidIndexLeast[slot]) & mask;
                if (last <= slot ? last >= home || home > slot : last >= home && home > slot) {
                    break;
                }
                slot = slot + 1 & mask;
            }
            if (uuidIndexRows[slot] == 0) {
                uuidIndexMost[last] = 0L;
                uuidIndexLeast[last] = 0L;
                uuidIndexRows[last] = 0;
                return;
            }
            uuidIndexMost[last] = uuidIndexMost[slot];
            uuidIndexLeast[last] = uuidIndexLeast[slot];
            uuidIndexRows[last] = uuidIndexRows[slot];
            last = slot;
        }
    }

    private void ensureRowCapacity(int required) {
        if (required > MAX_MEMBERSHIPS) {
            throw new IllegalStateException("Membership handle space exhausted at " + MAX_MEMBERSHIPS);
        }
        if (required <= unitHandles.length) {
            return;
        }
        int current = unitHandles.length;
        int grown = current < MIN_GROWTH ? MIN_GROWTH : current + (current >>> 1);
        int capacity = Math.min(MAX_MEMBERSHIPS, Math.max(required, grown));
        unitHandles = Arrays.copyOf(unitHandles, capacity);
        uuidMost = Arrays.copyOf(uuidMost, capacity);
        uuidLeast = Arrays.copyOf(uuidLeast, capacity);
    }

    private void ensureIndexCapacity(int required) {
        if (!indexed) {
            return;
        }
        if (required <= indexMaxFill) {
            return;
        }
        int capacity = MIN_INDEX_CAPACITY;
        long minimum = Math.max((long) MIN_INDEX_CAPACITY, (long) required * 2L);
        while (capacity < minimum) {
            if (capacity >= (1 << 30)) {
                throw new IllegalArgumentException("Membership index capacity is too large: " + required);
            }
            capacity <<= 1;
        }
        rehash(capacity);
    }

    private void rehash(int capacity) {
        handleIndexKeys = new int[capacity];
        handleIndexRows = new int[capacity];
        uuidIndexMost = new long[capacity];
        uuidIndexLeast = new long[capacity];
        uuidIndexRows = new int[capacity];
        indexMaxFill = capacity >>> 1;
        for (int row = 0; row < size; row++) {
            putHandleIndex(unitHandles[row], row);
            putUuidIndex(uuidMost[row], uuidLeast[row], row);
        }
    }

    private void checkRow(int row) {
        if (row < 0 || row >= size) {
            throw new IndexOutOfBoundsException("Membership row " + row + " of " + size);
        }
    }

    private int linearFindHandleRow(int unitHandle) {
        if (unitHandle == 0) {
            return -1;
        }
        for (int row = 0; row < size; row++) {
            if (unitHandles[row] == unitHandle) {
                return row;
            }
        }
        return -1;
    }

    private int linearFindUuidRow(long most, long least) {
        for (int row = 0; row < size; row++) {
            if (uuidMost[row] == most && uuidLeast[row] == least) {
                return row;
            }
        }
        return -1;
    }

    private static int mixHandle(int value) {
        int mixed = value;
        mixed ^= mixed >>> 16;
        mixed *= 0x7FEB352D;
        mixed ^= mixed >>> 15;
        mixed *= 0x846CA68B;
        return mixed ^ mixed >>> 16;
    }

    private static int mixUuid(long most, long least) {
        long mixed = most ^ Long.rotateLeft(least, 29);
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        return (int) (mixed ^ mixed >>> 32);
    }

    /** Allocation-free invariant check intended for self-tests. */
    void checkInvariants() {
        if (size < 0 || size > unitHandles.length) {
            throw new AssertionError("Membership size is outside row storage");
        }
        for (int row = 0; row < size; row++) {
            int handle = unitHandles[row];
            if (handle == 0 || findHandleRow(handle) != row) {
                throw new AssertionError("Handle index mismatch at row " + row);
            }
            if (findUuidRow(uuidMost[row], uuidLeast[row]) != row) {
                throw new AssertionError("UUID index mismatch at row " + row);
            }
        }
    }

    public static final class UuidBits {
        private long most;
        private long least;

        private UuidBits() {}

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

        public int unitHandle() {
            checkActive();
            return owner.unitHandles[row];
        }

        public long uuidMost() {
            checkActive();
            return owner.uuidMost[row];
        }

        public long uuidLeast() {
            checkActive();
            return owner.uuidLeast[row];
        }

        private void checkActive() {
            if (expectedStructuralVersion != owner.structuralVersion || row < 0) {
                throw new IllegalStateException("Membership cursor is not on a valid row");
            }
        }
    }
}
