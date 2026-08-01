package ru.kaiserroman.millenairearmies.server.logistics;

import java.util.Arrays;
import java.util.Objects;
import ru.kaiserroman.millenairearmies.model.ArmyOrderType;
import ru.kaiserroman.millenairearmies.model.LogisticsRequestStatus;
import ru.kaiserroman.millenairearmies.persistence.PackedCommandState;
import ru.kaiserroman.millenairearmies.persistence.PackedLogisticsState;

/**
 * Allocation-stable strategic supply planner.
 *
 * <p>This is deliberately not an inventory, entity, combat, target, or pathfinding system. A
 * bridge publishes absolute aggregate village stock and records dispatch/delivery facts. The
 * engine only reserves supplies, advances persisted logistics requests, and retires a completed
 * packed RESUPPLY command. It is ticked independently of players, so unloaded/no-player villages
 * can continue their coarse strategic simulation when their bridge publishes data.</p>
 *
 * <p>All hot state is primitive. Tick work is bounded by a FIFO event budget and one deterministic
 * request stripe. A zero-ingress server retains no request, event, or supply arrays; the first
 * explicit logistics ingress grows request storage in bounded tiers and allocates the fixed event
 * ring/supply table. This avoids reserving the configured worst case for a dormant subsystem.</p>
 */
public final class StrategicLogisticsEngine {
    public static final int PROGRESS_COMPLETE = 10_000;
    public static final long REQUEST_LIMIT_REACHED = -1L;
    public static final long NOT_RUNNING = -2L;

    private static final byte EVENT_SUPPLY_SNAPSHOT = 1;
    private static final byte EVENT_DISPATCH = 2;
    private static final byte EVENT_DELIVERY = 3;
    private static final byte EVENT_CANCEL = 4;
    private static final int MIN_REQUEST_TIER = 64;
    private static final int[] EMPTY_INTS = new int[0];
    private static final long[] EMPTY_LONGS = new long[0];
    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final long EMPTY_SUPPLY_HASH = 0xcbf29ce484222325L;

    private final int maximumRequests;
    private final int maximumSupplyKeys;
    private final int configuredEventCapacity;
    private final int requestStripes;
    private final int eventsPerTick;
    private PackedSupplyLedger supplies;
    private int[] reservationBuckets = EMPTY_INTS;
    private int[] reservedAmounts = EMPTY_INTS;
    private int[] inTransitAmounts = EMPTY_INTS;

    private byte[] eventTypes = EMPTY_BYTES;
    private int[] eventFactionIds = EMPTY_INTS;
    private int[] eventDimensionIds = EMPTY_INTS;
    private int[] eventItemKeys = EMPTY_INTS;
    private int[] eventAmounts = EMPTY_INTS;
    private long[] eventRequestIds = EMPTY_LONGS;
    private int eventHead;
    private int eventTail;
    private int eventCount;

    private PackedLogisticsState logistics;
    private PackedCommandState commands;
    private DirtyMarker dirtyMarker;
    private long lastGameTime = Long.MIN_VALUE;
    private int processedEvents;
    private int rejectedEvents;
    private int mutatedRows;
    private int requestStorageGrowths;
    private int lastTickWorkUnits;

    public StrategicLogisticsEngine(
            int maximumRequests,
            int maximumSupplyKeys,
            int eventCapacity,
            int requestStripes,
            int eventsPerTick) {
        if (maximumRequests <= 0
                || maximumSupplyKeys <= 0
                || eventCapacity <= 0
                || requestStripes <= 0
                || eventsPerTick <= 0) {
            throw new IllegalArgumentException("Logistics bounds must be positive");
        }
        this.maximumRequests = maximumRequests;
        this.maximumSupplyKeys = maximumSupplyKeys;
        this.configuredEventCapacity = eventCapacity;
        this.requestStripes = requestStripes;
        this.eventsPerTick = Math.min(eventsPerTick, eventCapacity);
    }

    public void start(PackedLogisticsState logistics, PackedCommandState commands, DirtyMarker dirtyMarker) {
        if (this.logistics != null) {
            throw new IllegalStateException("Strategic logistics engine is already attached");
        }
        this.logistics = Objects.requireNonNull(logistics, "logistics");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.dirtyMarker = Objects.requireNonNull(dirtyMarker, "dirtyMarker");
        if (logistics.size() > maximumRequests) {
            this.logistics = null;
            this.commands = null;
            this.dirtyMarker = null;
            throw new IllegalStateException("Persisted logistics rows exceed configured maximum");
        }
        resetRuntimeState();
        requestStorageGrowths = 0;
        if (logistics.size() != 0) {
            ensureRequestStorage(logistics.size());
        }
    }

    public void stop() {
        logistics = null;
        commands = null;
        dirtyMarker = null;
        resetRuntimeState();
        releaseRuntimeStorage();
    }

    public boolean isRunning() {
        return logistics != null;
    }

    /** Adds a persisted request immediately on the owning server thread. */
    public long requestSupply(
            int factionId,
            int requesterArmyHandle,
            int itemKey,
            int requiredAmount,
            int dimensionId,
            long destination,
            long createdGameTime,
            byte priority) {
        if (logistics == null) {
            return NOT_RUNNING;
        }
        if (logistics.size() == maximumRequests) {
            return REQUEST_LIMIT_REACHED;
        }
        ensureRequestStorage(logistics.size() + 1);
        long id = logistics.add(
                factionId,
                requesterArmyHandle,
                itemKey,
                requiredAmount,
                dimensionId,
                destination,
                createdGameTime,
                priority);
        dirtyMarker.markDirty();
        return id;
    }

    /** Queues an absolute stock snapshot. Reservations must not be subtracted by the producer. */
    public boolean publishSupply(int factionId, int dimensionId, int itemKey, int absoluteStock) {
        if (factionId < 0 || dimensionId < 0 || itemKey < 0 || absoluteStock < 0) {
            return false;
        }
        return enqueue(EVENT_SUPPLY_SNAPSHOT, factionId, dimensionId, itemKey, absoluteStock, 0L);
    }

    /**
     * Records a dispatch fact owned by an external persisted shipment system.
     * The current read-only Millenaire stock publisher deliberately never calls this method.
     */
    public boolean dispatch(long requestId) {
        return requestId > 0L && enqueue(EVENT_DISPATCH, 0, 0, 0, 0, requestId);
    }

    /**
     * Records a delivery fact owned by an external persisted shipment system; the engine itself
     * never inserts into a world inventory and the read-only publisher never calls this method.
     */
    public boolean deliver(long requestId, int amount) {
        return requestId > 0L && amount > 0 && enqueue(EVENT_DELIVERY, 0, 0, 0, amount, requestId);
    }

    public boolean cancel(long requestId) {
        return requestId > 0L && enqueue(EVENT_CANCEL, 0, 0, 0, 0, requestId);
    }

    /**
     * Processes a bounded event stripe followed by exactly one request stripe. Calling twice for
     * the same game time is a no-op, which protects deterministic replay from duplicate hooks.
     */
    public void tick(long gameTime) {
        if (logistics == null) {
            return;
        }
        if (gameTime < 0L) {
            throw new IllegalArgumentException("Game time must be non-negative");
        }
        if (gameTime == lastGameTime) {
            return;
        }
        if (lastGameTime != Long.MIN_VALUE && gameTime < lastGameTime) {
            throw new IllegalStateException("Game time moved backwards");
        }
        lastGameTime = gameTime;
        // PackedLogisticsState remains a public persistence API. Defensively absorb rows appended
        // by an external/future producer instead of indexing beyond the tiered runtime columns.
        ensureRequestStorage(logistics.size());

        int eventBudget = Math.min(eventsPerTick, eventCount);
        for (int event = 0; event < eventBudget; event++) {
            processHeadEvent();
        }

        int stripe = (int) (gameTime % requestStripes);
        int processedRows = 0;
        for (int row = stripe, size = logistics.size(); row < size; row += requestStripes) {
            reconcileRequest(row);
            processedRows++;
        }
        lastTickWorkUnits = eventBudget + processedRows;
    }

    public int queuedEventCount() {
        return eventCount;
    }

    public int processedEventCount() {
        return processedEvents;
    }

    public int rejectedEventCount() {
        return rejectedEvents;
    }

    public int mutatedRowCount() {
        return mutatedRows;
    }

    public int eventCapacity() {
        return eventTypes.length;
    }

    public int supplyTableCapacity() {
        return supplies == null ? 0 : supplies.capacity();
    }

    public int requestRuntimeCapacity() {
        return reservationBuckets.length;
    }

    public int requestStorageGrowthCount() {
        return requestStorageGrowths;
    }

    public int lastTickWorkUnits() {
        return lastTickWorkUnits;
    }

    public int reservedAmount(long requestId) {
        ensureExternalRowsCovered();
        int row = logistics == null ? -1 : logistics.findRow(requestId);
        return row < 0 ? 0 : reservedAmounts[row];
    }

    public int inTransitAmount(long requestId) {
        ensureExternalRowsCovered();
        int row = logistics == null ? -1 : logistics.findRow(requestId);
        return row < 0 ? 0 : inTransitAmounts[row];
    }

    /** Actual delivered progress, in 1/100 of one percent. */
    public int requestProgress(int row) {
        int required = logistics.requiredAmountAt(row);
        return ratio(logistics.fulfilledAmountAt(row), required);
    }

    /** Delivered plus reserved/in-transit planning progress, useful for a strategic UI. */
    public int requestPlannedProgress(int row) {
        ensureExternalRowsCovered();
        int required = logistics.requiredAmountAt(row);
        long planned = (long) logistics.fulfilledAmountAt(row) + reservedAmounts[row] + inTransitAmounts[row];
        return ratio(Math.min(required, planned), required);
    }

    /** Aggregates persisted requests for the army targeted by one packed command. */
    public int commandProgress(long orderId) {
        if (commands == null) {
            return -1;
        }
        int armyHandle = 0;
        long issuedGameTime = 0L;
        boolean found = false;
        for (int row = 0, size = commands.size(); row < size; row++) {
            if (commands.orderIdAt(row) == orderId) {
                armyHandle = commands.armyHandleAt(row);
                issuedGameTime = commands.issuedGameTimeAt(row);
                found = true;
                break;
            }
        }
        if (!found) {
            return -1;
        }
        long required = 0L;
        long fulfilled = 0L;
        for (int row = 0, size = logistics.size(); row < size; row++) {
            if (logistics.requesterArmyHandleAt(row) != armyHandle
                    || logistics.createdGameTimeAt(row) < issuedGameTime
                    || logistics.statusCodeAt(row) == LogisticsRequestStatus.CANCELLED.code()) {
                continue;
            }
            required += logistics.requiredAmountAt(row);
            fulfilled += logistics.fulfilledAmountAt(row);
        }
        return required == 0L ? 0 : ratio(fulfilled, required);
    }

    /** Stable hash used by deterministic replay tests and diagnostics. */
    public long deterministicHash() {
        ensureExternalRowsCovered();
        long hash = supplies == null ? EMPTY_SUPPLY_HASH : supplies.deterministicHash();
        if (logistics == null) {
            return hash;
        }
        for (int row = 0, size = logistics.size(); row < size; row++) {
            hash = mixHash(hash, logistics.requestIdAt(row));
            hash = mixHash(hash, logistics.fulfilledAmountAt(row));
            hash = mixHash(hash, logistics.statusCodeAt(row));
            hash = mixHash(hash, reservedAmounts[row]);
            hash = mixHash(hash, inTransitAmounts[row]);
        }
        hash = mixHash(hash, commands.size());
        for (int row = 0, size = commands.size(); row < size; row++) {
            hash = mixHash(hash, commands.orderIdAt(row));
        }
        return hash;
    }

    private boolean enqueue(
            byte type, int factionId, int dimensionId, int itemKey, int amount, long requestId) {
        if (logistics == null) {
            rejectedEvents++;
            return false;
        }
        ensureEventStorage();
        if (type == EVENT_SUPPLY_SNAPSHOT && supplies == null) {
            supplies = new PackedSupplyLedger(maximumSupplyKeys);
        }
        if (eventCount == eventTypes.length) {
            rejectedEvents++;
            return false;
        }
        int row = eventTail;
        eventTypes[row] = type;
        eventFactionIds[row] = factionId;
        eventDimensionIds[row] = dimensionId;
        eventItemKeys[row] = itemKey;
        eventAmounts[row] = amount;
        eventRequestIds[row] = requestId;
        eventTail = eventTail + 1 == eventTypes.length ? 0 : eventTail + 1;
        eventCount++;
        return true;
    }

    private void processHeadEvent() {
        int event = eventHead;
        byte type = eventTypes[event];
        boolean accepted = switch (type) {
            case EVENT_SUPPLY_SNAPSHOT -> supplies.publish(
                            eventFactionIds[event],
                            eventDimensionIds[event],
                            eventItemKeys[event],
                            eventAmounts[event])
                    != PackedSupplyLedger.NO_BUCKET;
            case EVENT_DISPATCH -> processDispatch(eventRequestIds[event]);
            case EVENT_DELIVERY -> processDelivery(eventRequestIds[event], eventAmounts[event]);
            case EVENT_CANCEL -> processCancel(eventRequestIds[event]);
            default -> false;
        };
        if (!accepted) {
            rejectedEvents++;
        }
        processedEvents++;
        eventTypes[event] = 0;
        eventRequestIds[event] = 0L;
        eventHead = eventHead + 1 == eventTypes.length ? 0 : eventHead + 1;
        eventCount--;
    }

    private void reconcileRequest(int row) {
        byte status = logistics.statusCodeAt(row);
        if (status == LogisticsRequestStatus.FULFILLED.code()
                || status == LogisticsRequestStatus.CANCELLED.code()) {
            releaseReservation(row);
            inTransitAmounts[row] = 0;
            return;
        }
        if (status == LogisticsRequestStatus.IN_TRANSIT.code()) {
            return;
        }
        if (supplies == null) {
            return;
        }

        int bucket = reservationBuckets[row];
        if (bucket == PackedSupplyLedger.NO_BUCKET) {
            bucket = supplies.find(
                    logistics.factionIdAt(row), logistics.dimensionIdAt(row), logistics.itemKeyAt(row));
            if (bucket == PackedSupplyLedger.NO_BUCKET) {
                return;
            }
            reservationBuckets[row] = bucket;
        }

        int remaining = logistics.requiredAmountAt(row) - logistics.fulfilledAmountAt(row);
        int needed = remaining - reservedAmounts[row];
        if (needed > 0) {
            reservedAmounts[row] += supplies.reserve(bucket, needed);
        }
        if (reservedAmounts[row] == remaining
                && status == LogisticsRequestStatus.PENDING.code()
                && logistics.transitionAt(row, LogisticsRequestStatus.ASSIGNED)) {
            mutatedRows++;
            dirtyMarker.markDirty();
        }
    }

    private boolean processDispatch(long requestId) {
        int row = logistics.findRow(requestId);
        if (row < 0 || logistics.statusCodeAt(row) != LogisticsRequestStatus.ASSIGNED.code()) {
            return false;
        }
        int bucket = reservationBuckets[row];
        int amount = reservedAmounts[row];
        int remaining = logistics.requiredAmountAt(row) - logistics.fulfilledAmountAt(row);
        if (supplies == null
                || bucket == PackedSupplyLedger.NO_BUCKET
                || amount != remaining
                || !supplies.dispatch(bucket, amount)) {
            return false;
        }
        reservedAmounts[row] = 0;
        reservationBuckets[row] = PackedSupplyLedger.NO_BUCKET;
        inTransitAmounts[row] = amount;
        if (!logistics.transitionAt(row, LogisticsRequestStatus.IN_TRANSIT)) {
            throw new IllegalStateException("Assigned logistics request rejected in-transit transition");
        }
        mutatedRows++;
        dirtyMarker.markDirty();
        return true;
    }

    private boolean processDelivery(long requestId, int requestedAmount) {
        int row = logistics.findRow(requestId);
        if (row < 0 || logistics.statusCodeAt(row) != LogisticsRequestStatus.IN_TRANSIT.code()) {
            return false;
        }
        int remaining = logistics.requiredAmountAt(row) - logistics.fulfilledAmountAt(row);
        // An IN_TRANSIT row restored after restart has no runtime shipment counter. The real
        // inventory bridge is authoritative, so its recorded delivery remains valid.
        int trackedTransit = inTransitAmounts[row];
        int acceptedLimit = trackedTransit == 0 ? remaining : Math.min(remaining, trackedTransit);
        int accepted = logistics.addFulfilledAt(row, Math.min(requestedAmount, acceptedLimit));
        if (accepted == 0) {
            return false;
        }
        if (trackedTransit != 0) {
            inTransitAmounts[row] -= accepted;
        }
        mutatedRows++;
        dirtyMarker.markDirty();
        if (logistics.statusCodeAt(row) == LogisticsRequestStatus.FULFILLED.code()) {
            inTransitAmounts[row] = 0;
            completeOldestResupplyCommand(logistics.requesterArmyHandleAt(row));
        }
        return true;
    }

    private boolean processCancel(long requestId) {
        int row = logistics.findRow(requestId);
        if (row < 0
                || logistics.statusCodeAt(row) == LogisticsRequestStatus.CANCELLED.code()
                || logistics.statusCodeAt(row) == LogisticsRequestStatus.FULFILLED.code()
                || !logistics.transitionAt(row, LogisticsRequestStatus.CANCELLED)) {
            return false;
        }
        releaseReservation(row);
        // Dispatched stock is not magically returned; the bridge decides what happened to cargo.
        inTransitAmounts[row] = 0;
        mutatedRows++;
        dirtyMarker.markDirty();
        return true;
    }

    private void releaseReservation(int row) {
        int bucket = reservationBuckets[row];
        int amount = reservedAmounts[row];
        if (supplies != null && bucket != PackedSupplyLedger.NO_BUCKET && amount != 0) {
            supplies.release(bucket, amount);
        }
        reservationBuckets[row] = PackedSupplyLedger.NO_BUCKET;
        reservedAmounts[row] = 0;
    }

    private void completeOldestResupplyCommand(int armyHandle) {
        int oldestRow = -1;
        long oldestOrder = Long.MAX_VALUE;
        long oldestIssuedGameTime = 0L;
        for (int row = 0, size = commands.size(); row < size; row++) {
            if (commands.armyHandleAt(row) == armyHandle
                    && commands.typeCodeAt(row) == ArmyOrderType.RESUPPLY.code()
                    && commands.orderIdAt(row) < oldestOrder) {
                oldestOrder = commands.orderIdAt(row);
                oldestRow = row;
                oldestIssuedGameTime = commands.issuedGameTimeAt(row);
            }
        }
        if (oldestRow >= 0 && !hasOpenRequestForArmySince(armyHandle, oldestIssuedGameTime)) {
            commands.removeAt(oldestRow);
            dirtyMarker.markDirty();
        }
    }

    private boolean hasOpenRequestForArmySince(int armyHandle, long issuedGameTime) {
        for (int row = 0, size = logistics.size(); row < size; row++) {
            byte status = logistics.statusCodeAt(row);
            if (logistics.requesterArmyHandleAt(row) == armyHandle
                    && logistics.createdGameTimeAt(row) >= issuedGameTime
                    && status != LogisticsRequestStatus.FULFILLED.code()
                    && status != LogisticsRequestStatus.CANCELLED.code()) {
                return true;
            }
        }
        return false;
    }

    private void resetRuntimeState() {
        if (supplies != null) {
            supplies.clear();
        }
        Arrays.fill(reservationBuckets, PackedSupplyLedger.NO_BUCKET);
        Arrays.fill(reservedAmounts, 0);
        Arrays.fill(inTransitAmounts, 0);
        Arrays.fill(eventTypes, (byte) 0);
        eventHead = 0;
        eventTail = 0;
        eventCount = 0;
        lastGameTime = Long.MIN_VALUE;
        processedEvents = 0;
        rejectedEvents = 0;
        mutatedRows = 0;
        lastTickWorkUnits = 0;
    }

    private void ensureExternalRowsCovered() {
        if (logistics != null && logistics.size() > reservationBuckets.length) {
            ensureRequestStorage(logistics.size());
        }
    }

    private void ensureRequestStorage(int required) {
        if (required <= reservationBuckets.length) {
            return;
        }
        if (required > maximumRequests) {
            throw new IllegalStateException("Logistics request storage exceeded configured maximum");
        }
        int current = reservationBuckets.length;
        int capacity = current == 0 ? Math.min(maximumRequests, MIN_REQUEST_TIER) : current;
        while (capacity < required) {
            int grown = capacity + Math.max(1, capacity >>> 1);
            capacity = Math.min(maximumRequests, Math.max(required, grown));
        }
        reservationBuckets = Arrays.copyOf(reservationBuckets, capacity);
        Arrays.fill(reservationBuckets, current, capacity, PackedSupplyLedger.NO_BUCKET);
        reservedAmounts = Arrays.copyOf(reservedAmounts, capacity);
        inTransitAmounts = Arrays.copyOf(inTransitAmounts, capacity);
        logistics.reserve(capacity);
        requestStorageGrowths++;
    }

    private void ensureEventStorage() {
        if (eventTypes.length != 0) {
            return;
        }
        eventTypes = new byte[configuredEventCapacity];
        eventFactionIds = new int[configuredEventCapacity];
        eventDimensionIds = new int[configuredEventCapacity];
        eventItemKeys = new int[configuredEventCapacity];
        eventAmounts = new int[configuredEventCapacity];
        eventRequestIds = new long[configuredEventCapacity];
    }

    private void releaseRuntimeStorage() {
        supplies = null;
        reservationBuckets = EMPTY_INTS;
        reservedAmounts = EMPTY_INTS;
        inTransitAmounts = EMPTY_INTS;
        eventTypes = EMPTY_BYTES;
        eventFactionIds = EMPTY_INTS;
        eventDimensionIds = EMPTY_INTS;
        eventItemKeys = EMPTY_INTS;
        eventAmounts = EMPTY_INTS;
        eventRequestIds = EMPTY_LONGS;
        requestStorageGrowths = 0;
    }

    private static int ratio(long numerator, long denominator) {
        if (denominator <= 0L || numerator <= 0L) {
            return 0;
        }
        if (numerator >= denominator) {
            return PROGRESS_COMPLETE;
        }
        if (numerator <= Long.MAX_VALUE / PROGRESS_COMPLETE) {
            return (int) (numerator * PROGRESS_COMPLETE / denominator);
        }
        // Only possible for aggregate diagnostics at extreme configured limits; strictfp IEEE-754
        // conversion is deterministic and avoids an allocating BigInteger slow path.
        return (int) ((double) numerator * PROGRESS_COMPLETE / (double) denominator);
    }

    private static long mixHash(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }

    @FunctionalInterface
    public interface DirtyMarker {
        void markDirty();
    }
}
