package ru.kaiserroman.millenaire.realm;

/** Political consequences Realm applies after an Armies battle report. */
public record WarImpact(
        int attackerWarScoreDelta,
        int defenderWarScoreDelta,
        int attackerExhaustionDelta,
        int defenderExhaustionDelta,
        int grievanceDelta) {
}
