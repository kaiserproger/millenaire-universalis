package ru.kaiserroman.millenaire.realm;

/** Territorial scale, deliberately independent from government form. */
public enum RealmScale {
    CITY_STATE,
    REGIONAL_STATE,
    KINGDOM,
    EMPIRE;

    public RealmScale lower() {
        return ordinal() == 0 ? this : values()[ordinal() - 1];
    }
}
