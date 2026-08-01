package ru.kaiserroman.millenairearmies.server.logistics;

import java.util.Arrays;

/**
 * Fixed-capacity primitive table for aggregate village supplies.
 *
 * <p>A key is {@code (faction, stable dimension, stable item)}. Stock is the last absolute
 * snapshot published by the Millenaire adapter; reservations are owned by the logistics engine.
 * The table never rehashes, so normal ticks cannot replace an array or allocate an entry object.</p>
 */
final class PackedSupplyLedger {
    static final int NO_BUCKET = -1;

    private final int mask;
    private final int maximumEntries;
    private final byte[] occupied;
    private final int[] factionIds;
    private final int[] dimensionIds;
    private final int[] itemKeys;
    private final int[] stock;
    private final int[] reserved;
    private int size;

    PackedSupplyLedger(int maximumEntries) {
        if (maximumEntries <= 0) {
            throw new IllegalArgumentException("Supply entry limit must be positive");
        }
        int required = (int) Math.min(1L << 30, ((long) maximumEntries * 10L + 6L) / 7L);
        int capacity = 1;
        while (capacity < required) {
            capacity <<= 1;
        }
        this.mask = capacity - 1;
        this.maximumEntries = maximumEntries;
        this.occupied = new byte[capacity];
        this.factionIds = new int[capacity];
        this.dimensionIds = new int[capacity];
        this.itemKeys = new int[capacity];
        this.stock = new int[capacity];
        this.reserved = new int[capacity];
    }

    int capacity() {
        return occupied.length;
    }

    int size() {
        return size;
    }

    void clear() {
        Arrays.fill(occupied, (byte) 0);
        Arrays.fill(stock, 0);
        Arrays.fill(reserved, 0);
        size = 0;
    }

    int publish(int factionId, int dimensionId, int itemKey, int absoluteStock) {
        if (factionId < 0 || dimensionId < 0 || itemKey < 0 || absoluteStock < 0) {
            return NO_BUCKET;
        }
        int bucket = find(factionId, dimensionId, itemKey);
        if (bucket == NO_BUCKET) {
            if (size == maximumEntries) {
                return NO_BUCKET;
            }
            bucket = insertionBucket(factionId, dimensionId, itemKey);
            if (bucket == NO_BUCKET) {
                return NO_BUCKET;
            }
            occupied[bucket] = 1;
            factionIds[bucket] = factionId;
            dimensionIds[bucket] = dimensionId;
            itemKeys[bucket] = itemKey;
            size++;
        }
        stock[bucket] = absoluteStock;
        return bucket;
    }

    int find(int factionId, int dimensionId, int itemKey) {
        int bucket = mix(factionId, dimensionId, itemKey) & mask;
        for (int probes = 0; probes <= mask; probes++) {
            if (occupied[bucket] == 0) {
                return NO_BUCKET;
            }
            if (factionIds[bucket] == factionId
                    && dimensionIds[bucket] == dimensionId
                    && itemKeys[bucket] == itemKey) {
                return bucket;
            }
            bucket = (bucket + 1) & mask;
        }
        return NO_BUCKET;
    }

    int available(int bucket) {
        int value = stock[bucket] - reserved[bucket];
        return Math.max(0, value);
    }

    int stock(int bucket) {
        return stock[bucket];
    }

    int reserved(int bucket) {
        return reserved[bucket];
    }

    int reserve(int bucket, int amount) {
        int accepted = Math.min(Math.max(0, amount), available(bucket));
        reserved[bucket] += accepted;
        return accepted;
    }

    void release(int bucket, int amount) {
        if (amount < 0 || amount > reserved[bucket]) {
            throw new IllegalArgumentException("Reservation release exceeds bucket reservation");
        }
        reserved[bucket] -= amount;
    }

    boolean dispatch(int bucket, int amount) {
        if (amount < 0 || amount > reserved[bucket] || amount > stock[bucket]) {
            return false;
        }
        reserved[bucket] -= amount;
        stock[bucket] -= amount;
        return true;
    }

    long deterministicHash() {
        long hash = 0xcbf29ce484222325L;
        for (int bucket = 0; bucket < occupied.length; bucket++) {
            if (occupied[bucket] == 0) {
                continue;
            }
            hash = mixHash(hash, factionIds[bucket]);
            hash = mixHash(hash, dimensionIds[bucket]);
            hash = mixHash(hash, itemKeys[bucket]);
            hash = mixHash(hash, stock[bucket]);
            hash = mixHash(hash, reserved[bucket]);
        }
        return hash;
    }

    private int insertionBucket(int factionId, int dimensionId, int itemKey) {
        int bucket = mix(factionId, dimensionId, itemKey) & mask;
        for (int probes = 0; probes <= mask; probes++) {
            if (occupied[bucket] == 0) {
                return bucket;
            }
            bucket = (bucket + 1) & mask;
        }
        return NO_BUCKET;
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

    private static long mixHash(long hash, int value) {
        hash ^= Integer.toUnsignedLong(value);
        return hash * 0x100000001b3L;
    }
}
