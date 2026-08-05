package ru.kaiserroman.millenaire.realm;

/** Normalized 0..1000 drivers used by the historical state machine. */
public record RealmHistoricalInputs(
        int settlementCount,
        long population,
        int populationReadiness,
        int foodCoverage,
        int fiscalCapacity,
        int militaryPower,
        int stability,
        int productiveCapital,
        int marketAccess,
        int legitimacy,
        int administrativeReserve,
        int culturalCohesion,
        int warExhaustion,
        int crisisSeverity,
        int strategicAmbition,
        boolean capitalExists) {
    public RealmHistoricalInputs {
        if (settlementCount < 0 || population < 0L) {
            throw new IllegalArgumentException("Negative historical Realm size");
        }
        requireIndex(populationReadiness, "populationReadiness");
        requireIndex(foodCoverage, "foodCoverage");
        requireIndex(fiscalCapacity, "fiscalCapacity");
        requireIndex(militaryPower, "militaryPower");
        requireIndex(stability, "stability");
        requireIndex(productiveCapital, "productiveCapital");
        requireIndex(marketAccess, "marketAccess");
        requireIndex(legitimacy, "legitimacy");
        requireIndex(administrativeReserve, "administrativeReserve");
        requireIndex(culturalCohesion, "culturalCohesion");
        requireIndex(warExhaustion, "warExhaustion");
        requireIndex(crisisSeverity, "crisisSeverity");
        requireIndex(strategicAmbition, "strategicAmbition");
    }

    private static void requireIndex(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " outside 0..1000");
        }
    }
}
