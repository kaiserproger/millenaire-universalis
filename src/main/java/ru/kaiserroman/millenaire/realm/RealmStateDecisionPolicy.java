package ru.kaiserroman.millenaire.realm;

/** Deterministic historical state planner: crisis first, then material bottlenecks, then ambition. */
public final class RealmStateDecisionPolicy {
    public static final int REASON_COLLAPSE = 1;
    public static final int REASON_FOOD = 1 << 1;
    public static final int REASON_WAR = 1 << 2;
    public static final int REASON_DAMAGE = 1 << 3;
    public static final int REASON_ADMINISTRATION = 1 << 4;
    public static final int REASON_PRODUCTIVITY = 1 << 5;
    public static final int REASON_MARKET = 1 << 6;
    public static final int REASON_SECURITY = 1 << 7;
    public static final int REASON_TREASURY = 1 << 8;
    public static final int REASON_EXPANSION = 1 << 9;
    public static final int REASON_CIVIC = 1 << 10;

    public RealmStateDecision evaluate(RealmStateDecisionInputs in) {
        if (in == null) throw new NullPointerException("inputs");

        if (in.phase() == RealmHistoricalPhase.COLLAPSING || in.viability() < 250) {
            int pressure = clamp(650 + (250 - Math.min(250, in.viability()))
                    + in.damage() / 5);
            return decision(
                    RealmStatePriority.AUSTERITY,
                    0,
                    false,
                    false,
                    true,
                    pressure,
                    REASON_COLLAPSE | REASON_TREASURY);
        }

        if (in.atWar() && in.security() < 700) {
            int pressure = clamp(700 + (700 - in.security()) / 2 + in.damage() / 5);
            return decision(
                    RealmStatePriority.FORTIFICATION,
                    700,
                    true,
                    false,
                    in.viability() < 500,
                    pressure,
                    REASON_WAR | REASON_SECURITY);
        }

        if (in.foodCoverage() < 500) {
            int pressure = clamp(650 + (500 - in.foodCoverage()) / 2);
            return decision(
                    RealmStatePriority.FOOD_SECURITY,
                    750,
                    true,
                    false,
                    in.atWar() && in.viability() < 550,
                    pressure,
                    REASON_FOOD);
        }

        if (in.damage() > 350 || in.phase() == RealmHistoricalPhase.RESTORING) {
            int pressure = clamp(600 + in.damage() / 3
                    + (in.phase() == RealmHistoricalPhase.RESTORING ? 100 : 0));
            return decision(
                    RealmStatePriority.RECOVERY,
                    700,
                    true,
                    false,
                    in.atWar(),
                    pressure,
                    REASON_DAMAGE);
        }

        if (in.administrativeReserve() < 400 || in.phase() == RealmHistoricalPhase.STRAINED) {
            int pressure = clamp(550 + (400 - Math.min(400, in.administrativeReserve())) / 2);
            return decision(
                    RealmStatePriority.CONSOLIDATION,
                    450,
                    true,
                    false,
                    in.atWar() && in.viability() < 600,
                    pressure,
                    REASON_ADMINISTRATION);
        }

        if (in.treasuryCoverage() < 250) {
            return decision(
                    RealmStatePriority.AUSTERITY,
                    0,
                    false,
                    false,
                    in.atWar(),
                    clamp(600 + (250 - in.treasuryCoverage())),
                    REASON_TREASURY);
        }

        if (in.atWar()) {
            return decision(
                    RealmStatePriority.MILITARY_MOBILIZATION,
                    650,
                    false,
                    false,
                    in.viability() < 500,
                    clamp(650 + (600 - Math.min(600, in.security())) / 2),
                    REASON_WAR);
        }

        if (in.productivity() < 550) {
            return decision(
                    RealmStatePriority.INDUSTRY,
                    650,
                    true,
                    false,
                    false,
                    clamp(550 + (550 - in.productivity()) / 2),
                    REASON_PRODUCTIVITY);
        }

        if (in.marketAccess() < 550) {
            return decision(
                    RealmStatePriority.TRADE,
                    600,
                    true,
                    false,
                    false,
                    clamp(520 + (550 - in.marketAccess()) / 2),
                    REASON_MARKET);
        }

        if (in.security() < 550) {
            return decision(
                    RealmStatePriority.FORTIFICATION,
                    550,
                    true,
                    false,
                    false,
                    clamp(520 + (550 - in.security()) / 2),
                    REASON_SECURITY);
        }

        if (in.phase().permitsExpansion()
                && in.expansionReadiness() >= 700
                && in.administrativeReserve() >= 550
                && in.treasuryCoverage() >= 500) {
            return decision(
                    RealmStatePriority.EXPANSION,
                    650,
                    true,
                    true,
                    false,
                    clamp(600 + (in.expansionReadiness() - 700) / 2),
                    REASON_EXPANSION);
        }

        return decision(
                RealmStatePriority.CIVIC_GROWTH,
                500,
                true,
                false,
                false,
                clamp(450 + in.viability() / 5),
                REASON_CIVIC);
    }

    private static RealmStateDecision decision(
            RealmStatePriority priority,
            int investment,
            boolean construction,
            boolean expansion,
            boolean peace,
            int pressure,
            int reasons) {
        return new RealmStateDecision(
                priority,
                investment,
                construction,
                expansion,
                peace,
                clamp(pressure),
                reasons);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(1000, value));
    }
}
