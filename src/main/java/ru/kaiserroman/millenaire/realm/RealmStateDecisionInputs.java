package ru.kaiserroman.millenaire.realm;

/** Normalized annual/periodic state-planning inputs. Indices use 0..1000. */
public record RealmStateDecisionInputs(
        RealmHistoricalPhase phase,
        RealmScale scale,
        int viability,
        int expansionReadiness,
        int foodCoverage,
        int security,
        int marketAccess,
        int productivity,
        int damage,
        int administrativeReserve,
        int treasuryCoverage,
        boolean atWar,
        int settlementCount,
        long population) {

    public RealmStateDecisionInputs {
        if (phase == null || scale == null || settlementCount <= 0 || population < 0L) {
            throw new IllegalArgumentException("Invalid Realm state decision inputs");
        }
        validateIndex(viability);
        validateIndex(expansionReadiness);
        validateIndex(foodCoverage);
        validateIndex(security);
        validateIndex(marketAccess);
        validateIndex(productivity);
        validateIndex(damage);
        validateIndex(administrativeReserve);
        validateIndex(treasuryCoverage);
    }

    private static void validateIndex(int value) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException("Realm planning index outside 0..1000");
        }
    }
}
