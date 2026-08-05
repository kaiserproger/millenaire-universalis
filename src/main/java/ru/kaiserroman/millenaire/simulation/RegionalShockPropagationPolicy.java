package ru.kaiserroman.millenaire.simulation;

/**
 * Pure high-level policy for spreading regional crises through trade, proximity and cultural ties.
 * It produces another bounded {@link WorldShock}; physical routing remains adapter-owned.
 */
public final class RegionalShockPropagationPolicy {
    public static final int REASON_CONTACT = 1;
    public static final int REASON_SHARED_REGION = 1 << 1;
    public static final int REASON_SHARED_CULTURE = 1 << 2;
    public static final int REASON_MARKET_ACCESS = 1 << 3;
    public static final int REASON_DISTANCE = 1 << 4;
    public static final int REASON_BORDER_CONTROL = 1 << 5;
    public static final int REASON_SECURITY = 1 << 6;
    public static final int REASON_TIME_DECAY = 1 << 7;

    private static final int PROPAGATION_THRESHOLD = 500;

    public ShockPropagationDecision evaluate(
            WorldShock source,
            int targetMarketAccess,
            int targetSecurity,
            boolean sharedRegion,
            boolean sharedCulture,
            ShockPropagationInputs inputs) {
        if (source == null || inputs == null) throw new NullPointerException("shock propagation input");
        requireIndex(targetMarketAccess, "targetMarketAccess");
        requireIndex(targetSecurity, "targetSecurity");
        if (inputs.elapsedCycles() >= source.remainingCycles()) {
            return new ShockPropagationDecision(0, 0, 0, REASON_TIME_DECAY);
        }

        Weights weights = weights(source.type());
        int pressure = source.magnitude() * 35 / 100
                + weights.base
                + inputs.contactIntensity() * weights.contact / 100
                + targetMarketAccess * weights.market / 100
                + (sharedRegion ? weights.sharedRegion : 0)
                + (sharedCulture ? weights.sharedCulture : 0)
                - inputs.distancePenalty() * weights.distance / 100
                - inputs.borderControl() * weights.border / 100
                - targetSecurity * weights.security / 100
                - inputs.elapsedCycles() * weights.decayPerCycle;
        pressure = clamp(pressure);

        int reasons = 0;
        if (inputs.contactIntensity() >= 300) reasons |= REASON_CONTACT;
        if (sharedRegion) reasons |= REASON_SHARED_REGION;
        if (sharedCulture) reasons |= REASON_SHARED_CULTURE;
        if (targetMarketAccess >= 600) reasons |= REASON_MARKET_ACCESS;
        if (inputs.distancePenalty() >= 500) reasons |= REASON_DISTANCE;
        if (inputs.borderControl() >= 500) reasons |= REASON_BORDER_CONTROL;
        if (targetSecurity >= 600) reasons |= REASON_SECURITY;
        if (inputs.elapsedCycles() > 0) reasons |= REASON_TIME_DECAY;

        if (pressure < PROPAGATION_THRESHOLD) {
            return new ShockPropagationDecision(pressure, 0, 0, reasons);
        }
        int retention = Math.min(1000, (pressure - 350) * 1000 / 650);
        int magnitude = Math.max(1, source.magnitude() * retention / 1000);
        if (magnitude < 25) {
            return new ShockPropagationDecision(pressure, 0, 0, reasons);
        }
        int remaining = source.remainingCycles() - inputs.elapsedCycles();
        int duration = Math.min(remaining, Math.max(1, 1 + magnitude / 300));
        return new ShockPropagationDecision(pressure, magnitude, duration, reasons);
    }

    private static Weights weights(ShockType type) {
        return switch (type) {
            case EPIDEMIC -> new Weights(150, 45, 15, 160, 50, 35, 30, 20, 45);
            case HARVEST_FAILURE -> new Weights(120, 10, 0, 360, 50, 25, 10, 0, 40);
            case WAR_DEVASTATION -> new Weights(100, 25, 0, 260, 0, 30, 20, 15, 50);
            case TRADE_BOOM -> new Weights(100, 45, 30, 40, 50, 25, 25, 0, 30);
            case TECHNOLOGY_DIFFUSION -> new Weights(80, 35, 25, 40, 120, 20, 10, 0, 25);
            case MIGRATION_WAVE -> new Weights(100, 30, 10, 120, 40, 25, 20, 5, 35);
        };
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(1000, value));
    }

    private static void requireIndex(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " outside 0..1000");
        }
    }

    private record Weights(
            int base,
            int contact,
            int market,
            int sharedRegion,
            int sharedCulture,
            int distance,
            int border,
            int security,
            int decayPerCycle) {
    }
}
