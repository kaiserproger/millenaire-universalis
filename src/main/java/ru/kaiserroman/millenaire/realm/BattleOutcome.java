package ru.kaiserroman.millenaire.realm;

/** Aggregated result reported by Armies after physical battles or sieges. */
public record BattleOutcome(
        long attackerRealmId,
        long defenderRealmId,
        boolean attackerVictory,
        int attackerLosses,
        int defenderLosses,
        long settlementId,
        boolean capitalCaptured,
        boolean settlementOccupied) {

    public BattleOutcome {
        if (attackerRealmId <= 0L || defenderRealmId <= 0L || attackerRealmId == defenderRealmId) {
            throw new IllegalArgumentException("Invalid battle realms");
        }
        if (attackerLosses < 0 || defenderLosses < 0 || settlementId < 0L) {
            throw new IllegalArgumentException("Invalid battle losses or settlement id");
        }
    }

    /** Compatibility constructor for abstract battles without a physical settlement objective. */
    public BattleOutcome(
            long attackerRealmId,
            long defenderRealmId,
            boolean attackerVictory,
            int attackerLosses,
            int defenderLosses,
            boolean capitalCaptured,
            boolean settlementOccupied) {
        this(
                attackerRealmId,
                defenderRealmId,
                attackerVictory,
                attackerLosses,
                defenderLosses,
                0L,
                capitalCaptured,
                settlementOccupied);
    }
}
