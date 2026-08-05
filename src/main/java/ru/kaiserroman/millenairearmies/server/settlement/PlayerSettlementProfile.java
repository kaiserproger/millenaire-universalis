package ru.kaiserroman.millenairearmies.server.settlement;

import java.util.Locale;
import ru.kaiserroman.millenaire.realm.RealmStatePriority;

/** Player-selected development programme for an owned Millenaire settlement. */
public enum PlayerSettlementProfile {
    BALANCED,
    FOOD,
    TRADE,
    INDUSTRY,
    MILITARY,
    CIVIC;

    public static PlayerSettlementProfile parse(String value) {
        if (value == null) throw new NullPointerException("profile");
        return valueOf(value.strip().toUpperCase(Locale.ROOT));
    }

    public RealmStatePriority primaryPriority() {
        return switch (this) {
            case BALANCED, CIVIC -> RealmStatePriority.CIVIC_GROWTH;
            case FOOD -> RealmStatePriority.FOOD_SECURITY;
            case TRADE -> RealmStatePriority.TRADE;
            case INDUSTRY -> RealmStatePriority.INDUSTRY;
            case MILITARY -> RealmStatePriority.FORTIFICATION;
        };
    }

    public RealmStatePriority secondaryPriority() {
        return switch (this) {
            case BALANCED -> RealmStatePriority.FOOD_SECURITY;
            case FOOD -> RealmStatePriority.CIVIC_GROWTH;
            case TRADE -> RealmStatePriority.INDUSTRY;
            case INDUSTRY -> RealmStatePriority.TRADE;
            case MILITARY -> RealmStatePriority.MILITARY_MOBILIZATION;
            case CIVIC -> RealmStatePriority.CONSOLIDATION;
        };
    }
}
