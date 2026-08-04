package ru.kaiserroman.millenairearmies.integration.millenaire;

/**
 * Pure deterministic policy for player block breaking inside a foreign Millenaire settlement.
 *
 * <p>Spatial lookup, Realm ownership and active physical-siege detection stay in the server adapter.
 * This policy deliberately distinguishes a very slow peacetime dismantling path from a bounded
 * siege breach. Critical infrastructure is never exposed through the generic break event.</p>
 */
public final class SettlementBlockProtectionPolicy {
    public enum Decision {
        OUTSIDE,
        AUTHORIZED,
        FOREIGN_SLOWED,
        SIEGE_BREACH_SLOWED,
        DENY_CRITICAL,
        DENY_INTERIOR_DURING_SIEGE
    }

    private final int foreignSpeedPermille;
    private final int siegeBreachSpeedPermille;

    public SettlementBlockProtectionPolicy(
            int foreignSpeedPermille,
            int siegeBreachSpeedPermille) {
        if (foreignSpeedPermille < 1 || foreignSpeedPermille > 1_000
                || siegeBreachSpeedPermille < foreignSpeedPermille
                || siegeBreachSpeedPermille > 1_000) {
            throw new IllegalArgumentException("Invalid settlement break-speed policy");
        }
        this.foreignSpeedPermille = foreignSpeedPermille;
        this.siegeBreachSpeedPermille = siegeBreachSpeedPermille;
    }

    public Decision decide(
            boolean insideSettlement,
            boolean authorized,
            boolean criticalInfrastructure,
            boolean activePhysicalSiege,
            boolean insideBreachBand) {
        if (!insideSettlement) return Decision.OUTSIDE;
        if (authorized) return Decision.AUTHORIZED;
        if (criticalInfrastructure) return Decision.DENY_CRITICAL;
        if (!activePhysicalSiege) return Decision.FOREIGN_SLOWED;
        return insideBreachBand
                ? Decision.SIEGE_BREACH_SLOWED
                : Decision.DENY_INTERIOR_DURING_SIEGE;
    }

    public float adjustedSpeed(float originalSpeed, Decision decision) {
        if (!Float.isFinite(originalSpeed) || originalSpeed < 0.0F || decision == null) {
            throw new IllegalArgumentException("Break speed and decision must be valid");
        }
        return switch (decision) {
            case FOREIGN_SLOWED -> scaled(originalSpeed, foreignSpeedPermille);
            case SIEGE_BREACH_SLOWED -> scaled(originalSpeed, siegeBreachSpeedPermille);
            case DENY_CRITICAL, DENY_INTERIOR_DURING_SIEGE -> 0.0F;
            case OUTSIDE, AUTHORIZED -> originalSpeed;
        };
    }

    public boolean cancelFinalBreak(Decision decision) {
        return decision == Decision.DENY_CRITICAL
                || decision == Decision.DENY_INTERIOR_DURING_SIEGE;
    }

    public int foreignSpeedPermille() {
        return foreignSpeedPermille;
    }

    public int siegeBreachSpeedPermille() {
        return siegeBreachSpeedPermille;
    }

    private static float scaled(float originalSpeed, int permille) {
        if (originalSpeed == 0.0F) return 0.0F;
        return Math.max(0.0001F, originalSpeed * permille / 1_000.0F);
    }
}
