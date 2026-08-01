package ru.kaiserroman.millenairearmies.server.unit;

import java.util.Arrays;

/**
 * Allocation-free hot store for the strategic descriptors assigned to army units.
 *
 * <p>The resource-location keys live in the reload-time catalog. Runtime rows contain only the
 * opaque ECS unit handle and three deterministic integer tokens. The primitive open-addressed
 * index makes descriptor reads independent of army size.</p>
 */
public final class PackedUnitRoleState {
    public static final byte FLAG_EQUIPMENT_DIRTY = 1;

    private static final int MIN_CAPACITY = 16;
    private static final float INDEX_LOAD = 0.65F;

    private int size;
    private int structuralVersion;
    private long revision;
    private int[] unitHandles = new int[0];
    private int[] roleTokens = new int[0];
    private int[] rankTokens = new int[0];
    private int[] loadoutTokens = new int[0];
    private byte[] flags = new byte[0];

    private int[] indexKeys = new int[0];
    private int[] indexRowsPlusOne = new int[0];
    private int indexMask;
    private int indexMaxFill;

    public int size() {
        return size;
    }

    public long revision() {
        return revision;
    }

    /** Primitive array payload, excluding small JVM headers and the service/catalog objects. */
    public long estimatedPrimitiveBytes() {
        return (long) unitHandles.length * (Integer.BYTES * 4L + Byte.BYTES)
                + (long) indexKeys.length * Integer.BYTES * 2L;
    }

    /** Capacity preparation is a cold lifecycle operation. */
    public void reserve(int expectedUnits) {
        if (expectedUnits < 0) {
            throw new IllegalArgumentException("Expected unit count must be non-negative");
        }
        ensureDenseCapacity(expectedUnits);
        int indexCapacity = tableSize(expectedUnits);
        if (indexCapacity > indexKeys.length) {
            rebuildIndex(indexCapacity);
        }
    }

    /**
     * Inserts or updates one row. Token zero means that the role/catalog default should be used.
     * Returns true only if data changed.
     */
    public boolean assign(int unitHandle, int roleToken, int rankToken, int loadoutToken) {
        requireHandle(unitHandle);
        int row = findRow(unitHandle);
        if (row >= 0) {
            if (roleTokens[row] == roleToken
                    && rankTokens[row] == rankToken
                    && loadoutTokens[row] == loadoutToken) {
                return false;
            }
            roleTokens[row] = roleToken;
            rankTokens[row] = rankToken;
            loadoutTokens[row] = loadoutToken;
            flags[row] |= FLAG_EQUIPMENT_DIRTY;
            revision++;
            return true;
        }

        ensureDenseCapacity(size + 1);
        ensureIndexCapacity(size + 1);
        row = size++;
        unitHandles[row] = unitHandle;
        roleTokens[row] = roleToken;
        rankTokens[row] = rankToken;
        loadoutTokens[row] = loadoutToken;
        flags[row] = FLAG_EQUIPMENT_DIRTY;
        putIndex(unitHandle, row);
        structuralVersion++;
        revision++;
        return true;
    }

    public boolean remove(int unitHandle) {
        int row = findRow(unitHandle);
        if (row < 0) {
            return false;
        }
        removeIndex(unitHandle);
        int last = --size;
        if (row != last) {
            int movedHandle = unitHandles[last];
            unitHandles[row] = movedHandle;
            roleTokens[row] = roleTokens[last];
            rankTokens[row] = rankTokens[last];
            loadoutTokens[row] = loadoutTokens[last];
            flags[row] = flags[last];
            replaceIndexRow(movedHandle, row);
        }
        unitHandles[last] = 0;
        roleTokens[last] = 0;
        rankTokens[last] = 0;
        loadoutTokens[last] = 0;
        flags[last] = 0;
        structuralVersion++;
        revision++;
        return true;
    }

    public boolean read(int unitHandle, View destination) {
        int row = findRow(unitHandle);
        if (row < 0) {
            return false;
        }
        destination.unitHandle = unitHandles[row];
        destination.roleToken = roleTokens[row];
        destination.rankToken = rankTokens[row];
        destination.loadoutToken = loadoutTokens[row];
        destination.flags = flags[row];
        return true;
    }

    public boolean isEquipmentDirty(int unitHandle) {
        int row = findRow(unitHandle);
        return row >= 0 && (flags[row] & FLAG_EQUIPMENT_DIRTY) != 0;
    }

    public boolean markEquipmentDirty(int unitHandle) {
        int row = findRow(unitHandle);
        if (row < 0 || (flags[row] & FLAG_EQUIPMENT_DIRTY) != 0) {
            return false;
        }
        flags[row] |= FLAG_EQUIPMENT_DIRTY;
        revision++;
        return true;
    }

    public boolean markEquipmentProjected(int unitHandle) {
        int row = findRow(unitHandle);
        if (row < 0 || (flags[row] & FLAG_EQUIPMENT_DIRTY) == 0) {
            return false;
        }
        flags[row] &= (byte) ~FLAG_EQUIPMENT_DIRTY;
        revision++;
        return true;
    }

    public void markAllEquipmentDirty() {
        boolean changed = false;
        for (int row = 0; row < size; row++) {
            if ((flags[row] & FLAG_EQUIPMENT_DIRTY) == 0) {
                flags[row] |= FLAG_EQUIPMENT_DIRTY;
                changed = true;
            }
        }
        if (changed) {
            revision++;
        }
    }

    public View newView() {
        return new View();
    }

    public Cursor newCursor() {
        return new Cursor(this);
    }

    private int findRow(int unitHandle) {
        if (unitHandle == 0 || indexKeys.length == 0) {
            return -1;
        }
        int position = mix(unitHandle) & indexMask;
        int key;
        while ((key = indexKeys[position]) != 0) {
            if (key == unitHandle) {
                return indexRowsPlusOne[position] - 1;
            }
            position = (position + 1) & indexMask;
        }
        return -1;
    }

    private void ensureDenseCapacity(int required) {
        if (required <= unitHandles.length) {
            return;
        }
        int capacity = Math.max(required, unitHandles.length == 0 ? MIN_CAPACITY : unitHandles.length << 1);
        unitHandles = Arrays.copyOf(unitHandles, capacity);
        roleTokens = Arrays.copyOf(roleTokens, capacity);
        rankTokens = Arrays.copyOf(rankTokens, capacity);
        loadoutTokens = Arrays.copyOf(loadoutTokens, capacity);
        flags = Arrays.copyOf(flags, capacity);
    }

    private void ensureIndexCapacity(int required) {
        if (indexKeys.length == 0) {
            rebuildIndex(tableSize(Math.max(required, MIN_CAPACITY)));
        } else if (required > indexMaxFill) {
            rebuildIndex(indexKeys.length << 1);
        }
    }

    private void rebuildIndex(int capacity) {
        int[] oldHandles = unitHandles;
        indexKeys = new int[Math.max(MIN_CAPACITY, capacity)];
        indexRowsPlusOne = new int[indexKeys.length];
        indexMask = indexKeys.length - 1;
        indexMaxFill = Math.min(indexKeys.length - 1, (int) (indexKeys.length * INDEX_LOAD));
        for (int row = 0; row < size; row++) {
            putIndex(oldHandles[row], row);
        }
    }

    private void putIndex(int unitHandle, int row) {
        int position = mix(unitHandle) & indexMask;
        while (indexKeys[position] != 0) {
            position = (position + 1) & indexMask;
        }
        indexKeys[position] = unitHandle;
        indexRowsPlusOne[position] = row + 1;
    }

    private void replaceIndexRow(int unitHandle, int newRow) {
        int position = mix(unitHandle) & indexMask;
        while (indexKeys[position] != unitHandle) {
            if (indexKeys[position] == 0) {
                throw new IllegalStateException("Packed role index lost unit " + Integer.toUnsignedString(unitHandle));
            }
            position = (position + 1) & indexMask;
        }
        indexRowsPlusOne[position] = newRow + 1;
    }

    /** Back-shift deletion keeps lookups tombstone-free. */
    private void removeIndex(int unitHandle) {
        int last;
        int position = mix(unitHandle) & indexMask;
        while (indexKeys[position] != unitHandle) {
            position = (position + 1) & indexMask;
        }
        for (;;) {
            position = ((last = position) + 1) & indexMask;
            int key;
            for (;;) {
                key = indexKeys[position];
                if (key == 0) {
                    indexKeys[last] = 0;
                    indexRowsPlusOne[last] = 0;
                    return;
                }
                int slot = mix(key) & indexMask;
                if (last <= position
                        ? last >= slot || slot > position
                        : last >= slot && slot > position) {
                    break;
                }
                position = (position + 1) & indexMask;
            }
            indexKeys[last] = key;
            indexRowsPlusOne[last] = indexRowsPlusOne[position];
        }
    }

    private static int tableSize(int expected) {
        int required = Math.max(MIN_CAPACITY, (int) Math.ceil(expected / INDEX_LOAD));
        int capacity = 1;
        while (capacity < required) {
            capacity <<= 1;
            if (capacity <= 0) {
                throw new IllegalArgumentException("Unit role capacity is too large");
            }
        }
        return capacity;
    }

    private static int mix(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        return value ^ value >>> 16;
    }

    private static void requireHandle(int unitHandle) {
        if (unitHandle == 0) {
            throw new IllegalArgumentException("Zero is not a valid ECS unit handle");
        }
    }

    public static final class View {
        private int unitHandle;
        private int roleToken;
        private int rankToken;
        private int loadoutToken;
        private byte flags;

        private View() {}

        public int unitHandle() { return unitHandle; }
        public int roleToken() { return roleToken; }
        public int rankToken() { return rankToken; }
        public int loadoutToken() { return loadoutToken; }
        public byte flags() { return flags; }
    }

    public static final class Cursor {
        private final PackedUnitRoleState owner;
        private int expectedStructuralVersion;
        private int nextRow;
        private int row = -1;

        private Cursor(PackedUnitRoleState owner) {
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
                throw new IllegalStateException("Unit role cursor invalidated by structural change; reset it");
            }
            if (nextRow == owner.size) {
                row = -1;
                return false;
            }
            row = nextRow++;
            return true;
        }

        public int unitHandle() { checkActive(); return owner.unitHandles[row]; }
        public int roleToken() { checkActive(); return owner.roleTokens[row]; }
        public int rankToken() { checkActive(); return owner.rankTokens[row]; }
        public int loadoutToken() { checkActive(); return owner.loadoutTokens[row]; }
        public byte flags() { checkActive(); return owner.flags[row]; }

        private void checkActive() {
            if (row < 0 || expectedStructuralVersion != owner.structuralVersion) {
                throw new IllegalStateException("Unit role cursor is not on a valid row");
            }
        }
    }
}
