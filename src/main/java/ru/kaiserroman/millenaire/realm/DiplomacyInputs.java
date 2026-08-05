package ru.kaiserroman.millenaire.realm;

/** All indices are 0..1000; power advantage 500 means approximate parity. */
public record DiplomacyInputs(
        int trust,
        int grievances,
        int fear,
        int tradeInterdependence,
        int borderFriction,
        int claimStrength,
        int powerAdvantage,
        int ideologicalDistance,
        int warExhaustion,
        int commonThreat,
        int truceCycles) {

    public DiplomacyInputs {
        requireIndex(trust, "trust");
        requireIndex(grievances, "grievances");
        requireIndex(fear, "fear");
        requireIndex(tradeInterdependence, "tradeInterdependence");
        requireIndex(borderFriction, "borderFriction");
        requireIndex(claimStrength, "claimStrength");
        requireIndex(powerAdvantage, "powerAdvantage");
        requireIndex(ideologicalDistance, "ideologicalDistance");
        requireIndex(warExhaustion, "warExhaustion");
        requireIndex(commonThreat, "commonThreat");
        if (truceCycles < 0) throw new IllegalArgumentException("Negative truceCycles");
    }

    private static void requireIndex(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " outside 0..1000");
        }
    }
}
