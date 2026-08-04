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

    public static final byte TROOP_CLASS_UNCLASSIFIED = 0;
    public static final byte TROOP_CLASS_LEVY = 1;
    public static final byte TROOP_CLASS_REGULAR = 2;
    public static final byte TROOP_CLASS_NOBLE = 3;
    public static final int MAX_UNPAID_CYCLES = 127;

    private static final int MIN_CAPACITY = 16;
    private static final float INDEX_LOAD = 0.65F;

    private int size;
    private int structuralVersion;
    private long revision;
    private int[] unitHandles = new int[0];
    private int[] roleTokens = new int[0];
    private int[] rankTokens = new int[0];
    private int[] loadoutTokens = new int[0];
    private byte[] troopClasses = new byte[0];
    private byte[] unpaidCycles = new byte[0];
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
        return (long) unitHandles.length * (Integer.BYTES * 4L + Byte.BYTES * 3L)
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
        byte troopClass = row < 0 ? TROOP_CLASS_LEVY : troopClasses[row];
        return assign(unitHandle, roleToken, rankToken, loadoutToken, troopClass);
    }

    /** Inserts or updates role data together with an explicit persisted military class. */
    public boolean assign(
            int unitHandle,
            int roleToken,
            int rankToken,
            int loadoutToken,
            byte troopClass) {
        requireHandle(unitHandle);
        troopClass = normalizeTroopClass(troopClass);
        int row = findRow(unitHandle);
        if (row >= 0) {
            if (roleTokens[row] == roleToken
                    && rankTokens[row] == rankToken
                    && loadoutTokens[row] == loadoutToken
                    && troopClasses[row] == troopClass) {
                return false;
            }
            roleTokens[row] = roleToken;
            rankTokens[row] = rankToken;
            loadoutTokens[row] = loadoutToken;
            troopClasses[row] = troopClass;
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
        troopClasses[row] = troopClass;
        unpaidCycles[row] = 0;
        flags[row] = FLAG_EQUIPMENT_DIRTY;
        putIndex(unitHandle, row);
        structuralVersion++;
        revision++;
        return true;
    }

    /** Updates only the loadout token for an existing role assignment. */
    public boolean assignLoadoutOnly(int unitHandle, int loadoutToken) {
        requireHandle(unitHandle);
        int row = findRow(unitHandle);
        if (row < 0 || loadoutTokens[row] == loadoutToken) {
            return false;
        }
        loadoutTokens[row] = loadoutToken;
        flags[row] |= FLAG_EQUIPMENT_DIRTY;
        revision++;
        return true;
    }

    public boolean assignTroopClass(int unitHandle, byte troopClass) {
        requireHandle(unitHandle);
        troopClass = normalizeTroopClass(troopClass);
        int row = findRow(unitHandle);
        if (row < 0) {
            return assign(unitHandle, 0, 0, 0, troopClass);
        }
        if (troopClasses[row] == troopClass) return false;
        troopClasses[row] = troopClass;
        revision++;
        return true;
    }

    public boolean recordUpkeepPaid(int unitHandle) {
        int row = findRow(unitHandle);
        if (row < 0 || unpaidCycles[row] == 0) return false;
        unpaidCycles[row] = 0;
        revision++;
        return true;
    }

    public int recordUpkeepMissed(int unitHandle) {
        int row = findRow(unitHandle);
        if (row < 0) return -1;
        int current = Byte.toUnsignedInt(unpaidCycles[row]);
        if (current < MAX_UNPAID_CYCLES) {
            unpaidCycles[row] = (byte) (current + 1);
            revision++;
            return current + 1;
        }
        return current;
    }

    public byte troopClass(int unitHandle) {
        int row = findRow(unitHandle);
        return row < 0 ? TROOP_CLASS_UNCLASSIFIED : troopClasses[row];
    }

    public int unpaidCycles(int unitHandle) {
        int row = findRow(unitHandle);
        return row < 0 ? 0 : Byte.toUnsignedInt(unpaidCycles[row]);
    }

    public void restoreRow(
            int unitHandle,
            int roleToken,
            int rankToken,
            int loadoutToken,
            byte flags) {
        restoreRow(unitHandle, roleToken, rankToken, loadoutToken, TROOP_CLASS_LEVY, 0, flags);
    }

    public void restoreRow(
            int unitHandle,
            int roleToken,
            int rankToken,
            int loadoutToken,
            byte troopClass,
            int unpaidCycleCount,
            byte flags) {
        if (revision != 0L) {
            throw new IllegalStateException("Persisted unit role rows must be restored before runtime mutations");
        }
        requireHandle(unitHandle);
        byte normalizedFlags = normalizeFlags(flags);
        byte normalizedTroopClass = normalizeTroopClass(troopClass);
        if (unpaidCycleCount < 0 || unpaidCycleCount > MAX_UNPAID_CYCLES) {
            throw new IllegalArgumentException("Invalid unpaid upkeep cycle count " + unpaidCycleCount);
        }
        if (findRow(unitHandle) >= 0) {
            throw new IllegalArgumentException("Duplicate persisted unit role handle " + unitHandle);
        }
        ensureDenseCapacity(size + 1);
        ensureIndexCapacity(size + 1);
        int row = size++;
        unitHandles[row] = unitHandle;
        roleTokens[row] = roleToken;
        rankTokens[row] = rankToken;
        loadoutTokens[row] = loadoutToken;
        troopClasses[row] = normalizedTroopClass;
        unpaidCycles[row] = (byte) unpaidCycleCount;
        this.flags[row] = normalizedFlags;
        putIndex(unitHandle, row);
        structuralVersion++;
    }

    public void restoreRevision(long restoredRevision) {
        if (restoredRevision < 0L) {
            throw new IllegalArgumentException("Role revision must be non-negative");
        }
        if (revision != 0L) {
            throw new IllegalStateException("Unit role revision can only be restored once during cold load");
        }
        if (restoredRevision < size) {
            throw new IllegalArgumentException(
                    "Role revision " + restoredRevision + " is below restored row count " + size);
        }
        revision = restoredRevision;
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
            troopClasses[row] = troopClasses[last];
            unpaidCycles[row] = unpaidCycles[last];
            flags[row] = flags[last];
            replaceIndexRow(movedHandle, row);
        }
        unitHandles[last] = 0;
        roleTokens[last] = 0;
        rankTokens[last] = 0;
        loadoutTokens[last] = 0;
        troopClasses[last] = 0;
        unpaidCycles[last] = 0;
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
        destination.troopClass = troopClasses[row];
        destination.unpaidCycles = Byte.toUnsignedInt(unpaidCycles[row]);
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
        troopClasses = Arrays.copyOf(troopClasses, capacity);
        unpaidCycles = Arrays.copyOf(unpaidCycles, capacity);
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

    private static byte normalizeFlags(byte flags) {
        byte normalized = (byte) (flags & FLAG_EQUIPMENT_DIRTY);
        if ((byte) (normalized & 0xFF) != (byte) (flags & 0xFF)) {
            throw new IllegalArgumentException("Unknown unit role flag bits: " + flags);
        }
        return normalized;
    }

    private static byte normalizeTroopClass(byte troopClass) {
        if (troopClass < TROOP_CLASS_UNCLASSIFIED || troopClass > TROOP_CLASS_NOBLE) {
            throw new IllegalArgumentException("Unknown troop class " + troopClass);
        }
        return troopClass;
    }

    public static final class View {
        private int unitHandle;
        private int roleToken;
        private int rankToken;
        private int loadoutToken;
        private byte troopClass;
        private int unpaidCycles;
        private byte flags;

        private View() {}

        public int unitHandle() { return unitHandle; }
        public int roleToken() { return roleToken; }
        public int rankToken() { return rankToken; }
        public int loadoutToken() { return loadoutToken; }
        public byte troopClass() { return troopClass; }
        public int unpaidCycles() { return unpaidCycles; }
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
        public byte troopClass() { checkActive(); return owner.troopClasses[row]; }
        public int unpaidCycles() { checkActive(); return Byte.toUnsignedInt(owner.unpaidCycles[row]); }
        public byte flags() { checkActive(); return owner.flags[row]; }

        private void checkActive() {
            if (row < 0 || expectedStructuralVersion != owner.structuralVersion) {
                throw new IllegalStateException("Unit role cursor is not on a valid row");
            }
        }
    }
}
