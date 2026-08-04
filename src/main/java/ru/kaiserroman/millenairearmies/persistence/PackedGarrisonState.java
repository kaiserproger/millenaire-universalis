package ru.kaiserroman.millenairearmies.persistence;

import java.util.Arrays;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;

/**
 * Persistent, allocation-free-on-tick garrison bindings keyed by opaque army handles.
 *
 * <p>Rows bind one army to one real Millenaire settlement and a bounded muster area. The coarse
 * supply/readiness/morale values are authoritative strategic state; physical villager inventories
 * and combat remain owned by Millenaire.</p>
 */
public final class PackedGarrisonState {
    public static final byte STATUS_SUPPLIED = 0;
    public static final byte STATUS_LOW = 1;
    public static final byte STATUS_STARVING = 2;

    private static final int MIN_GROWTH = 4;

    private int size;
    private int structuralVersion;
    private long nextRevision = 1L;
    private int[] armyHandles = new int[0];
    /** Army handle slot -> dense row + 1; generation/complete handle is rechecked on lookup. */
    private int[] armySlotToRow = new int[0];
    private long[] villageMost = new long[0];
    private long[] villageLeast = new long[0];
    private int[] dimensionIds = new int[0];
    private long[] musterPositions = new long[0];
    private int[] guardRadii = new int[0];
    private int[] supplyPercent = new int[0];
    private int[] readinessPercent = new int[0];
    private int[] moralePercent = new int[0];
    private byte[] statuses = new byte[0];
    private long[] nextUpkeepTicks = new long[0];
    private long[] revisions = new long[0];

    public PackedGarrisonState() {
    }

    public PackedGarrisonState(int capacity) {
        reserve(capacity);
    }

    public int size() {
        return size;
    }

    public long nextRevision() {
        return nextRevision;
    }

    public void reserve(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Negative garrison capacity");
        }
        if (capacity <= armyHandles.length) {
            return;
        }
        armyHandles = Arrays.copyOf(armyHandles, capacity);
        villageMost = Arrays.copyOf(villageMost, capacity);
        villageLeast = Arrays.copyOf(villageLeast, capacity);
        dimensionIds = Arrays.copyOf(dimensionIds, capacity);
        musterPositions = Arrays.copyOf(musterPositions, capacity);
        guardRadii = Arrays.copyOf(guardRadii, capacity);
        supplyPercent = Arrays.copyOf(supplyPercent, capacity);
        readinessPercent = Arrays.copyOf(readinessPercent, capacity);
        moralePercent = Arrays.copyOf(moralePercent, capacity);
        statuses = Arrays.copyOf(statuses, capacity);
        nextUpkeepTicks = Arrays.copyOf(nextUpkeepTicks, capacity);
        revisions = Arrays.copyOf(revisions, capacity);
    }

    /** Adds or updates one binding. Reassignment cannot refill coarse readiness by itself. */
    public boolean assign(
            int armyHandle,
            long settlementMost,
            long settlementLeast,
            int dimensionId,
            long musterPosition,
            int guardRadius,
            long nextUpkeepTick) {
        requireBinding(armyHandle, settlementMost, settlementLeast, dimensionId, guardRadius, nextUpkeepTick);
        int row = findArmy(armyHandle);
        if (row >= 0) {
            if (villageMost[row] == settlementMost
                    && villageLeast[row] == settlementLeast
                    && dimensionIds[row] == dimensionId
                    && musterPositions[row] == musterPosition
                    && guardRadii[row] == guardRadius) {
                return false;
            }
            villageMost[row] = settlementMost;
            villageLeast[row] = settlementLeast;
            dimensionIds[row] = dimensionId;
            musterPositions[row] = musterPosition;
            guardRadii[row] = guardRadius;
            // Reassignment must never postpone an already scheduled debit; otherwise repeatedly
            // placing the same banner would allow a garrison to avoid upkeep indefinitely.
            nextUpkeepTicks[row] = Math.min(nextUpkeepTicks[row], nextUpkeepTick);
            revisions[row] = takeRevision();
            return true;
        }

        ensureCapacity(size + 1);
        int slot = PackedArmyEcs.handleSlotIndex(armyHandle);
        ensureSlotCapacity(slot + 1);
        row = size++;
        armyHandles[row] = armyHandle;
        armySlotToRow[slot] = row + 1;
        villageMost[row] = settlementMost;
        villageLeast[row] = settlementLeast;
        dimensionIds[row] = dimensionId;
        musterPositions[row] = musterPosition;
        guardRadii[row] = guardRadius;
        supplyPercent[row] = 100;
        readinessPercent[row] = 100;
        moralePercent[row] = 100;
        statuses[row] = STATUS_SUPPLIED;
        nextUpkeepTicks[row] = nextUpkeepTick;
        revisions[row] = takeRevision();
        structuralVersion++;
        return true;
    }

    /** Cold-load boundary; rejects duplicate armies and malformed percentages/statuses. */
    public void restore(
            int armyHandle,
            long settlementMost,
            long settlementLeast,
            int dimensionId,
            long musterPosition,
            int guardRadius,
            int supply,
            int readiness,
            int morale,
            byte status,
            long nextUpkeepTick,
            long revision) {
        requireBinding(armyHandle, settlementMost, settlementLeast, dimensionId, guardRadius, nextUpkeepTick);
        requirePercent(supply, "supply");
        requirePercent(readiness, "readiness");
        requirePercent(morale, "morale");
        if (status < STATUS_SUPPLIED || status > STATUS_STARVING) {
            throw new IllegalArgumentException("Unknown garrison status " + status);
        }
        if (revision <= 0L) {
            throw new IllegalArgumentException("Garrison revision must be positive");
        }
        if (findArmy(armyHandle) >= 0) {
            throw new IllegalArgumentException("Duplicate persisted garrison army");
        }
        ensureCapacity(size + 1);
        int slot = PackedArmyEcs.handleSlotIndex(armyHandle);
        ensureSlotCapacity(slot + 1);
        int row = size++;
        armyHandles[row] = armyHandle;
        armySlotToRow[slot] = row + 1;
        villageMost[row] = settlementMost;
        villageLeast[row] = settlementLeast;
        dimensionIds[row] = dimensionId;
        musterPositions[row] = musterPosition;
        guardRadii[row] = guardRadius;
        supplyPercent[row] = supply;
        readinessPercent[row] = readiness;
        moralePercent[row] = morale;
        statuses[row] = status;
        nextUpkeepTicks[row] = nextUpkeepTick;
        revisions[row] = revision;
        nextRevision = Math.max(nextRevision, saturatedIncrement(revision));
        structuralVersion++;
    }

    public void restoreNextRevision(long revision) {
        if (revision <= 0L) {
            throw new IllegalArgumentException("Next garrison revision must be positive");
        }
        if (revision < nextRevision) {
            throw new IllegalArgumentException("Next garrison revision precedes a restored row");
        }
        nextRevision = revision;
    }

    public boolean removeArmy(int armyHandle) {
        int row = findArmy(armyHandle);
        if (row < 0) {
            return false;
        }
        removeAt(row);
        return true;
    }

    /**
     * Advances one coarse upkeep period. The deltas are intentionally gradual and bounded: failure
     * does not delete units, and reassignment does not reset the accumulated state.
     */
    public boolean recordUpkeep(int armyHandle, boolean supplied, long nextUpkeepTick) {
        if (nextUpkeepTick < 0L) {
            throw new IllegalArgumentException("Negative next upkeep tick");
        }
        int row = findArmy(armyHandle);
        if (row < 0) {
            return false;
        }
        int nextSupply = clampPercent(supplyPercent[row] + (supplied ? 12 : -18));
        int nextReadiness = clampPercent(readinessPercent[row] + (supplied ? 6 : -10));
        int nextMorale = clampPercent(moralePercent[row] + (supplied ? 4 : -7));
        byte nextStatus = statusFor(nextSupply, nextReadiness);
        boolean changed = nextSupply != supplyPercent[row]
                || nextReadiness != readinessPercent[row]
                || nextMorale != moralePercent[row]
                || nextStatus != statuses[row]
                || nextUpkeepTicks[row] != nextUpkeepTick;
        if (!changed) {
            return false;
        }
        supplyPercent[row] = nextSupply;
        readinessPercent[row] = nextReadiness;
        moralePercent[row] = nextMorale;
        statuses[row] = nextStatus;
        nextUpkeepTicks[row] = nextUpkeepTick;
        revisions[row] = takeRevision();
        return true;
    }

    public int findArmy(int armyHandle) {
        if (armyHandle == 0) {
            return -1;
        }
        int slot = PackedArmyEcs.handleSlotIndex(armyHandle);
        if (slot < 0 || slot >= armySlotToRow.length) {
            return -1;
        }
        int row = armySlotToRow[slot] - 1;
        return row >= 0 && row < size && armyHandles[row] == armyHandle ? row : -1;
    }

    public boolean readArmy(int armyHandle, View destination) {
        int row = findArmy(armyHandle);
        if (row < 0) {
            destination.clear();
            return false;
        }
        copy(row, destination);
        return true;
    }

    public int armyHandleAt(int row) { checkRow(row); return armyHandles[row]; }
    public long villageMostAt(int row) { checkRow(row); return villageMost[row]; }
    public long villageLeastAt(int row) { checkRow(row); return villageLeast[row]; }
    public int dimensionIdAt(int row) { checkRow(row); return dimensionIds[row]; }
    public long musterPositionAt(int row) { checkRow(row); return musterPositions[row]; }
    public int guardRadiusAt(int row) { checkRow(row); return guardRadii[row]; }
    public int supplyPercentAt(int row) { checkRow(row); return supplyPercent[row]; }
    public int readinessPercentAt(int row) { checkRow(row); return readinessPercent[row]; }
    public int moralePercentAt(int row) { checkRow(row); return moralePercent[row]; }
    public byte statusAt(int row) { checkRow(row); return statuses[row]; }
    public long nextUpkeepTickAt(int row) { checkRow(row); return nextUpkeepTicks[row]; }
    public long revisionAt(int row) { checkRow(row); return revisions[row]; }

    public View newView() {
        return new View();
    }

    public Cursor newCursor() {
        return new Cursor(this);
    }

    public void clear() {
        if (size == 0) {
            return;
        }
        Arrays.fill(armyHandles, 0, size, 0);
        Arrays.fill(armySlotToRow, 0);
        Arrays.fill(villageMost, 0, size, 0L);
        Arrays.fill(villageLeast, 0, size, 0L);
        Arrays.fill(dimensionIds, 0, size, 0);
        Arrays.fill(musterPositions, 0, size, 0L);
        Arrays.fill(guardRadii, 0, size, 0);
        Arrays.fill(supplyPercent, 0, size, 0);
        Arrays.fill(readinessPercent, 0, size, 0);
        Arrays.fill(moralePercent, 0, size, 0);
        Arrays.fill(statuses, 0, size, (byte) 0);
        Arrays.fill(nextUpkeepTicks, 0, size, 0L);
        Arrays.fill(revisions, 0, size, 0L);
        size = 0;
        structuralVersion++;
    }

    private void copy(int row, View destination) {
        destination.armyHandle = armyHandles[row];
        destination.villageMost = villageMost[row];
        destination.villageLeast = villageLeast[row];
        destination.dimensionId = dimensionIds[row];
        destination.musterPosition = musterPositions[row];
        destination.guardRadius = guardRadii[row];
        destination.supplyPercent = supplyPercent[row];
        destination.readinessPercent = readinessPercent[row];
        destination.moralePercent = moralePercent[row];
        destination.status = statuses[row];
        destination.nextUpkeepTick = nextUpkeepTicks[row];
        destination.revision = revisions[row];
    }

    private void removeAt(int row) {
        int removedHandle = armyHandles[row];
        int removedSlot = PackedArmyEcs.handleSlotIndex(removedHandle);
        int last = --size;
        armySlotToRow[removedSlot] = 0;
        if (row != last) {
            armyHandles[row] = armyHandles[last];
            armySlotToRow[PackedArmyEcs.handleSlotIndex(armyHandles[row])] = row + 1;
            villageMost[row] = villageMost[last];
            villageLeast[row] = villageLeast[last];
            dimensionIds[row] = dimensionIds[last];
            musterPositions[row] = musterPositions[last];
            guardRadii[row] = guardRadii[last];
            supplyPercent[row] = supplyPercent[last];
            readinessPercent[row] = readinessPercent[last];
            moralePercent[row] = moralePercent[last];
            statuses[row] = statuses[last];
            nextUpkeepTicks[row] = nextUpkeepTicks[last];
            revisions[row] = revisions[last];
        }
        armyHandles[last] = 0;
        villageMost[last] = 0L;
        villageLeast[last] = 0L;
        dimensionIds[last] = 0;
        musterPositions[last] = 0L;
        guardRadii[last] = 0;
        supplyPercent[last] = 0;
        readinessPercent[last] = 0;
        moralePercent[last] = 0;
        statuses[last] = 0;
        nextUpkeepTicks[last] = 0L;
        revisions[last] = 0L;
        structuralVersion++;
    }

    private void ensureCapacity(int required) {
        if (required <= armyHandles.length) {
            return;
        }
        int current = armyHandles.length;
        int grown = current < MIN_GROWTH ? MIN_GROWTH : current + (current >>> 1);
        reserve(Math.max(required, grown));
    }

    private void ensureSlotCapacity(int required) {
        if (required <= armySlotToRow.length) {
            return;
        }
        int current = armySlotToRow.length;
        int grown = current < MIN_GROWTH ? MIN_GROWTH : current + (current >>> 1);
        armySlotToRow = Arrays.copyOf(armySlotToRow, Math.max(required, grown));
    }

    private long takeRevision() {
        if (nextRevision == Long.MAX_VALUE) {
            throw new IllegalStateException("Garrison revision space exhausted");
        }
        return nextRevision++;
    }

    private static long saturatedIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static byte statusFor(int supply, int readiness) {
        int floor = Math.min(supply, readiness);
        return floor < 25 ? STATUS_STARVING : floor < 60 ? STATUS_LOW : STATUS_SUPPLIED;
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static void requireBinding(
            int armyHandle,
            long settlementMost,
            long settlementLeast,
            int dimensionId,
            int guardRadius,
            long nextUpkeepTick) {
        if (armyHandle == 0) {
            throw new IllegalArgumentException("Zero is not a valid garrison army handle");
        }
        if (settlementMost == 0L && settlementLeast == 0L) {
            throw new IllegalArgumentException("Garrison settlement UUID is absent");
        }
        if (dimensionId < 0) {
            throw new IllegalArgumentException("Negative garrison dimension id");
        }
        if (guardRadius <= 0 || guardRadius > 1_024) {
            throw new IllegalArgumentException("Garrison radius outside 1..1024: " + guardRadius);
        }
        if (nextUpkeepTick < 0L) {
            throw new IllegalArgumentException("Negative next upkeep tick");
        }
    }

    private static void requirePercent(int value, String name) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException("Garrison " + name + " outside 0..100: " + value);
        }
    }

    private void checkRow(int row) {
        if (row < 0 || row >= size) {
            throw new IndexOutOfBoundsException("Garrison row " + row + " of " + size);
        }
    }

    public static final class View {
        private int armyHandle;
        private long villageMost;
        private long villageLeast;
        private int dimensionId;
        private long musterPosition;
        private int guardRadius;
        private int supplyPercent;
        private int readinessPercent;
        private int moralePercent;
        private byte status;
        private long nextUpkeepTick;
        private long revision;

        private View() {
        }

        private void clear() {
            armyHandle = 0;
            villageMost = 0L;
            villageLeast = 0L;
            dimensionId = 0;
            musterPosition = 0L;
            guardRadius = 0;
            supplyPercent = 0;
            readinessPercent = 0;
            moralePercent = 0;
            status = 0;
            nextUpkeepTick = 0L;
            revision = 0L;
        }

        public int armyHandle() { return armyHandle; }
        public long villageMost() { return villageMost; }
        public long villageLeast() { return villageLeast; }
        public int dimensionId() { return dimensionId; }
        public long musterPosition() { return musterPosition; }
        public int guardRadius() { return guardRadius; }
        public int supplyPercent() { return supplyPercent; }
        public int readinessPercent() { return readinessPercent; }
        public int moralePercent() { return moralePercent; }
        public byte status() { return status; }
        public long nextUpkeepTick() { return nextUpkeepTick; }
        public long revision() { return revision; }
    }

    public static final class Cursor {
        private final PackedGarrisonState owner;
        private int expectedVersion;
        private int nextRow;
        private int row = -1;

        private Cursor(PackedGarrisonState owner) {
            this.owner = owner;
            reset();
        }

        public Cursor reset() {
            expectedVersion = owner.structuralVersion;
            nextRow = 0;
            row = -1;
            return this;
        }

        public boolean advance() {
            checkVersion();
            if (nextRow == owner.size) {
                row = -1;
                return false;
            }
            row = nextRow++;
            return true;
        }

        public int armyHandle() { checkActive(); return owner.armyHandles[row]; }
        public long villageMost() { checkActive(); return owner.villageMost[row]; }
        public long villageLeast() { checkActive(); return owner.villageLeast[row]; }
        public int dimensionId() { checkActive(); return owner.dimensionIds[row]; }
        public long musterPosition() { checkActive(); return owner.musterPositions[row]; }
        public int guardRadius() { checkActive(); return owner.guardRadii[row]; }
        public int supplyPercent() { checkActive(); return owner.supplyPercent[row]; }
        public int readinessPercent() { checkActive(); return owner.readinessPercent[row]; }
        public int moralePercent() { checkActive(); return owner.moralePercent[row]; }
        public byte status() { checkActive(); return owner.statuses[row]; }
        public long nextUpkeepTick() { checkActive(); return owner.nextUpkeepTicks[row]; }
        public long revision() { checkActive(); return owner.revisions[row]; }

        private void checkActive() {
            checkVersion();
            if (row < 0) {
                throw new IllegalStateException("Garrison cursor is not on a row");
            }
        }

        private void checkVersion() {
            if (expectedVersion != owner.structuralVersion) {
                throw new IllegalStateException("Garrison cursor invalidated by structural change");
            }
        }
    }
}
