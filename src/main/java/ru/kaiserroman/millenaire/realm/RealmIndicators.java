package ru.kaiserroman.millenaire.realm;

/** Coarse social and economic inputs used by constitutional evolution. */
public record RealmIndicators(
        int settlementCount,
        long population,
        int urbanization,
        int marketIntegration,
        int tradeDependence,
        int landInequality,
        int bureaucracyCapacity,
        int externalThreat,
        int warExhaustion,
        int eliteCompetition,
        int civicTradition,
        int rulerAuthority,
        int culturalCohesion,
        int legitimacy,
        int prosperity) {

    public RealmIndicators {
        if (settlementCount < 0 || population < 0L) {
            throw new IllegalArgumentException("Negative realm size");
        }
        requireIndex(urbanization, "urbanization");
        requireIndex(marketIntegration, "marketIntegration");
        requireIndex(tradeDependence, "tradeDependence");
        requireIndex(landInequality, "landInequality");
        requireIndex(bureaucracyCapacity, "bureaucracyCapacity");
        requireIndex(externalThreat, "externalThreat");
        requireIndex(warExhaustion, "warExhaustion");
        requireIndex(eliteCompetition, "eliteCompetition");
        requireIndex(civicTradition, "civicTradition");
        requireIndex(rulerAuthority, "rulerAuthority");
        requireIndex(culturalCohesion, "culturalCohesion");
        requireIndex(legitimacy, "legitimacy");
        requireIndex(prosperity, "prosperity");
    }

    private static void requireIndex(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " outside 0..1000");
        }
    }
}
