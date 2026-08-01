package ru.kaiserroman.millenairearmies.persistence;

import java.util.Arrays;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.model.ArmyOrderType;

/**
 * Packed structure-of-arrays storage for pending army commands.
 *
 * <p>The store contains primitive values only. Army references are transient ECS handles and are
 * remapped by {@link ArmySavedData} when the world is loaded. Dimension ids refer to
 * {@link StableDimensionTable}; subjects use persistent UUID bits, never runtime entity ids.
 * Iteration through a retained cursor and in-capacity mutations are allocation-free.</p>
 */
public final class PackedCommandState {
    private static final int MIN_GROWTH = 8;

    private int size;
    private int structuralVersion;
    private long nextOrderId = 1L;
    private long revision;

    private long[] orderIds = new long[0];
    private int[] armyHandles = new int[0];
    private int[] issuerFactionIds = new int[0];
    private byte[] typeCodes = new byte[0];
    private int[] dimensionIds = new int[0];
    private long[] primaryPositions = new long[0];
    private long[] secondaryPositions = new long[0];
    private long[] subjectUuidMost = new long[0];
    private long[] subjectUuidLeast = new long[0];
    private long[] issuedGameTimes = new long[0];
    private byte[] flags = new byte[0];

    public PackedCommandState() {
    }

    public PackedCommandState(int expectedCommands) {
        reserve(expectedCommands);
    }

    public int size() {
        return size;
    }

    /** Current primitive row capacity. Exposed for bounded-engine admission and allocation tests. */
    public int capacity() {
        return orderIds.length;
    }

    public long nextOrderId() {
        return nextOrderId;
    }

    public long revision() {
        return revision;
    }

    public void reserve(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Negative command capacity: " + capacity);
        }
        ensureCapacity(capacity);
    }

    public long add(
            int armyHandle,
            int issuerFactionId,
            byte typeCode,
            int dimensionId,
            long primaryPosition,
            long secondaryPosition,
            long subjectUuidMost,
            long subjectUuidLeast,
            long issuedGameTime,
            byte commandFlags) {
        if (nextOrderId == Long.MAX_VALUE) {
            throw new IllegalStateException("Army order id space exhausted");
        }
        long orderId = nextOrderId++;
        append(
                orderId,
                armyHandle,
                issuerFactionId,
                typeCode,
                dimensionId,
                primaryPosition,
                secondaryPosition,
                subjectUuidMost,
                subjectUuidLeast,
                issuedGameTime,
                commandFlags);
        bumpRevision();
        return orderId;
    }

    public boolean remove(long orderId) {
        for (int row = 0; row < size; row++) {
            if (orderIds[row] == orderId) {
                removeAt(row);
                return true;
            }
        }
        return false;
    }

    /** Swap-removes a known row without the otherwise linear order-id lookup. */
    public void removeAt(int row) {
        checkRow(row);
        removeRow(row);
        bumpRevision();
    }

    public long orderIdAt(int row) { checkRow(row); return orderIds[row]; }
    public int armyHandleAt(int row) { checkRow(row); return armyHandles[row]; }
    public byte typeCodeAt(int row) { checkRow(row); return typeCodes[row]; }
    public long issuedGameTimeAt(int row) { checkRow(row); return issuedGameTimes[row]; }

    public void clear() {
        Arrays.fill(orderIds, 0, size, 0L);
        Arrays.fill(armyHandles, 0, size, PackedArmyEcs.NO_ARMY);
        Arrays.fill(issuerFactionIds, 0, size, 0);
        Arrays.fill(typeCodes, 0, size, (byte) 0);
        Arrays.fill(dimensionIds, 0, size, 0);
        Arrays.fill(primaryPositions, 0, size, 0L);
        Arrays.fill(secondaryPositions, 0, size, 0L);
        Arrays.fill(subjectUuidMost, 0, size, 0L);
        Arrays.fill(subjectUuidLeast, 0, size, 0L);
        Arrays.fill(issuedGameTimes, 0, size, 0L);
        Arrays.fill(flags, 0, size, (byte) 0);
        size = 0;
        structuralVersion++;
        bumpRevision();
    }

    public Cursor newCursor() {
        return new Cursor(this);
    }

    void restore(
            long orderId,
            int armyHandle,
            int issuerFactionId,
            byte typeCode,
            int dimensionId,
            long primaryPosition,
            long secondaryPosition,
            long subjectUuidMost,
            long subjectUuidLeast,
            long issuedGameTime,
            byte commandFlags) {
        append(
                orderId,
                armyHandle,
                issuerFactionId,
                typeCode,
                dimensionId,
                primaryPosition,
                secondaryPosition,
                subjectUuidMost,
                subjectUuidLeast,
                issuedGameTime,
                commandFlags);
    }

    void restoreNextOrderId(long restoredNextOrderId) {
        if (restoredNextOrderId <= 0L) {
            throw new IllegalArgumentException("Next order id must be positive");
        }
        long minimum = 1L;
        for (int row = 0; row < size; row++) {
            if (orderIds[row] == Long.MAX_VALUE) {
                throw new IllegalArgumentException("Persisted order id cannot be incremented");
            }
            minimum = Math.max(minimum, orderIds[row] + 1L);
        }
        if (restoredNextOrderId < minimum) {
            throw new IllegalArgumentException(
                    "Next order id " + restoredNextOrderId + " precedes existing order id " + (minimum - 1L));
        }
        nextOrderId = restoredNextOrderId;
    }

    void restoreRevision(long restoredRevision) {
        if (restoredRevision < 0L) {
            throw new IllegalArgumentException("Command revision must be non-negative");
        }
        revision = restoredRevision;
    }

    private void bumpRevision() {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("Command revision space exhausted");
        }
        revision++;
    }

    private void append(
            long orderId,
            int armyHandle,
            int issuerFactionId,
            byte typeCode,
            int dimensionId,
            long primaryPosition,
            long secondaryPosition,
            long subjectUuidMost,
            long subjectUuidLeast,
            long issuedGameTime,
            byte commandFlags) {
        validate(
                orderId,
                armyHandle,
                issuerFactionId,
                typeCode,
                dimensionId,
                issuedGameTime,
                commandFlags);
        ensureCapacity(size + 1);
        int row = size++;
        orderIds[row] = orderId;
        armyHandles[row] = armyHandle;
        issuerFactionIds[row] = issuerFactionId;
        typeCodes[row] = typeCode;
        dimensionIds[row] = dimensionId;
        primaryPositions[row] = primaryPosition;
        secondaryPositions[row] = secondaryPosition;
        this.subjectUuidMost[row] = subjectUuidMost;
        this.subjectUuidLeast[row] = subjectUuidLeast;
        issuedGameTimes[row] = issuedGameTime;
        flags[row] = commandFlags;
        structuralVersion++;
    }

    private static void validate(
            long orderId,
            int armyHandle,
            int issuerFactionId,
            byte typeCode,
            int dimensionId,
            long issuedGameTime,
            byte commandFlags) {
        if (orderId <= 0L) {
            throw new IllegalArgumentException("Order id must be positive");
        }
        if (issuerFactionId < 0 || dimensionId < 0 || issuedGameTime < 0L) {
            throw new IllegalArgumentException("Faction, dimension and game time must be non-negative");
        }
        if (!ArmyOrderType.isValidCode(typeCode)) {
            throw new IllegalArgumentException("Unknown army order code: " + typeCode);
        }
    }

    private void removeRow(int row) {
        int last = --size;
        if (row != last) {
            orderIds[row] = orderIds[last];
            armyHandles[row] = armyHandles[last];
            issuerFactionIds[row] = issuerFactionIds[last];
            typeCodes[row] = typeCodes[last];
            dimensionIds[row] = dimensionIds[last];
            primaryPositions[row] = primaryPositions[last];
            secondaryPositions[row] = secondaryPositions[last];
            subjectUuidMost[row] = subjectUuidMost[last];
            subjectUuidLeast[row] = subjectUuidLeast[last];
            issuedGameTimes[row] = issuedGameTimes[last];
            flags[row] = flags[last];
        }
        orderIds[last] = 0L;
        armyHandles[last] = PackedArmyEcs.NO_ARMY;
        issuerFactionIds[last] = 0;
        typeCodes[last] = 0;
        dimensionIds[last] = 0;
        primaryPositions[last] = 0L;
        secondaryPositions[last] = 0L;
        subjectUuidMost[last] = 0L;
        subjectUuidLeast[last] = 0L;
        issuedGameTimes[last] = 0L;
        flags[last] = 0;
        structuralVersion++;
    }

    private void checkRow(int row) {
        if (row < 0 || row >= size) {
            throw new IndexOutOfBoundsException("Command row " + row + " outside 0.." + (size - 1));
        }
    }

    private void ensureCapacity(int required) {
        if (required <= orderIds.length) {
            return;
        }
        int current = orderIds.length;
        int grown = current < MIN_GROWTH ? MIN_GROWTH : current + (current >>> 1);
        int capacity = Math.max(required, grown);
        orderIds = Arrays.copyOf(orderIds, capacity);
        armyHandles = Arrays.copyOf(armyHandles, capacity);
        issuerFactionIds = Arrays.copyOf(issuerFactionIds, capacity);
        typeCodes = Arrays.copyOf(typeCodes, capacity);
        dimensionIds = Arrays.copyOf(dimensionIds, capacity);
        primaryPositions = Arrays.copyOf(primaryPositions, capacity);
        secondaryPositions = Arrays.copyOf(secondaryPositions, capacity);
        subjectUuidMost = Arrays.copyOf(subjectUuidMost, capacity);
        subjectUuidLeast = Arrays.copyOf(subjectUuidLeast, capacity);
        issuedGameTimes = Arrays.copyOf(issuedGameTimes, capacity);
        flags = Arrays.copyOf(flags, capacity);
    }

    public static final class Cursor {
        private final PackedCommandState owner;
        private int expectedStructuralVersion;
        private int nextRow;
        private int row = -1;

        private Cursor(PackedCommandState owner) {
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
            checkVersion();
            if (nextRow == owner.size) {
                row = -1;
                return false;
            }
            row = nextRow++;
            return true;
        }

        public long orderId() {
            checkActive();
            return owner.orderIds[row];
        }

        public int armyHandle() {
            checkActive();
            return owner.armyHandles[row];
        }

        public int issuerFactionId() {
            checkActive();
            return owner.issuerFactionIds[row];
        }

        public byte typeCode() {
            checkActive();
            return owner.typeCodes[row];
        }

        public int dimensionId() {
            checkActive();
            return owner.dimensionIds[row];
        }

        public long primaryPosition() {
            checkActive();
            return owner.primaryPositions[row];
        }

        public long secondaryPosition() {
            checkActive();
            return owner.secondaryPositions[row];
        }

        public long subjectUuidMost() {
            checkActive();
            return owner.subjectUuidMost[row];
        }

        public long subjectUuidLeast() {
            checkActive();
            return owner.subjectUuidLeast[row];
        }

        public long issuedGameTime() {
            checkActive();
            return owner.issuedGameTimes[row];
        }

        public byte flags() {
            checkActive();
            return owner.flags[row];
        }

        private void checkVersion() {
            if (expectedStructuralVersion != owner.structuralVersion) {
                throw new IllegalStateException("Command cursor invalidated by structural change; reset it");
            }
        }

        private void checkActive() {
            checkVersion();
            if (row < 0) {
                throw new IllegalStateException("Command cursor is not on a row");
            }
        }
    }
}
