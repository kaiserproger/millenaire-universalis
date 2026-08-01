package ru.kaiserroman.millenairearmies.persistence;

import java.util.Arrays;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.model.LogisticsRequestStatus;

/**
 * Primitive logistics request storage with per-row revisions for future delta sync. Item and
 * dimension ids refer to the SavedData-owned stable dictionaries, not runtime registry ordinals.
 */
public final class PackedLogisticsState {
    private static final int MIN_GROWTH = 8;
    private static final long[] EMPTY_LONGS = new long[0];
    private static final int[] EMPTY_INTS = new int[0];
    private static final byte[] EMPTY_BYTES = new byte[0];

    private int size;
    private int structuralVersion;
    private long nextRequestId = 1L;
    private long nextRevision = 1L;
    private long[] requestIds = EMPTY_LONGS;
    private int[] factionIds = EMPTY_INTS;
    private int[] requesterArmyHandles = EMPTY_INTS;
    private int[] itemKeys = EMPTY_INTS;
    private int[] requiredAmounts = EMPTY_INTS;
    private int[] fulfilledAmounts = EMPTY_INTS;
    private int[] dimensionIds = EMPTY_INTS;
    private long[] destinations = EMPTY_LONGS;
    private long[] createdGameTimes = EMPTY_LONGS;
    private byte[] priorities = EMPTY_BYTES;
    private byte[] statusCodes = EMPTY_BYTES;
    private long[] revisions = EMPTY_LONGS;

    public PackedLogisticsState() {
    }

    public PackedLogisticsState(int expectedRequests) {
        reserve(expectedRequests);
    }

    public int size() {
        return size;
    }

    /** Current primitive row capacity. Exposed for bounded-engine admission and allocation tests. */
    public int capacity() {
        return requestIds.length;
    }

    public long nextRequestId() {
        return nextRequestId;
    }

    public long nextRevision() {
        return nextRevision;
    }

    public void reserve(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Negative logistics capacity: " + capacity);
        }
        ensureCapacity(capacity);
    }

    public long add(
            int factionId,
            int requesterArmyHandle,
            int itemKey,
            int requiredAmount,
            int dimensionId,
            long destination,
            long createdGameTime,
            byte priority) {
        if (nextRequestId == Long.MAX_VALUE) {
            throw new IllegalStateException("Logistics request id space exhausted");
        }
        long requestId = nextRequestId++;
        append(
                requestId,
                factionId,
                requesterArmyHandle,
                itemKey,
                requiredAmount,
                0,
                dimensionId,
                destination,
                createdGameTime,
                priority,
                LogisticsRequestStatus.PENDING.code(),
                claimRevision());
        return requestId;
    }

    public int addFulfilled(long requestId, int amount) {
        int row = row(requestId);
        return row < 0 ? 0 : addFulfilledAt(row, amount);
    }

    /** In-capacity row mutation used by the striped logistics engine; performs no id scan. */
    public int addFulfilledAt(int row, int amount) {
        checkRow(row);
        if (amount <= 0 || statusCodes[row] == LogisticsRequestStatus.CANCELLED.code()) {
            return 0;
        }
        int accepted = Math.min(amount, requiredAmounts[row] - fulfilledAmounts[row]);
        if (accepted == 0) {
            return 0;
        }
        fulfilledAmounts[row] += accepted;
        if (fulfilledAmounts[row] == requiredAmounts[row]) {
            statusCodes[row] = LogisticsRequestStatus.FULFILLED.code();
        }
        revisions[row] = claimRevision();
        return accepted;
    }

    public boolean transition(long requestId, LogisticsRequestStatus next) {
        int row = row(requestId);
        return row >= 0 && transitionAt(row, next);
    }

    /** In-capacity row mutation used by the striped logistics engine; performs no id scan. */
    public boolean transitionAt(int row, LogisticsRequestStatus next) {
        checkRow(row);
        if (next == null || !canTransition(statusCodes[row], next.code())) {
            return false;
        }
        if (statusCodes[row] != next.code()) {
            statusCodes[row] = next.code();
            revisions[row] = claimRevision();
        }
        return true;
    }

    public int findRow(long requestId) {
        return row(requestId);
    }

    public long requestIdAt(int row) { checkRow(row); return requestIds[row]; }
    public int factionIdAt(int row) { checkRow(row); return factionIds[row]; }
    public int requesterArmyHandleAt(int row) { checkRow(row); return requesterArmyHandles[row]; }
    public int itemKeyAt(int row) { checkRow(row); return itemKeys[row]; }
    public int requiredAmountAt(int row) { checkRow(row); return requiredAmounts[row]; }
    public int fulfilledAmountAt(int row) { checkRow(row); return fulfilledAmounts[row]; }
    public int dimensionIdAt(int row) { checkRow(row); return dimensionIds[row]; }
    public long createdGameTimeAt(int row) { checkRow(row); return createdGameTimes[row]; }
    public byte statusCodeAt(int row) { checkRow(row); return statusCodes[row]; }

    /** Swap-removes a known row; used when its owning army is permanently disbanded. */
    public void removeAt(int row) {
        checkRow(row);
        int last = --size;
        if (row != last) {
            requestIds[row] = requestIds[last];
            factionIds[row] = factionIds[last];
            requesterArmyHandles[row] = requesterArmyHandles[last];
            itemKeys[row] = itemKeys[last];
            requiredAmounts[row] = requiredAmounts[last];
            fulfilledAmounts[row] = fulfilledAmounts[last];
            dimensionIds[row] = dimensionIds[last];
            destinations[row] = destinations[last];
            createdGameTimes[row] = createdGameTimes[last];
            priorities[row] = priorities[last];
            statusCodes[row] = statusCodes[last];
            revisions[row] = revisions[last];
        }
        requestIds[last] = 0L;
        factionIds[last] = 0;
        requesterArmyHandles[last] = PackedArmyEcs.NO_ARMY;
        itemKeys[last] = 0;
        requiredAmounts[last] = 0;
        fulfilledAmounts[last] = 0;
        dimensionIds[last] = 0;
        destinations[last] = 0L;
        createdGameTimes[last] = 0L;
        priorities[last] = 0;
        statusCodes[last] = 0;
        revisions[last] = 0L;
        structuralVersion++;
    }

    public Cursor newCursor() {
        return new Cursor(this);
    }

    void restore(
            long requestId,
            int factionId,
            int requesterArmyHandle,
            int itemKey,
            int requiredAmount,
            int fulfilledAmount,
            int dimensionId,
            long destination,
            long createdGameTime,
            byte priority,
            byte statusCode,
            long revision) {
        append(
                requestId,
                factionId,
                requesterArmyHandle,
                itemKey,
                requiredAmount,
                fulfilledAmount,
                dimensionId,
                destination,
                createdGameTime,
                priority,
                statusCode,
                revision);
    }

    void restoreCounters(long restoredNextRequestId, long restoredNextRevision) {
        if (restoredNextRequestId <= 0L || restoredNextRevision <= 0L) {
            throw new IllegalArgumentException("Next logistics identity and revision must be positive");
        }
        long minimumRequest = 1L;
        long minimumRevision = 1L;
        for (int row = 0; row < size; row++) {
            if (requestIds[row] == Long.MAX_VALUE || revisions[row] == Long.MAX_VALUE) {
                throw new IllegalArgumentException("Persisted logistics identity or revision cannot be incremented");
            }
            minimumRequest = Math.max(minimumRequest, requestIds[row] + 1L);
            minimumRevision = Math.max(minimumRevision, revisions[row] + 1L);
        }
        if (restoredNextRequestId < minimumRequest || restoredNextRevision < minimumRevision) {
            throw new IllegalArgumentException("Persisted logistics counters precede stored rows");
        }
        nextRequestId = restoredNextRequestId;
        nextRevision = restoredNextRevision;
    }

    private void append(
            long requestId,
            int factionId,
            int requesterArmyHandle,
            int itemKey,
            int requiredAmount,
            int fulfilledAmount,
            int dimensionId,
            long destination,
            long createdGameTime,
            byte priority,
            byte statusCode,
            long revision) {
        validate(
                requestId,
                factionId,
                itemKey,
                requiredAmount,
                fulfilledAmount,
                dimensionId,
                createdGameTime,
                priority,
                statusCode,
                revision);
        ensureCapacity(size + 1);
        int row = size++;
        requestIds[row] = requestId;
        factionIds[row] = factionId;
        requesterArmyHandles[row] = requesterArmyHandle;
        itemKeys[row] = itemKey;
        requiredAmounts[row] = requiredAmount;
        fulfilledAmounts[row] = fulfilledAmount;
        dimensionIds[row] = dimensionId;
        destinations[row] = destination;
        createdGameTimes[row] = createdGameTime;
        priorities[row] = priority;
        statusCodes[row] = statusCode;
        revisions[row] = revision;
        structuralVersion++;
    }

    private static void validate(
            long requestId,
            int factionId,
            int itemKey,
            int requiredAmount,
            int fulfilledAmount,
            int dimensionId,
            long createdGameTime,
            byte priority,
            byte statusCode,
            long revision) {
        if (requestId <= 0L || factionId < 0 || itemKey < 0 || dimensionId < 0 || createdGameTime < 0L) {
            throw new IllegalArgumentException("Logistics ids and game time are invalid");
        }
        if (requiredAmount <= 0 || fulfilledAmount < 0 || fulfilledAmount > requiredAmount) {
            throw new IllegalArgumentException("Fulfilled amount must be within 0..required amount");
        }
        if (priority < 0 || priority > 7 || !LogisticsRequestStatus.isValidCode(statusCode)) {
            throw new IllegalArgumentException("Invalid logistics priority or status");
        }
        if (statusCode == LogisticsRequestStatus.FULFILLED.code() && fulfilledAmount != requiredAmount) {
            throw new IllegalArgumentException("Fulfilled request is incomplete");
        }
        if (fulfilledAmount == requiredAmount
                && statusCode != LogisticsRequestStatus.FULFILLED.code()
                && statusCode != LogisticsRequestStatus.CANCELLED.code()) {
            throw new IllegalArgumentException("Complete request has a non-terminal status");
        }
        if (revision <= 0L) {
            throw new IllegalArgumentException("Logistics revision must be positive");
        }
    }

    private int row(long requestId) {
        // Requests are append-only and normally use contiguous ids, so the hot event path is O(1).
        // Keep the scan fallback for defensive compatibility with older/reordered persisted data.
        long hinted = requestId - 1L;
        if (hinted >= 0L && hinted < size && requestIds[(int) hinted] == requestId) {
            return (int) hinted;
        }
        for (int row = 0; row < size; row++) {
            if (requestIds[row] == requestId) {
                return row;
            }
        }
        return -1;
    }

    private void checkRow(int row) {
        if (row < 0 || row >= size) {
            throw new IndexOutOfBoundsException("Logistics row " + row + " outside 0.." + (size - 1));
        }
    }

    private long claimRevision() {
        if (nextRevision == Long.MAX_VALUE) {
            throw new IllegalStateException("Logistics revision space exhausted");
        }
        return nextRevision++;
    }

    private static boolean canTransition(byte current, byte next) {
        if (current == next) {
            return true;
        }
        if (next == LogisticsRequestStatus.CANCELLED.code()) {
            return current != LogisticsRequestStatus.FULFILLED.code()
                    && current != LogisticsRequestStatus.CANCELLED.code();
        }
        return (current == LogisticsRequestStatus.PENDING.code() && next == LogisticsRequestStatus.ASSIGNED.code())
                || (current == LogisticsRequestStatus.ASSIGNED.code()
                        && next == LogisticsRequestStatus.IN_TRANSIT.code());
    }

    private void ensureCapacity(int required) {
        if (required <= requestIds.length) {
            return;
        }
        int current = requestIds.length;
        int capacity = Math.max(required, current < MIN_GROWTH ? MIN_GROWTH : current + (current >>> 1));
        requestIds = Arrays.copyOf(requestIds, capacity);
        factionIds = Arrays.copyOf(factionIds, capacity);
        requesterArmyHandles = Arrays.copyOf(requesterArmyHandles, capacity);
        itemKeys = Arrays.copyOf(itemKeys, capacity);
        requiredAmounts = Arrays.copyOf(requiredAmounts, capacity);
        fulfilledAmounts = Arrays.copyOf(fulfilledAmounts, capacity);
        dimensionIds = Arrays.copyOf(dimensionIds, capacity);
        destinations = Arrays.copyOf(destinations, capacity);
        createdGameTimes = Arrays.copyOf(createdGameTimes, capacity);
        priorities = Arrays.copyOf(priorities, capacity);
        statusCodes = Arrays.copyOf(statusCodes, capacity);
        revisions = Arrays.copyOf(revisions, capacity);
    }

    public static final class Cursor {
        private final PackedLogisticsState owner;
        private int expectedStructuralVersion;
        private int nextRow;
        private int row = -1;

        private Cursor(PackedLogisticsState owner) {
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

        public long requestId() { checkActive(); return owner.requestIds[row]; }
        public int factionId() { checkActive(); return owner.factionIds[row]; }
        public int requesterArmyHandle() { checkActive(); return owner.requesterArmyHandles[row]; }
        public int itemKey() { checkActive(); return owner.itemKeys[row]; }
        public int requiredAmount() { checkActive(); return owner.requiredAmounts[row]; }
        public int fulfilledAmount() { checkActive(); return owner.fulfilledAmounts[row]; }
        public int dimensionId() { checkActive(); return owner.dimensionIds[row]; }
        public long destination() { checkActive(); return owner.destinations[row]; }
        public long createdGameTime() { checkActive(); return owner.createdGameTimes[row]; }
        public byte priority() { checkActive(); return owner.priorities[row]; }
        public byte statusCode() { checkActive(); return owner.statusCodes[row]; }
        public long revision() { checkActive(); return owner.revisions[row]; }

        private void checkVersion() {
            if (expectedStructuralVersion != owner.structuralVersion) {
                throw new IllegalStateException("Logistics cursor invalidated by structural change; reset it");
            }
        }

        private void checkActive() {
            checkVersion();
            if (row < 0) {
                throw new IllegalStateException("Logistics cursor is not on a row");
            }
        }
    }
}
