package ru.kaiserroman.millenaire.realm;

/** One deterministic historical update. Momentum uses 1,000,000 points per phase transition. */
public record RealmHistoricalAssessment(
        RealmHistoricalPhase phase,
        RealmScale scale,
        int stateCapacity,
        int crisisBurden,
        int viability,
        int expansionReadiness,
        int crisisMomentum,
        int recoveryMomentum,
        int crisisRatePerYear,
        int recoveryRatePerYear,
        int reasonMask,
        long phaseSinceMilliYear,
        boolean phaseChanged,
        boolean scaleChanged,
        boolean mayExpand,
        boolean terminalCollapse) {
    public RealmHistoricalAssessment {
        if (phase == null || scale == null) throw new NullPointerException("historical state");
        requireIndex(stateCapacity, "stateCapacity");
        requireIndex(crisisBurden, "crisisBurden");
        requireIndex(viability, "viability");
        requireIndex(expansionReadiness, "expansionReadiness");
        requireMomentum(crisisMomentum, "crisisMomentum");
        requireMomentum(recoveryMomentum, "recoveryMomentum");
        if (crisisRatePerYear < 0 || recoveryRatePerYear < 0 || phaseSinceMilliYear < 0L) {
            throw new IllegalArgumentException("Negative historical rate/time");
        }
    }

    private static void requireIndex(int value, String name) {
        if (value < 0 || value > 1000) throw new IllegalArgumentException(name + " outside 0..1000");
    }

    private static void requireMomentum(int value, String name) {
        if (value < 0 || value > RealmHistoricalPolicy.MOMENTUM_THRESHOLD) {
            throw new IllegalArgumentException(name + " outside historical bounds");
        }
    }
}
