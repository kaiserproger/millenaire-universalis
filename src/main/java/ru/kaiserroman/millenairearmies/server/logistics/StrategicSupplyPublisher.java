package ru.kaiserroman.millenairearmies.server.logistics;

import java.util.Arrays;
import java.util.Objects;
import ru.kaiserroman.millenairearmies.model.LogisticsRequestStatus;
import ru.kaiserroman.millenairearmies.persistence.PackedLogisticsState;

/**
 * Demand-driven, bounded publisher between physical stores and {@link StrategicLogisticsEngine}.
 *
 * <p>Only keys referenced by persisted logistics requests are tracked. New request rows are
 * discovered in a bounded stripe, while inventory reconciliation is driven by a fixed primitive
 * dirty queue plus a low-frequency safety sweep. Once constructed, an idle tick allocates no
 * objects and scans neither villages nor containers. This publisher only reports stock: it never
 * removes items or reports dispatch/delivery. Those mutations remain disabled until a persisted,
 * idempotent shipment/WAL and component-aware physical courier inventory exist.</p>
 */
public final class StrategicSupplyPublisher {
    private static final byte EMPTY = 0;
    private static final byte OCCUPIED = 1;
    private static final int UNOBSERVED_ROW = -2;
    private static final int NO_ACTIVE_KEY = -1;

    private final StrategicLogisticsEngine engine;
    private final PackedLogisticsState requests;
    private final SupplyInventoryAccess inventories;
    private final int maximumKeys;
    private final int requestRowsPerTick;
    private final int keysPerTick;
    private final int safetySweepIntervalTicks;
    private final int mask;

    private final byte[] occupied;
    private final byte[] queued;
    private final byte[] published;
    private final int[] factionIds;
    private final int[] dimensionIds;
    private final int[] itemKeys;
    private final int[] lastPublishedStock;
    private final int[] activeRequestCounts;
    private final int[] dirtyQueue;
    private int[] requestKeyBuckets;

    private int size;
    private int activeKeys;
    private int discoveredRequestRows;
    private int statusScanRow;
    private int dirtyHead;
    private int dirtyTail;
    private int dirtyCount;
    private long lastGameTime = Long.MIN_VALUE;
    private long nextSafetySweepGameTime;
    private long reconciliationRevision;
    private int rejectedKeys;
    private int unavailableReads;
    private int acceptedSnapshots;
    private int unchangedSnapshots;

    public StrategicSupplyPublisher(
            StrategicLogisticsEngine engine,
            PackedLogisticsState requests,
            SupplyInventoryAccess inventories,
            int maximumKeys,
            int requestRowsPerTick,
            int keysPerTick,
            int safetySweepIntervalTicks) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.inventories = Objects.requireNonNull(inventories, "inventories");
        if (maximumKeys <= 0 || requestRowsPerTick <= 0 || keysPerTick <= 0 || safetySweepIntervalTicks <= 0) {
            throw new IllegalArgumentException("Supply publisher bounds must be positive");
        }
        this.maximumKeys = maximumKeys;
        this.requestRowsPerTick = requestRowsPerTick;
        this.keysPerTick = keysPerTick;
        this.safetySweepIntervalTicks = safetySweepIntervalTicks;

        int required = (int) Math.min(1L << 30, ((long) maximumKeys * 10L + 6L) / 7L);
        int capacity = 1;
        while (capacity < required) {
            capacity <<= 1;
        }
        mask = capacity - 1;
        occupied = new byte[capacity];
        queued = new byte[capacity];
        published = new byte[capacity];
        factionIds = new int[capacity];
        dimensionIds = new int[capacity];
        itemKeys = new int[capacity];
        lastPublishedStock = new int[capacity];
        activeRequestCounts = new int[capacity];
        dirtyQueue = new int[maximumKeys];
        requestKeyBuckets = new int[requests.size() == 0 ? 0 : requests.capacity()];
        Arrays.fill(requestKeyBuckets, UNOBSERVED_ROW);
    }

    /**
     * Discovers new demand and reconciles a bounded number of dirty aggregate keys.
     * Duplicate calls for one game time are ignored.
     */
    public void tick(long gameTime) {
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

        if (discoverDemand()) {
            beginInventoryReconciliation();
        }
        retireTerminalDemand();
        if (gameTime >= nextSafetySweepGameTime) {
            requestReconcileAll();
            nextSafetySweepGameTime = saturatedAdd(gameTime, safetySweepIntervalTicks);
        }
        reconcileDirtyKeys();
    }

    /** Marks one already-known aggregate key dirty without allocating or duplicating queue work. */
    public boolean requestReconcile(int factionId, int dimensionId, int itemKey) {
        int bucket = find(factionId, dimensionId, itemKey);
        if (bucket < 0 || activeRequestCounts[bucket] == 0) {
            return false;
        }
        beginInventoryReconciliation();
        return enqueueDirty(bucket);
    }

    /** Queues every demanded key once; intended for village-index or inventory revision hooks. */
    public void requestReconcileAll() {
        if (activeKeys == 0) {
            return;
        }
        beginInventoryReconciliation();
        for (int bucket = 0; bucket < occupied.length; bucket++) {
            if (occupied[bucket] == OCCUPIED && activeRequestCounts[bucket] != 0) {
                enqueueDirty(bucket);
            }
        }
    }

    public int trackedKeyCount() {
        return size;
    }

    public int activeKeyCount() {
        return activeKeys;
    }

    public int queuedKeyCount() {
        return dirtyCount;
    }

    public int rejectedKeyCount() {
        return rejectedKeys;
    }

    public int unavailableReadCount() {
        return unavailableReads;
    }

    public int acceptedSnapshotCount() {
        return acceptedSnapshots;
    }

    public int unchangedSnapshotCount() {
        return unchangedSnapshots;
    }

    public int tableCapacity() {
        return occupied.length;
    }

    public int dirtyQueueCapacity() {
        return dirtyQueue.length;
    }

    private boolean discoverDemand() {
        boolean activated = false;
        int end = Math.min(requests.size(), discoveredRequestRows + requestRowsPerTick);
        ensureRequestRowCapacity(end);
        while (discoveredRequestRows < end) {
            int row = discoveredRequestRows++;
            byte status = requests.statusCodeAt(row);
            if (isTerminal(status)) {
                requestKeyBuckets[row] = NO_ACTIVE_KEY;
                continue;
            }
            int bucket = registerKey(
                    requests.factionIdAt(row),
                    requests.dimensionIdAt(row),
                    requests.itemKeyAt(row));
            requestKeyBuckets[row] = bucket;
            if (bucket >= 0 && activeRequestCounts[bucket]++ == 0) {
                activeKeys++;
                enqueueDirty(bucket);
                activated = true;
            }
        }
        return activated;
    }

    private void ensureRequestRowCapacity(int required) {
        if (required <= requestKeyBuckets.length) {
            return;
        }
        int capacity = Math.max(required, requests.capacity());
        int oldLength = requestKeyBuckets.length;
        requestKeyBuckets = Arrays.copyOf(requestKeyBuckets, capacity);
        Arrays.fill(requestKeyBuckets, oldLength, capacity, UNOBSERVED_ROW);
    }

    private void retireTerminalDemand() {
        int rows = discoveredRequestRows;
        if (rows == 0) {
            return;
        }
        int budget = Math.min(requestRowsPerTick, rows);
        for (int scanned = 0; scanned < budget; scanned++) {
            if (statusScanRow == rows) {
                statusScanRow = 0;
            }
            int row = statusScanRow++;
            int bucket = requestKeyBuckets[row];
            if (bucket < 0 || !isTerminal(requests.statusCodeAt(row))) {
                continue;
            }
            requestKeyBuckets[row] = NO_ACTIVE_KEY;
            int remaining = --activeRequestCounts[bucket];
            if (remaining < 0) {
                throw new IllegalStateException("Supply key active request count underflow");
            }
            if (remaining == 0) {
                activeKeys--;
            }
        }
    }

    private void reconcileDirtyKeys() {
        int budget = Math.min(keysPerTick, dirtyCount);
        for (int processed = 0; processed < budget; processed++) {
            int bucket = popDirty();
            if (activeRequestCounts[bucket] == 0) {
                continue;
            }
            int stock = inventories.absoluteStock(
                    factionIds[bucket], dimensionIds[bucket], itemKeys[bucket]);
            if (stock < 0) {
                unavailableReads++;
                continue;
            }
            if (published[bucket] != 0 && lastPublishedStock[bucket] == stock) {
                unchangedSnapshots++;
                continue;
            }
            if (engine.publishSupply(
                    factionIds[bucket], dimensionIds[bucket], itemKeys[bucket], stock)) {
                published[bucket] = 1;
                lastPublishedStock[bucket] = stock;
                acceptedSnapshots++;
            } else {
                // The engine ring is bounded. Preserve the dirty bit and retry on the next tick.
                enqueueDirty(bucket);
            }
        }
    }

    private int registerKey(int factionId, int dimensionId, int itemKey) {
        int existing = find(factionId, dimensionId, itemKey);
        if (existing >= 0) {
            return existing;
        }
        if (size == maximumKeys) {
            rejectedKeys++;
            return -1;
        }
        int bucket = mix(factionId, dimensionId, itemKey) & mask;
        while (occupied[bucket] == OCCUPIED) {
            bucket = (bucket + 1) & mask;
        }
        occupied[bucket] = OCCUPIED;
        factionIds[bucket] = factionId;
        dimensionIds[bucket] = dimensionId;
        itemKeys[bucket] = itemKey;
        size++;
        return bucket;
    }

    private int find(int factionId, int dimensionId, int itemKey) {
        if (factionId < 0 || dimensionId < 0 || itemKey < 0) {
            return -1;
        }
        int bucket = mix(factionId, dimensionId, itemKey) & mask;
        for (int probes = 0; probes <= mask; probes++) {
            if (occupied[bucket] == EMPTY) {
                return -1;
            }
            if (factionIds[bucket] == factionId
                    && dimensionIds[bucket] == dimensionId
                    && itemKeys[bucket] == itemKey) {
                return bucket;
            }
            bucket = (bucket + 1) & mask;
        }
        return -1;
    }

    private boolean enqueueDirty(int bucket) {
        if (bucket < 0 || queued[bucket] != 0) {
            return false;
        }
        if (dirtyCount == dirtyQueue.length) {
            throw new IllegalStateException("Unique supply dirty queue exceeded configured key limit");
        }
        dirtyQueue[dirtyTail] = bucket;
        dirtyTail = dirtyTail + 1 == dirtyQueue.length ? 0 : dirtyTail + 1;
        dirtyCount++;
        queued[bucket] = 1;
        return true;
    }

    private int popDirty() {
        int bucket = dirtyQueue[dirtyHead];
        dirtyQueue[dirtyHead] = 0;
        dirtyHead = dirtyHead + 1 == dirtyQueue.length ? 0 : dirtyHead + 1;
        dirtyCount--;
        queued[bucket] = 0;
        return bucket;
    }

    private void beginInventoryReconciliation() {
        if (reconciliationRevision == Long.MAX_VALUE) {
            throw new IllegalStateException("Supply reconciliation revision space exhausted");
        }
        inventories.beginReconciliation(++reconciliationRevision);
    }

    private static int mix(int factionId, int dimensionId, int itemKey) {
        int value = factionId * 0x9e3779b9;
        value ^= Integer.rotateLeft(dimensionId * 0x85ebca6b, 11);
        value ^= Integer.rotateLeft(itemKey * 0xc2b2ae35, 22);
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        return value;
    }

    private static long saturatedAdd(long value, int increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }

    private static boolean isTerminal(byte status) {
        return status == LogisticsRequestStatus.FULFILLED.code()
                || status == LogisticsRequestStatus.CANCELLED.code();
    }
}
