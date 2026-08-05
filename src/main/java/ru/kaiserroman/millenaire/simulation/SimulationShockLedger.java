package ru.kaiserroman.millenaire.simulation;

/** Persistable bounded set of active regional, cultural or settlement shocks. */
public final class SimulationShockLedger {
    private final byte[] types;
    private final long[] targetSettlementIds;
    private final long[] targetRegionKeys;
    private final int[] targetCultureKeys;
    private final int[] magnitudes;
    private final long[] untilCycles;
    private int size;

    public SimulationShockLedger(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Shock capacity must be positive");
        types = new byte[capacity];
        targetSettlementIds = new long[capacity];
        targetRegionKeys = new long[capacity];
        targetCultureKeys = new int[capacity];
        magnitudes = new int[capacity];
        untilCycles = new long[capacity];
    }

    public boolean add(WorldShock shock, long currentCycle) {
        if (shock == null) throw new NullPointerException("shock");
        if (currentCycle < 0L) throw new IllegalArgumentException("Negative currentCycle");
        prune(currentCycle);
        long until = saturatedAdd(currentCycle, (long) shock.remainingCycles() + 1L);
        int existing = findEquivalent(shock);
        if (existing >= 0) {
            magnitudes[existing] = Math.min(
                    1000,
                    magnitudes[existing] + Math.max(25, shock.magnitude() / 2));
            untilCycles[existing] = Math.max(untilCycles[existing], until);
            return true;
        }
        if (size == types.length) return false;
        append(
                shock.type(),
                shock.targetSettlementId(),
                shock.targetRegionKey(),
                shock.targetCultureKey(),
                shock.magnitude(),
                until);
        return true;
    }

    public int prune(long currentCycle) {
        if (currentCycle < 0L) throw new IllegalArgumentException("Negative currentCycle");
        int removed = 0;
        for (int row = size - 1; row >= 0; row--) {
            if (currentCycle < untilCycles[row]) continue;
            removeAt(row);
            removed++;
        }
        return removed;
    }

    public void restore(
            ShockType type,
            long targetSettlementId,
            long targetRegionKey,
            int targetCultureKey,
            int magnitude,
            long untilCycle) {
        validate(type, targetSettlementId, targetCultureKey, magnitude, untilCycle);
        if (size == types.length) throw new IllegalArgumentException("Restored shocks exceed capacity");
        append(type, targetSettlementId, targetRegionKey, targetCultureKey, magnitude, untilCycle);
    }

    public boolean matchesAt(int row, long settlementId, long regionKey, int cultureKey, long cycle) {
        checkRow(row);
        return cycle < untilCycles[row]
                && (targetSettlementIds[row] == 0L || targetSettlementIds[row] == settlementId)
                && (targetRegionKeys[row] == 0L || targetRegionKeys[row] == regionKey)
                && (targetCultureKeys[row] == 0 || targetCultureKeys[row] == cultureKey);
    }

    public ShockType typeAt(int row) {
        checkRow(row);
        return ShockType.values()[Byte.toUnsignedInt(types[row])];
    }

    public int magnitudeAt(int row) { checkRow(row); return magnitudes[row]; }
    public long targetSettlementIdAt(int row) { checkRow(row); return targetSettlementIds[row]; }
    public long targetRegionKeyAt(int row) { checkRow(row); return targetRegionKeys[row]; }
    public int targetCultureKeyAt(int row) { checkRow(row); return targetCultureKeys[row]; }
    public long untilCycleAt(int row) { checkRow(row); return untilCycles[row]; }
    public int size() { return size; }
    public int capacity() { return types.length; }

    public void visit(ShockVisitor visitor) {
        if (visitor == null) throw new NullPointerException("visitor");
        for (int row = 0; row < size; row++) {
            visitor.accept(
                    typeAt(row),
                    targetSettlementIds[row],
                    targetRegionKeys[row],
                    targetCultureKeys[row],
                    magnitudes[row],
                    untilCycles[row]);
        }
    }

    public int estimatedPrimitiveBytes() {
        return types.length
                + targetSettlementIds.length * Long.BYTES
                + targetRegionKeys.length * Long.BYTES
                + targetCultureKeys.length * Integer.BYTES
                + magnitudes.length * Integer.BYTES
                + untilCycles.length * Long.BYTES;
    }

    private int findEquivalent(WorldShock shock) {
        byte type = (byte) shock.type().ordinal();
        for (int row = 0; row < size; row++) {
            if (types[row] == type
                    && targetSettlementIds[row] == shock.targetSettlementId()
                    && targetRegionKeys[row] == shock.targetRegionKey()
                    && targetCultureKeys[row] == shock.targetCultureKey()) {
                return row;
            }
        }
        return -1;
    }

    private void append(
            ShockType type,
            long targetSettlementId,
            long targetRegionKey,
            int targetCultureKey,
            int magnitude,
            long untilCycle) {
        validate(type, targetSettlementId, targetCultureKey, magnitude, untilCycle);
        int row = size++;
        types[row] = (byte) type.ordinal();
        targetSettlementIds[row] = targetSettlementId;
        targetRegionKeys[row] = targetRegionKey;
        targetCultureKeys[row] = targetCultureKey;
        magnitudes[row] = magnitude;
        untilCycles[row] = untilCycle;
    }

    private void removeAt(int row) {
        int last = --size;
        if (row != last) {
            types[row] = types[last];
            targetSettlementIds[row] = targetSettlementIds[last];
            targetRegionKeys[row] = targetRegionKeys[last];
            targetCultureKeys[row] = targetCultureKeys[last];
            magnitudes[row] = magnitudes[last];
            untilCycles[row] = untilCycles[last];
        }
        types[last] = 0;
        targetSettlementIds[last] = 0L;
        targetRegionKeys[last] = 0L;
        targetCultureKeys[last] = 0;
        magnitudes[last] = 0;
        untilCycles[last] = 0L;
    }

    private void checkRow(int row) {
        if (row < 0 || row >= size) throw new IndexOutOfBoundsException("shock row=" + row);
    }

    private static void validate(
            ShockType type,
            long targetSettlementId,
            int targetCultureKey,
            int magnitude,
            long untilCycle) {
        if (type == null || targetSettlementId < 0L || targetCultureKey < 0
                || magnitude <= 0 || magnitude > 1000 || untilCycle <= 0L) {
            throw new IllegalArgumentException("Invalid simulation shock row");
        }
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    @FunctionalInterface
    public interface ShockVisitor {
        void accept(
                ShockType type,
                long targetSettlementId,
                long targetRegionKey,
                int targetCultureKey,
                int magnitude,
                long untilCycle);
    }
}
