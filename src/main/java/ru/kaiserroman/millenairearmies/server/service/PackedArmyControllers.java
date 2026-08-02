package ru.kaiserroman.millenairearmies.server.service;

import java.util.Arrays;

/**
 * Persistable primitive controller columns keyed by the raw 32-bit ECS army handle.
 *
 * <p>This store is deliberately separate from command authority and Minecraft player objects.
 * Persistence can iterate it with one reusable cursor and rebuild it through {@link #put} without
 * allocating UUID instances or boxed handles.</p>
 */
public final class PackedArmyControllers {
    private int size;
    private int structuralVersion;
    private int[] armyHandles = new int[0];
    private long[] ownerMost = new long[0];
    private long[] ownerLeast = new long[0];
    private byte[] ownerPresent = new byte[0];

    public PackedArmyControllers() {}

    public PackedArmyControllers(int capacity) {
        reserve(capacity);
    }

    public void reserve(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Negative controller capacity");
        }
        if (capacity <= armyHandles.length) {
            return;
        }
        armyHandles = Arrays.copyOf(armyHandles, capacity);
        ownerMost = Arrays.copyOf(ownerMost, capacity);
        ownerLeast = Arrays.copyOf(ownerLeast, capacity);
        ownerPresent = Arrays.copyOf(ownerPresent, capacity);
    }

    public int size() {
        return size;
    }

    /** Adds or replaces a controller row. Army handle zero is reserved and rejected. */
    public void put(int armyHandle, long uuidMost, long uuidLeast, boolean present) {
        if (armyHandle == 0) {
            throw new IllegalArgumentException("Zero is not a valid army handle");
        }
        int index = indexOf(armyHandle);
        if (index < 0) {
            ensureCapacity(size + 1);
            index = size++;
            armyHandles[index] = armyHandle;
            structuralVersion++;
        }
        ownerMost[index] = uuidMost;
        ownerLeast[index] = uuidLeast;
        ownerPresent[index] = present ? (byte) 1 : (byte) 0;
    }

    public boolean remove(int armyHandle) {
        int index = indexOf(armyHandle);
        if (index < 0) {
            return false;
        }
        int last = --size;
        if (index != last) {
            armyHandles[index] = armyHandles[last];
            ownerMost[index] = ownerMost[last];
            ownerLeast[index] = ownerLeast[last];
            ownerPresent[index] = ownerPresent[last];
        }
        armyHandles[last] = 0;
        ownerMost[last] = 0L;
        ownerLeast[last] = 0L;
        ownerPresent[last] = 0;
        structuralVersion++;
        return true;
    }

    public boolean matches(int armyHandle, long uuidMost, long uuidLeast) {
        int index = indexOf(armyHandle);
        return index >= 0
                && ownerPresent[index] != 0
                && ownerMost[index] == uuidMost
                && ownerLeast[index] == uuidLeast;
    }

    public boolean hasController(int armyHandle) {
        int index = indexOf(armyHandle);
        return index >= 0 && ownerPresent[index] != 0;
    }

    public long uuidMost(int armyHandle) {
        int index = indexOf(armyHandle);
        if (index < 0 || ownerPresent[index] == 0) {
            throw new IllegalArgumentException("Army has no player controller: "
                    + Integer.toUnsignedString(armyHandle));
        }
        return ownerMost[index];
    }

    public long uuidLeast(int armyHandle) {
        int index = indexOf(armyHandle);
        if (index < 0 || ownerPresent[index] == 0) {
            throw new IllegalArgumentException("Army has no player controller: "
                    + Integer.toUnsignedString(armyHandle));
        }
        return ownerLeast[index];
    }

    /** Same non-empty controller identity; unowned armies are not implicitly allied. */
    public boolean sameController(int firstArmyHandle, int secondArmyHandle) {
        int first = indexOf(firstArmyHandle);
        int second = indexOf(secondArmyHandle);
        return first >= 0
                && second >= 0
                && ownerPresent[first] != 0
                && ownerPresent[second] != 0
                && ownerMost[first] == ownerMost[second]
                && ownerLeast[first] == ownerLeast[second];
    }

    public void clear() {
        Arrays.fill(armyHandles, 0, size, 0);
        Arrays.fill(ownerMost, 0, size, 0L);
        Arrays.fill(ownerLeast, 0, size, 0L);
        Arrays.fill(ownerPresent, 0, size, (byte) 0);
        if (size != 0) {
            size = 0;
            structuralVersion++;
        }
    }

    public Cursor newCursor() {
        return new Cursor(this);
    }

    private int indexOf(int armyHandle) {
        for (int i = 0; i < size; i++) {
            if (armyHandles[i] == armyHandle) {
                return i;
            }
        }
        return -1;
    }

    private void ensureCapacity(int required) {
        if (required <= armyHandles.length) {
            return;
        }
        int grown = armyHandles.length < 4 ? 4 : armyHandles.length + (armyHandles.length >>> 1);
        reserve(Math.max(grown, required));
    }

    public static final class Cursor {
        private final PackedArmyControllers owner;
        private int expectedVersion;
        private int next;
        private int index = -1;

        private Cursor(PackedArmyControllers owner) {
            this.owner = owner;
            reset();
        }

        public Cursor reset() {
            expectedVersion = owner.structuralVersion;
            next = 0;
            index = -1;
            return this;
        }

        public boolean advance() {
            checkVersion();
            if (next == owner.size) {
                index = -1;
                return false;
            }
            index = next++;
            return true;
        }

        public int armyHandle() {
            checkActive();
            return owner.armyHandles[index];
        }

        public long uuidMost() {
            checkActive();
            return owner.ownerMost[index];
        }

        public long uuidLeast() {
            checkActive();
            return owner.ownerLeast[index];
        }

        public boolean hasController() {
            checkActive();
            return owner.ownerPresent[index] != 0;
        }

        private void checkActive() {
            checkVersion();
            if (index < 0) {
                throw new IllegalStateException("Controller cursor is not on a row");
            }
        }

        private void checkVersion() {
            if (expectedVersion != owner.structuralVersion) {
                throw new IllegalStateException("Controller cursor invalidated by a structural change");
            }
        }
    }
}
