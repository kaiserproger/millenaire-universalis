package ru.kaiserroman.millenairearmies.server.settlement;

/** Development tier of a player-controlled physical Millenaire settlement. */
public enum PlayerSettlementTier {
    HAMLET,
    VILLAGE,
    TOWN,
    CITY_STATE;

    public boolean atLeast(PlayerSettlementTier required) {
        return ordinal() >= required.ordinal();
    }
}
