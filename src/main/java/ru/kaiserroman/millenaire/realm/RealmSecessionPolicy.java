package ru.kaiserroman.millenaire.realm;

/** Historical provincial secession without assuming every collapse destroys the whole Realm. */
public final class RealmSecessionPolicy {
    public static final int SECESSION_THRESHOLD = 700;
    public static final int BREAKAWAY_STATE_THRESHOLD = 560;

    public static final int REASON_DECADENCE = 1;
    public static final int REASON_COLLAPSE = 1 << 1;
    public static final int REASON_OVEREXTENSION = 1 << 2;
    public static final int REASON_CULTURE = 1 << 3;
    public static final int REASON_DISTANCE = 1 << 4;
    public static final int REASON_DAMAGE = 1 << 5;
    public static final int REASON_LOCAL_WEAKNESS = 1 << 6;
    public static final int REASON_LOCAL_CAPACITY = 1 << 7;

    public RealmSecessionDecision evaluate(RealmSecessionInputs inputs) {
        if (inputs == null) throw new NullPointerException("inputs");
        if (inputs.parentSettlementCount() < 2
                || (inputs.parentPhase() != RealmHistoricalPhase.DECADENT
                        && inputs.parentPhase() != RealmHistoricalPhase.COLLAPSING)) {
            return new RealmSecessionDecision(0, breakawayCapacity(inputs), 0, false, false);
        }

        int reasons = 0;
        int pressure = inputs.parentPhase() == RealmHistoricalPhase.COLLAPSING ? 360 : 190;
        reasons |= inputs.parentPhase() == RealmHistoricalPhase.COLLAPSING
                ? REASON_COLLAPSE
                : REASON_DECADENCE;
        pressure += (1000 - inputs.parentViability()) * 18 / 100;
        pressure += (1000 - inputs.parentAdministrativeReserve()) * 20 / 100;
        pressure += (1000 - inputs.parentCulturalCohesion()) * 12 / 100;
        if (inputs.parentAdministrativeReserve() < 500) reasons |= REASON_OVEREXTENSION;
        if (!inputs.sameAsCapitalCulture()) {
            pressure += 180;
            reasons |= REASON_CULTURE;
        }
        if (inputs.remoteFromCapital()) {
            pressure += 110;
            reasons |= REASON_DISTANCE;
        }
        pressure += inputs.localDamage() * 15 / 100;
        if (inputs.localDamage() >= 500) reasons |= REASON_DAMAGE;
        pressure += (1000 - inputs.localStability()) * 8 / 100;
        pressure += (1000 - inputs.localSecurity()) * 7 / 100;
        if (inputs.localStability() < 400 || inputs.localSecurity() < 400) {
            reasons |= REASON_LOCAL_WEAKNESS;
        }

        int capacity = breakawayCapacity(inputs);
        pressure += capacity * 10 / 100;
        if (capacity >= BREAKAWAY_STATE_THRESHOLD) reasons |= REASON_LOCAL_CAPACITY;
        pressure = clamp(pressure);
        boolean secedes = pressure >= SECESSION_THRESHOLD;
        boolean formsState = secedes
                && capacity >= BREAKAWAY_STATE_THRESHOLD
                && inputs.localPopulationReadiness() >= 400
                && inputs.localProductiveCapital() >= 300;
        return new RealmSecessionDecision(pressure, capacity, reasons, secedes, formsState);
    }

    private static int breakawayCapacity(RealmSecessionInputs inputs) {
        return clamp((int) ((inputs.localPopulationReadiness() * 24L
                + inputs.localStability() * 20L
                + inputs.localSecurity() * 14L
                + inputs.localMarketAccess() * 15L
                + inputs.localProductiveCapital() * 17L
                + (1000 - inputs.localDamage()) * 10L) / 100L));
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(1000, value));
    }
}
