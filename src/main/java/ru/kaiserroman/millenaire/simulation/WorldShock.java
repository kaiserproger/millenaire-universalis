package ru.kaiserroman.millenaire.simulation;

/**
 * A bounded shock. Target settlement, region and culture keys may be zero to mean wildcard; at
 * least one target selector should normally be non-zero for authored shocks.
 */
public record WorldShock(
        ShockType type,
        long targetSettlementId,
        long targetRegionKey,
        int targetCultureKey,
        int magnitude,
        int remainingCycles) {

    public WorldShock {
        if (type == null) throw new NullPointerException("type");
        if (targetSettlementId < 0L || targetCultureKey < 0
                || magnitude <= 0 || magnitude > 1000 || remainingCycles <= 0) {
            throw new IllegalArgumentException("Invalid world shock");
        }
    }

    public boolean matches(long settlementId, long regionKey, int cultureKey) {
        return (targetSettlementId == 0L || targetSettlementId == settlementId)
                && (targetRegionKey == 0L || targetRegionKey == regionKey)
                && (targetCultureKey == 0 || targetCultureKey == cultureKey);
    }

    public WorldShock nextCycle() {
        return remainingCycles <= 1
                ? null
                : new WorldShock(
                        type,
                        targetSettlementId,
                        targetRegionKey,
                        targetCultureKey,
                        magnitude,
                        remainingCycles - 1);
    }
}
