package ru.kaiserroman.millenaire.simulation;

/**
 * Immutable capture supplied by the Millenaire adapter. All indices are 0..1000. Region and
 * culture keys are adapter-owned stable ids; Realm id may be zero for an independent settlement.
 */
public record SettlementObservation(
        long settlementId,
        int cultureKey,
        long realmId,
        long regionKey,
        long population,
        long housingCapacity,
        int buildingCount,
        int productiveBuildings,
        int marketAccess,
        int security,
        int damage,
        int education,
        int geographicCapacity,
        int fertility,
        int specialization) {

    public SettlementObservation {
        if (settlementId <= 0L || cultureKey < 0 || realmId < 0L
                || population < 0L || housingCapacity < 0L
                || buildingCount < 0 || productiveBuildings < 0
                || productiveBuildings > buildingCount) {
            throw new IllegalArgumentException("Invalid settlement observation");
        }
        requireIndex(marketAccess, "marketAccess");
        requireIndex(security, "security");
        requireIndex(damage, "damage");
        requireIndex(education, "education");
        requireIndex(geographicCapacity, "geographicCapacity");
        requireIndex(fertility, "fertility");
        requireIndex(specialization, "specialization");
    }

    private static void requireIndex(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " outside 0..1000");
        }
    }
}
