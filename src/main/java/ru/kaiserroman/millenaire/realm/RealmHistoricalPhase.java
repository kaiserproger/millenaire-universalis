package ru.kaiserroman.millenaire.realm;

/** Long-lived political condition of a Realm; transitions are measured in historical years. */
public enum RealmHistoricalPhase {
    ASCENDANT,
    STABLE,
    STRAINED,
    DECADENT,
    COLLAPSING,
    RESTORING;

    public boolean isCrisis() {
        return this == STRAINED || this == DECADENT || this == COLLAPSING;
    }

    public boolean permitsExpansion() {
        return this == ASCENDANT || this == STABLE;
    }
}
