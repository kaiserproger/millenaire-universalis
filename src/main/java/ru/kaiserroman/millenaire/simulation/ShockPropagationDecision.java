package ru.kaiserroman.millenaire.simulation;

/** Result of evaluating whether an existing world shock reaches another settlement. */
public record ShockPropagationDecision(
        int pressure,
        int magnitude,
        int durationCycles,
        int reasonMask) {

    public ShockPropagationDecision {
        requireIndex(pressure, "pressure");
        requireIndex(magnitude, "magnitude");
        if (durationCycles < 0 || magnitude == 0 && durationCycles != 0
                || magnitude > 0 && durationCycles == 0) {
            throw new IllegalArgumentException("Invalid propagated shock duration");
        }
        if (reasonMask < 0) throw new IllegalArgumentException("Negative reasonMask");
    }

    public boolean propagates() { return magnitude > 0; }

    public WorldShock toShock(
            ShockType type,
            long targetSettlementId,
            long targetRegionKey,
            int targetCultureKey) {
        if (!propagates()) return null;
        return new WorldShock(
                type,
                targetSettlementId,
                targetRegionKey,
                targetCultureKey,
                magnitude,
                durationCycles);
    }

    private static void requireIndex(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " outside 0..1000");
        }
    }
}
