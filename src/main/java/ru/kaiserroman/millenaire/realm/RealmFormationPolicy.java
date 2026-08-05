package ru.kaiserroman.millenaire.realm;

/** Legacy pressure helper; qualification parameters are schema-3 historical milli-years. */
public final class RealmFormationPolicy {
    public static final int FORMATION_THRESHOLD = 650;
    public static final int DISSOLUTION_THRESHOLD = 700;
    public static final int SECESSION_THRESHOLD = 760;

    public int formationPressure(FormationContext context) {
        require(context);
        if (context.settlementCount() < 2 || context.population() < 40L) {
            return 0;
        }
        long pressure = 0L;
        pressure += context.tradeConnectivity() * 20L;
        pressure += context.culturalCohesion() * 18L;
        pressure += context.securityPressure() * 15L;
        pressure += context.leaderAuthority() * 16L;
        pressure += context.geographicCompactness() * 12L;
        pressure += context.treasuryReadiness() * 9L;
        pressure += context.sponsorCommitment() * 10L;
        return clamp((int) (pressure / 100L));
    }

    public boolean shouldForm(FormationContext context, int qualifyingMilliYears) {
        if (qualifyingMilliYears < 0) {
            throw new IllegalArgumentException("qualifyingMilliYears must be non-negative");
        }
        int requiredMilliYears = context.sponsorCommitment() >= 800 ? 3_000 : 8_000;
        return qualifyingMilliYears >= requiredMilliYears
                && formationPressure(context) >= FORMATION_THRESHOLD;
    }

    public int dissolutionPressure(DissolutionContext context) {
        require(context);
        if (!context.capitalExists() || context.memberCount() == 0) {
            return 1000;
        }
        long pressure = 0L;
        pressure += (1000 - context.legitimacy()) * 22L;
        pressure += (1000 - context.culturalCohesion()) * 15L;
        pressure += (1000 - context.treasuryCoverage()) * 12L;
        pressure += context.warExhaustion() * 18L;
        pressure += context.separatism() * 23L;
        pressure += (1000 - context.administrativeReach()) * 10L;
        return clamp((int) (pressure / 100L));
    }

    public boolean shouldDissolve(DissolutionContext context, int qualifyingMilliYears) {
        if (qualifyingMilliYears < 0) {
            throw new IllegalArgumentException("qualifyingMilliYears must be non-negative");
        }
        if (!context.capitalExists() || context.memberCount() == 0) {
            return true;
        }
        return qualifyingMilliYears >= 6_000
                && dissolutionPressure(context) >= DISSOLUTION_THRESHOLD;
    }

    public int secessionPressure(DissolutionContext context) {
        require(context);
        if (context.memberCount() < 4) return 0;
        long pressure = context.separatism() * 35L
                + (1000 - context.administrativeReach()) * 25L
                + (1000 - context.culturalCohesion()) * 20L
                + context.warExhaustion() * 20L;
        return clamp((int) (pressure / 100L));
    }

    public boolean shouldSplit(DissolutionContext context, int qualifyingMilliYears) {
        return qualifyingMilliYears >= 8_000
                && secessionPressure(context) >= SECESSION_THRESHOLD;
    }

    private static void require(Object value) {
        if (value == null) throw new NullPointerException("realm lifecycle context");
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(1000, value));
    }

    public record FormationContext(
            int settlementCount,
            long population,
            int tradeConnectivity,
            int culturalCohesion,
            int securityPressure,
            int leaderAuthority,
            int geographicCompactness,
            int treasuryReadiness,
            int sponsorCommitment) {
        public FormationContext {
            if (settlementCount < 0 || population < 0L) {
                throw new IllegalArgumentException("Negative formation size");
            }
            requireIndex(tradeConnectivity, "tradeConnectivity");
            requireIndex(culturalCohesion, "culturalCohesion");
            requireIndex(securityPressure, "securityPressure");
            requireIndex(leaderAuthority, "leaderAuthority");
            requireIndex(geographicCompactness, "geographicCompactness");
            requireIndex(treasuryReadiness, "treasuryReadiness");
            requireIndex(sponsorCommitment, "sponsorCommitment");
        }
    }

    public record DissolutionContext(
            int memberCount,
            boolean capitalExists,
            int legitimacy,
            int culturalCohesion,
            int treasuryCoverage,
            int warExhaustion,
            int separatism,
            int administrativeReach) {
        public DissolutionContext {
            if (memberCount < 0) throw new IllegalArgumentException("Negative memberCount");
            requireIndex(legitimacy, "legitimacy");
            requireIndex(culturalCohesion, "culturalCohesion");
            requireIndex(treasuryCoverage, "treasuryCoverage");
            requireIndex(warExhaustion, "warExhaustion");
            requireIndex(separatism, "separatism");
            requireIndex(administrativeReach, "administrativeReach");
        }
    }

    private static void requireIndex(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " outside 0..1000");
        }
    }
}
