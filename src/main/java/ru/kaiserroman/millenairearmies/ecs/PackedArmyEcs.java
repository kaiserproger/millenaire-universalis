package ru.kaiserroman.millenairearmies.ecs;

import java.util.Arrays;

/**
 * Single-owner, packed structure-of-arrays storage for the hot army simulation.
 *
 * <p>The store owns every backing array and never exposes one. It is deliberately
 * thread-confined: the simulation thread is the sole writer and cursor owner.
 * Call {@link #reserveArmies(int)} and {@link #reserveUnits(int)} during world
 * loading, and create cursors/snapshots once during system setup. After that,
 * successful reads, writes, iteration, assignment changes, creation within the
 * reserved capacity, and swap-removal perform no heap allocation. Array growth
 * and exceptional error paths are the only allocating paths.</p>
 *
 * <p>Handles contain a 20-bit slot and a 12-bit generation. Handle {@code 0} is
 * reserved for "unassigned". A removed slot is reused with a new generation so
 * an ordinary stale handle cannot address its replacement. Generation rollover
 * is possible after 4095 reuses of the same slot and must not be treated as a
 * permanent external identity.</p>
 */
public final class PackedArmyEcs {
    public static final int NO_ARMY = 0;

    private static final int SLOT_BITS = 20;
    private static final int SLOT_MASK = (1 << SLOT_BITS) - 1;
    private static final int GENERATION_MASK = (1 << (Integer.SIZE - SLOT_BITS)) - 1;
    private static final int MAX_SLOTS = 1 << SLOT_BITS;
    private static final int MIN_GROWTH = 4;

    private int armySize;
    private int armyNextSlot;
    private int armyFreeCount;
    private int armyStructuralVersion;
    private int[] armyHandles = new int[0];
    private int[] armyFaction = new int[0];
    private int[] armyOrder = new int[0];
    private int[] armyState = new int[0];
    private int[] armyUnitCount = new int[0];
    private long[] armyPackedTargetPos = new long[0];
    private int[] armySlotToDense = new int[0];
    private int[] armySlotGeneration = new int[0];
    private int[] armyFreeSlots = new int[0];

    private int unitSize;
    private int unitNextSlot;
    private int unitFreeCount;
    private int unitStructuralVersion;
    private int[] unitHandles = new int[0];
    private int[] unitArmy = new int[0];
    private int[] unitOrder = new int[0];
    private int[] unitState = new int[0];
    private long[] unitPackedPos = new long[0];
    private int[] unitSlotToDense = new int[0];
    private int[] unitSlotGeneration = new int[0];
    private int[] unitFreeSlots = new int[0];

    public PackedArmyEcs() {
    }

    public PackedArmyEcs(int expectedArmies, int expectedUnits) {
        reserveArmies(expectedArmies);
        reserveUnits(expectedUnits);
    }

    /** Reserves both dense rows and handle slots. Use this before ticking. */
    public void reserveArmies(int capacity) {
        checkRequestedCapacity(capacity);
        ensureArmyDenseCapacity(capacity);
        ensureArmySlotCapacity(capacity);
    }

    /** Reserves both dense rows and handle slots. Use this before ticking. */
    public void reserveUnits(int capacity) {
        checkRequestedCapacity(capacity);
        ensureUnitDenseCapacity(capacity);
        ensureUnitSlotCapacity(capacity);
    }

    public int armySize() {
        return armySize;
    }

    public int unitSize() {
        return unitSize;
    }

    public int createArmy(int factionId, int order, int state, long packedTargetPos) {
        ensureArmyDenseCapacity(armySize + 1);
        int slot = acquireArmySlot();
        int handle = makeHandle(slot, armySlotGeneration[slot]);
        int dense = armySize++;

        armyHandles[dense] = handle;
        armyFaction[dense] = factionId;
        armyOrder[dense] = order;
        armyState[dense] = state;
        armyUnitCount[dense] = 0;
        armyPackedTargetPos[dense] = packedTargetPos;
        armySlotToDense[slot] = dense;
        armyStructuralVersion++;
        return handle;
    }

    public int createUnit(int armyHandle, int order, int state, long packedPos) {
        if (armyHandle != NO_ARMY && !isArmyAlive(armyHandle)) {
            throw new IllegalArgumentException("Unknown army handle: " + armyHandle);
        }

        ensureUnitDenseCapacity(unitSize + 1);
        int slot = acquireUnitSlot();
        int handle = makeHandle(slot, unitSlotGeneration[slot]);
        int dense = unitSize++;

        unitHandles[dense] = handle;
        unitArmy[dense] = armyHandle;
        unitOrder[dense] = order;
        unitState[dense] = state;
        unitPackedPos[dense] = packedPos;
        unitSlotToDense[slot] = dense;
        if (armyHandle != NO_ARMY) {
            armyUnitCount[armyDenseOrThrow(armyHandle)]++;
        }
        unitStructuralVersion++;
        return handle;
    }

    /**
     * Removes the army with swap-remove and leaves its units alive but unassigned.
     * The scan is allocation-free and linear in the number of units.
     */
    public boolean removeArmy(int handle) {
        int dense = armyDense(handle);
        if (dense < 0) {
            return false;
        }

        for (int i = 0; i < unitSize; i++) {
            if (unitArmy[i] == handle) {
                unitArmy[i] = NO_ARMY;
            }
        }
        removeArmyDense(dense);
        return true;
    }

    public boolean removeUnit(int handle) {
        int dense = unitDense(handle);
        if (dense < 0) {
            return false;
        }

        int armyHandle = unitArmy[dense];
        if (armyHandle != NO_ARMY) {
            armyUnitCount[armyDenseOrThrow(armyHandle)]--;
        }
        removeUnitDense(dense);
        return true;
    }

    /** Clears all rows while invalidating all currently live handles. */
    public void clear() {
        while (unitSize != 0) {
            removeUnitDense(unitSize - 1);
        }
        while (armySize != 0) {
            removeArmyDense(armySize - 1);
        }
    }

    public boolean isArmyAlive(int handle) {
        return armyDense(handle) >= 0;
    }

    public boolean isUnitAlive(int handle) {
        return unitDense(handle) >= 0;
    }

    public int armyFaction(int handle) {
        return armyFaction[armyDenseOrThrow(handle)];
    }

    public void armyFaction(int handle, int factionId) {
        armyFaction[armyDenseOrThrow(handle)] = factionId;
    }

    public int armyOrder(int handle) {
        return armyOrder[armyDenseOrThrow(handle)];
    }

    public void armyOrder(int handle, int order) {
        armyOrder[armyDenseOrThrow(handle)] = order;
    }

    public int armyState(int handle) {
        return armyState[armyDenseOrThrow(handle)];
    }

    public void armyState(int handle, int state) {
        armyState[armyDenseOrThrow(handle)] = state;
    }

    public long armyPackedTargetPos(int handle) {
        return armyPackedTargetPos[armyDenseOrThrow(handle)];
    }

    public void armyPackedTargetPos(int handle, long packedPos) {
        armyPackedTargetPos[armyDenseOrThrow(handle)] = packedPos;
    }

    public int armyUnitCount(int handle) {
        return armyUnitCount[armyDenseOrThrow(handle)];
    }

    public int unitArmy(int handle) {
        return unitArmy[unitDenseOrThrow(handle)];
    }

    public void unitArmy(int handle, int newArmyHandle) {
        int dense = unitDenseOrThrow(handle);
        if (newArmyHandle != NO_ARMY && !isArmyAlive(newArmyHandle)) {
            throw new IllegalArgumentException("Unknown army handle: " + newArmyHandle);
        }

        int oldArmyHandle = unitArmy[dense];
        if (oldArmyHandle == newArmyHandle) {
            return;
        }
        if (oldArmyHandle != NO_ARMY) {
            armyUnitCount[armyDenseOrThrow(oldArmyHandle)]--;
        }
        unitArmy[dense] = newArmyHandle;
        if (newArmyHandle != NO_ARMY) {
            armyUnitCount[armyDenseOrThrow(newArmyHandle)]++;
        }
    }

    public int unitOrder(int handle) {
        return unitOrder[unitDenseOrThrow(handle)];
    }

    public void unitOrder(int handle, int order) {
        unitOrder[unitDenseOrThrow(handle)] = order;
    }

    public int unitState(int handle) {
        return unitState[unitDenseOrThrow(handle)];
    }

    public void unitState(int handle, int state) {
        unitState[unitDenseOrThrow(handle)] = state;
    }

    public long unitPackedPos(int handle) {
        return unitPackedPos[unitDenseOrThrow(handle)];
    }

    public void unitPackedPos(int handle, long packedPos) {
        unitPackedPos[unitDenseOrThrow(handle)] = packedPos;
    }

    /** Allocates once. Retain and {@link ArmyCursor#reset()} it for every tick. */
    public ArmyCursor newArmyCursor() {
        return new ArmyCursor(this);
    }

    /** Allocates once. Retain and {@link UnitCursor#reset()} it for every tick. */
    public UnitCursor newUnitCursor() {
        return new UnitCursor(this);
    }

    /** Allocates once. Retain it and overwrite it with {@link #readArmy}. */
    public ArmySnapshot newArmySnapshot() {
        return new ArmySnapshot();
    }

    /** Allocates once. Retain it and overwrite it with {@link #readUnit}. */
    public UnitSnapshot newUnitSnapshot() {
        return new UnitSnapshot();
    }

    public boolean readArmy(int handle, ArmySnapshot destination) {
        if (destination == null) {
            throw new NullPointerException("destination");
        }
        int dense = armyDense(handle);
        if (dense < 0) {
            return false;
        }
        destination.handle = armyHandles[dense];
        destination.faction = armyFaction[dense];
        destination.order = armyOrder[dense];
        destination.state = armyState[dense];
        destination.unitCount = armyUnitCount[dense];
        destination.packedTargetPos = armyPackedTargetPos[dense];
        return true;
    }

    public boolean readUnit(int handle, UnitSnapshot destination) {
        if (destination == null) {
            throw new NullPointerException("destination");
        }
        int dense = unitDense(handle);
        if (dense < 0) {
            return false;
        }
        destination.handle = unitHandles[dense];
        destination.army = unitArmy[dense];
        destination.order = unitOrder[dense];
        destination.state = unitState[dense];
        destination.packedPos = unitPackedPos[dense];
        return true;
    }

    /** Minecraft-compatible BlockPos packing without a BlockPos allocation. */
    public static long packBlockPos(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
                | ((long) z & 0x3FFFFFFL) << 12
                | ((long) y & 0xFFFL);
    }

    public static int unpackBlockX(long packedPos) {
        return (int) (packedPos >> 38);
    }

    public static int unpackBlockY(long packedPos) {
        return (int) (packedPos << 52 >> 52);
    }

    public static int unpackBlockZ(long packedPos) {
        return (int) (packedPos << 26 >> 38);
    }

    /** Low-level slot index for sibling packed runtime projections; generation remains in handle. */
    public static int handleSlotIndex(int handle) {
        return handleSlot(handle);
    }

    private int acquireArmySlot() {
        if (armyFreeCount != 0) {
            return armyFreeSlots[--armyFreeCount];
        }
        if (armyNextSlot == MAX_SLOTS) {
            throw new IllegalStateException("Army handle slot limit reached");
        }
        ensureArmySlotCapacity(armyNextSlot + 1);
        int slot = armyNextSlot++;
        armySlotGeneration[slot] = 1;
        return slot;
    }

    private int acquireUnitSlot() {
        if (unitFreeCount != 0) {
            return unitFreeSlots[--unitFreeCount];
        }
        if (unitNextSlot == MAX_SLOTS) {
            throw new IllegalStateException("Unit handle slot limit reached");
        }
        ensureUnitSlotCapacity(unitNextSlot + 1);
        int slot = unitNextSlot++;
        unitSlotGeneration[slot] = 1;
        return slot;
    }

    private void removeArmyDense(int dense) {
        int removedHandle = armyHandles[dense];
        int removedSlot = handleSlot(removedHandle);
        int last = --armySize;
        if (dense != last) {
            int movedHandle = armyHandles[last];
            armyHandles[dense] = movedHandle;
            armyFaction[dense] = armyFaction[last];
            armyOrder[dense] = armyOrder[last];
            armyState[dense] = armyState[last];
            armyUnitCount[dense] = armyUnitCount[last];
            armyPackedTargetPos[dense] = armyPackedTargetPos[last];
            armySlotToDense[handleSlot(movedHandle)] = dense;
        }
        armyHandles[last] = 0;
        armyFaction[last] = 0;
        armyOrder[last] = 0;
        armyState[last] = 0;
        armyUnitCount[last] = 0;
        armyPackedTargetPos[last] = 0L;
        armySlotToDense[removedSlot] = -1;
        armySlotGeneration[removedSlot] = nextGeneration(armySlotGeneration[removedSlot]);
        armyFreeSlots[armyFreeCount++] = removedSlot;
        armyStructuralVersion++;
    }

    private void removeUnitDense(int dense) {
        int removedHandle = unitHandles[dense];
        int removedSlot = handleSlot(removedHandle);
        int last = --unitSize;
        if (dense != last) {
            int movedHandle = unitHandles[last];
            unitHandles[dense] = movedHandle;
            unitArmy[dense] = unitArmy[last];
            unitOrder[dense] = unitOrder[last];
            unitState[dense] = unitState[last];
            unitPackedPos[dense] = unitPackedPos[last];
            unitSlotToDense[handleSlot(movedHandle)] = dense;
        }
        unitHandles[last] = 0;
        unitArmy[last] = NO_ARMY;
        unitOrder[last] = 0;
        unitState[last] = 0;
        unitPackedPos[last] = 0L;
        unitSlotToDense[removedSlot] = -1;
        unitSlotGeneration[removedSlot] = nextGeneration(unitSlotGeneration[removedSlot]);
        unitFreeSlots[unitFreeCount++] = removedSlot;
        unitStructuralVersion++;
    }

    private int armyDense(int handle) {
        if (handle == NO_ARMY) {
            return -1;
        }
        int slot = handleSlot(handle);
        if (slot >= armyNextSlot || armySlotGeneration[slot] != handleGeneration(handle)) {
            return -1;
        }
        return armySlotToDense[slot];
    }

    private int unitDense(int handle) {
        if (handle == 0) {
            return -1;
        }
        int slot = handleSlot(handle);
        if (slot >= unitNextSlot || unitSlotGeneration[slot] != handleGeneration(handle)) {
            return -1;
        }
        return unitSlotToDense[slot];
    }

    private int armyDenseOrThrow(int handle) {
        int dense = armyDense(handle);
        if (dense < 0) {
            throw new IllegalArgumentException("Unknown army handle: " + handle);
        }
        return dense;
    }

    private int unitDenseOrThrow(int handle) {
        int dense = unitDense(handle);
        if (dense < 0) {
            throw new IllegalArgumentException("Unknown unit handle: " + handle);
        }
        return dense;
    }

    private void ensureArmyDenseCapacity(int required) {
        if (required <= armyHandles.length) {
            return;
        }
        int capacity = grownCapacity(armyHandles.length, required);
        armyHandles = Arrays.copyOf(armyHandles, capacity);
        armyFaction = Arrays.copyOf(armyFaction, capacity);
        armyOrder = Arrays.copyOf(armyOrder, capacity);
        armyState = Arrays.copyOf(armyState, capacity);
        armyUnitCount = Arrays.copyOf(armyUnitCount, capacity);
        armyPackedTargetPos = Arrays.copyOf(armyPackedTargetPos, capacity);
    }

    private void ensureArmySlotCapacity(int required) {
        if (required <= armySlotToDense.length) {
            return;
        }
        int oldCapacity = armySlotToDense.length;
        int capacity = grownCapacity(oldCapacity, required);
        armySlotToDense = Arrays.copyOf(armySlotToDense, capacity);
        Arrays.fill(armySlotToDense, oldCapacity, capacity, -1);
        armySlotGeneration = Arrays.copyOf(armySlotGeneration, capacity);
        armyFreeSlots = Arrays.copyOf(armyFreeSlots, capacity);
    }

    private void ensureUnitDenseCapacity(int required) {
        if (required <= unitHandles.length) {
            return;
        }
        int capacity = grownCapacity(unitHandles.length, required);
        unitHandles = Arrays.copyOf(unitHandles, capacity);
        unitArmy = Arrays.copyOf(unitArmy, capacity);
        unitOrder = Arrays.copyOf(unitOrder, capacity);
        unitState = Arrays.copyOf(unitState, capacity);
        unitPackedPos = Arrays.copyOf(unitPackedPos, capacity);
    }

    private void ensureUnitSlotCapacity(int required) {
        if (required <= unitSlotToDense.length) {
            return;
        }
        int oldCapacity = unitSlotToDense.length;
        int capacity = grownCapacity(oldCapacity, required);
        unitSlotToDense = Arrays.copyOf(unitSlotToDense, capacity);
        Arrays.fill(unitSlotToDense, oldCapacity, capacity, -1);
        unitSlotGeneration = Arrays.copyOf(unitSlotGeneration, capacity);
        unitFreeSlots = Arrays.copyOf(unitFreeSlots, capacity);
    }

    private static int grownCapacity(int current, int required) {
        checkRequestedCapacity(required);
        int candidate = current < MIN_GROWTH ? MIN_GROWTH : current + (current >>> 1);
        if (candidate < 0 || candidate > MAX_SLOTS) {
            candidate = MAX_SLOTS;
        }
        return Math.max(candidate, required);
    }

    private static void checkRequestedCapacity(int capacity) {
        if (capacity < 0 || capacity > MAX_SLOTS) {
            throw new IllegalArgumentException("Capacity outside handle range: " + capacity);
        }
    }

    private static int makeHandle(int slot, int generation) {
        return generation << SLOT_BITS | slot;
    }

    private static int handleSlot(int handle) {
        return handle & SLOT_MASK;
    }

    private static int handleGeneration(int handle) {
        return handle >>> SLOT_BITS & GENERATION_MASK;
    }

    private static int nextGeneration(int generation) {
        int next = generation + 1 & GENERATION_MASK;
        return next == 0 ? 1 : next;
    }

    /** Package-private, allocation-free on success; intended for debug/self-test. */
    void checkInvariants() {
        invariant(armySize >= 0 && armySize <= armyHandles.length, "army dense size");
        invariant(unitSize >= 0 && unitSize <= unitHandles.length, "unit dense size");
        invariant(armyNextSlot >= 0 && armyNextSlot <= armySlotToDense.length, "army slot size");
        invariant(unitNextSlot >= 0 && unitNextSlot <= unitSlotToDense.length, "unit slot size");
        invariant(armyFreeCount >= 0 && armyFreeCount <= armyNextSlot, "army free size");
        invariant(unitFreeCount >= 0 && unitFreeCount <= unitNextSlot, "unit free size");
        invariant(armySize + armyFreeCount == armyNextSlot, "army active/free partition");
        invariant(unitSize + unitFreeCount == unitNextSlot, "unit active/free partition");

        for (int dense = 0; dense < armySize; dense++) {
            int handle = armyHandles[dense];
            int slot = handleSlot(handle);
            invariant(handle != NO_ARMY, "zero army handle");
            invariant(slot < armyNextSlot, "army slot bounds");
            invariant(armySlotToDense[slot] == dense, "army slot-to-dense");
            invariant(armySlotGeneration[slot] == handleGeneration(handle), "army generation");

            int observedUnits = 0;
            for (int unitDense = 0; unitDense < unitSize; unitDense++) {
                if (unitArmy[unitDense] == handle) {
                    observedUnits++;
                }
            }
            invariant(armyUnitCount[dense] == observedUnits, "army unit count");
        }

        for (int dense = 0; dense < unitSize; dense++) {
            int handle = unitHandles[dense];
            int slot = handleSlot(handle);
            invariant(handle != 0, "zero unit handle");
            invariant(slot < unitNextSlot, "unit slot bounds");
            invariant(unitSlotToDense[slot] == dense, "unit slot-to-dense");
            invariant(unitSlotGeneration[slot] == handleGeneration(handle), "unit generation");
            invariant(unitArmy[dense] == NO_ARMY || isArmyAlive(unitArmy[dense]), "unit army reference");
        }

        checkSlotPartition(armyNextSlot, armySlotToDense, armyFreeSlots, armyFreeCount, "army");
        checkSlotPartition(unitNextSlot, unitSlotToDense, unitFreeSlots, unitFreeCount, "unit");
    }

    private static void checkSlotPartition(
            int nextSlot,
            int[] slotToDense,
            int[] freeSlots,
            int freeCount,
            String kind
    ) {
        for (int slot = 0; slot < nextSlot; slot++) {
            int occurrences = 0;
            for (int free = 0; free < freeCount; free++) {
                if (freeSlots[free] == slot) {
                    occurrences++;
                }
            }
            if (slotToDense[slot] < 0) {
                invariant(occurrences == 1, kind, " free slot membership");
            } else {
                invariant(occurrences == 0, kind, " live slot in free stack");
            }
        }
    }

    private static void invariant(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("PackedArmyEcs invariant failed: " + message);
        }
    }

    private static void invariant(boolean condition, String kind, String message) {
        if (!condition) {
            throw new AssertionError("PackedArmyEcs invariant failed: " + kind + message);
        }
    }

    public static final class ArmyCursor {
        private final PackedArmyEcs owner;
        private int expectedStructuralVersion;
        private int nextDense;
        private int dense = -1;

        private ArmyCursor(PackedArmyEcs owner) {
            this.owner = owner;
            reset();
        }

        public ArmyCursor reset() {
            expectedStructuralVersion = owner.armyStructuralVersion;
            nextDense = 0;
            dense = -1;
            return this;
        }

        public boolean advance() {
            checkVersion();
            if (nextDense == owner.armySize) {
                dense = -1;
                return false;
            }
            dense = nextDense++;
            return true;
        }

        public int handle() {
            checkActive();
            return owner.armyHandles[dense];
        }

        public int faction() {
            checkActive();
            return owner.armyFaction[dense];
        }

        public void faction(int factionId) {
            checkActive();
            owner.armyFaction[dense] = factionId;
        }

        public int order() {
            checkActive();
            return owner.armyOrder[dense];
        }

        public void order(int order) {
            checkActive();
            owner.armyOrder[dense] = order;
        }

        public int state() {
            checkActive();
            return owner.armyState[dense];
        }

        public void state(int state) {
            checkActive();
            owner.armyState[dense] = state;
        }

        public int unitCount() {
            checkActive();
            return owner.armyUnitCount[dense];
        }

        public long packedTargetPos() {
            checkActive();
            return owner.armyPackedTargetPos[dense];
        }

        public void packedTargetPos(long packedPos) {
            checkActive();
            owner.armyPackedTargetPos[dense] = packedPos;
        }

        private void checkVersion() {
            if (expectedStructuralVersion != owner.armyStructuralVersion) {
                throw new IllegalStateException("Army cursor invalidated by structural change; reset it");
            }
        }

        private void checkActive() {
            checkVersion();
            if (dense < 0) {
                throw new IllegalStateException("Army cursor is not on a row");
            }
        }
    }

    public static final class UnitCursor {
        private final PackedArmyEcs owner;
        private int expectedStructuralVersion;
        private int nextDense;
        private int dense = -1;

        private UnitCursor(PackedArmyEcs owner) {
            this.owner = owner;
            reset();
        }

        public UnitCursor reset() {
            expectedStructuralVersion = owner.unitStructuralVersion;
            nextDense = 0;
            dense = -1;
            return this;
        }

        public boolean advance() {
            checkVersion();
            if (nextDense == owner.unitSize) {
                dense = -1;
                return false;
            }
            dense = nextDense++;
            return true;
        }

        public int handle() {
            checkActive();
            return owner.unitHandles[dense];
        }

        public int army() {
            checkActive();
            return owner.unitArmy[dense];
        }

        public void army(int armyHandle) {
            checkActive();
            owner.unitArmy(owner.unitHandles[dense], armyHandle);
        }

        public int order() {
            checkActive();
            return owner.unitOrder[dense];
        }

        public void order(int order) {
            checkActive();
            owner.unitOrder[dense] = order;
        }

        public int state() {
            checkActive();
            return owner.unitState[dense];
        }

        public void state(int state) {
            checkActive();
            owner.unitState[dense] = state;
        }

        public long packedPos() {
            checkActive();
            return owner.unitPackedPos[dense];
        }

        public void packedPos(long packedPos) {
            checkActive();
            owner.unitPackedPos[dense] = packedPos;
        }

        private void checkVersion() {
            if (expectedStructuralVersion != owner.unitStructuralVersion) {
                throw new IllegalStateException("Unit cursor invalidated by structural change; reset it");
            }
        }

        private void checkActive() {
            checkVersion();
            if (dense < 0) {
                throw new IllegalStateException("Unit cursor is not on a row");
            }
        }
    }

    /** Mutable caller-owned result buffer. */
    public static final class ArmySnapshot {
        private int handle;
        private int faction;
        private int order;
        private int state;
        private int unitCount;
        private long packedTargetPos;

        private ArmySnapshot() {
        }

        public int handle() {
            return handle;
        }

        public int faction() {
            return faction;
        }

        public int order() {
            return order;
        }

        public int state() {
            return state;
        }

        public int unitCount() {
            return unitCount;
        }

        public long packedTargetPos() {
            return packedTargetPos;
        }
    }

    /** Mutable caller-owned result buffer. */
    public static final class UnitSnapshot {
        private int handle;
        private int army;
        private int order;
        private int state;
        private long packedPos;

        private UnitSnapshot() {
        }

        public int handle() {
            return handle;
        }

        public int army() {
            return army;
        }

        public int order() {
            return order;
        }

        public int state() {
            return state;
        }

        public long packedPos() {
            return packedPos;
        }
    }
}
