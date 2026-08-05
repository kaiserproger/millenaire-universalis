package ru.kaiserroman.millenaire.realm;

/** Persisted strategic programme chosen by a Realm for the next historical planning period. */
public enum RealmStatePriority {
    NONE,
    FOOD_SECURITY,
    TRADE,
    INDUSTRY,
    FORTIFICATION,
    MILITARY_MOBILIZATION,
    CIVIC_GROWTH,
    CONSOLIDATION,
    EXPANSION,
    RECOVERY,
    AUSTERITY;

    public boolean permitsConstruction() {
        return this != NONE && this != AUSTERITY && this != MILITARY_MOBILIZATION;
    }

    public boolean isExpansionary() {
        return this == EXPANSION || this == MILITARY_MOBILIZATION;
    }
}
