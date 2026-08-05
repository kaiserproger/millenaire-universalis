package ru.kaiserroman.millenaire.realm;

/** Central and provincial drivers for one potential historical secession. */
public record RealmSecessionInputs(
        RealmHistoricalPhase parentPhase,
        int parentSettlementCount,
        int parentViability,
        int parentAdministrativeReserve,
        int parentCulturalCohesion,
        int localPopulationReadiness,
        int localStability,
        int localSecurity,
        int localDamage,
        int localMarketAccess,
        int localProductiveCapital,
        boolean sameAsCapitalCulture,
        boolean remoteFromCapital) {
    public RealmSecessionInputs {
        if (parentPhase == null) throw new NullPointerException("parentPhase");
        if (parentSettlementCount < 0) throw new IllegalArgumentException("Negative parent size");
        requireIndex(parentViability, "parentViability");
        requireIndex(parentAdministrativeReserve, "parentAdministrativeReserve");
        requireIndex(parentCulturalCohesion, "parentCulturalCohesion");
        requireIndex(localPopulationReadiness, "localPopulationReadiness");
        requireIndex(localStability, "localStability");
        requireIndex(localSecurity, "localSecurity");
        requireIndex(localDamage, "localDamage");
        requireIndex(localMarketAccess, "localMarketAccess");
        requireIndex(localProductiveCapital, "localProductiveCapital");
    }

    private static void requireIndex(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " outside 0..1000");
        }
    }
}
