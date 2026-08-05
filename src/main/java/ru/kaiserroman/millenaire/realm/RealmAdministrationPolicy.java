package ru.kaiserroman.millenaire.realm;

/**
 * Anti-snowball policy for administrative reach, fiscal leakage and separatism. The policy is pure:
 * it does not mutate a Realm and can therefore be evaluated off-thread on immutable snapshots.
 */
public final class RealmAdministrationPolicy {
    public static final int REASON_SIZE = 1;
    public static final int REASON_POPULATION = 1 << 1;
    public static final int REASON_CAPACITY_DEFICIT = 1 << 2;
    public static final int REASON_WAR_EXHAUSTION = 1 << 3;
    public static final int REASON_CULTURAL_FRAGMENTATION = 1 << 4;
    public static final int REASON_ELITE_CAPTURE = 1 << 5;
    public static final int REASON_FISCAL_LEAKAGE = 1 << 6;
    public static final int REASON_SECESSION_RISK = 1 << 7;

    public AdministrativeAssessment evaluate(
            Constitution constitution,
            RealmIndicators indicators) {
        if (constitution == null || indicators == null) {
            throw new NullPointerException("Realm administration input");
        }

        int sizeLoad = Math.min(620, Math.max(0, indicators.settlementCount() - 1) * 58);
        int populationLoad = (int) Math.min(320L, indicators.population() / 30L);
        int fragmentationLoad = (1000 - indicators.culturalCohesion()) * 22 / 100;
        int conflictLoad = indicators.warExhaustion() * 20 / 100
                + indicators.externalThreat() * 8 / 100;
        int eliteLoad = indicators.eliteCompetition() * 14 / 100;
        int load = clamp(110 + sizeLoad + populationLoad + fragmentationLoad + conflictLoad + eliteLoad);

        int capacity = clamp(
                constitution.bureaucracy() * 35 / 100
                        + indicators.bureaucracyCapacity() * 35 / 100
                        + constitution.centralization() * 15 / 100
                        + indicators.marketIntegration() * 10 / 100
                        + indicators.civicTradition() * 5 / 100);
        int coverage = clamp(850 + (capacity - load) * 3 / 4);
        int corruption = clamp(
                (1000 - coverage) * 55 / 100
                        + indicators.landInequality() * 20 / 100
                        + indicators.eliteCompetition() * 20 / 100
                        - indicators.civicTradition() * 15 / 100
                        - indicators.legitimacy() * 10 / 100);
        int taxEfficiency = clamp(
                coverage * 65 / 100
                        + indicators.marketIntegration() * 15 / 100
                        + indicators.prosperity() * 10 / 100
                        + indicators.legitimacy() * 10 / 100
                        - corruption * 25 / 100);
        int separatism = clamp(
                (1000 - indicators.culturalCohesion()) * 30 / 100
                        + (1000 - coverage) * 30 / 100
                        + indicators.warExhaustion() * 20 / 100
                        + indicators.eliteCompetition() * 10 / 100
                        + (1000 - indicators.rulerAuthority()) * 10 / 100
                        + corruption * 20 / 100);
        int legitimacyDelta = clampDelta(
                (indicators.prosperity() - 500) / 60
                        + (coverage - 650) / 50
                        + (indicators.culturalCohesion() - 500) / 80
                        - indicators.warExhaustion() / 70
                        - corruption / 60);

        int reasons = 0;
        if (indicators.settlementCount() >= 6) reasons |= REASON_SIZE;
        if (indicators.population() >= 3_000L) reasons |= REASON_POPULATION;
        if (load > capacity) reasons |= REASON_CAPACITY_DEFICIT;
        if (indicators.warExhaustion() >= 500) reasons |= REASON_WAR_EXHAUSTION;
        if (indicators.culturalCohesion() < 500) reasons |= REASON_CULTURAL_FRAGMENTATION;
        if (indicators.eliteCompetition() >= 600 || indicators.landInequality() >= 700) {
            reasons |= REASON_ELITE_CAPTURE;
        }
        if (taxEfficiency < 600) reasons |= REASON_FISCAL_LEAKAGE;
        if (separatism >= 550) reasons |= REASON_SECESSION_RISK;

        return new AdministrativeAssessment(
                capacity,
                load,
                coverage,
                corruption,
                taxEfficiency,
                separatism,
                legitimacyDelta,
                reasons);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(1000, value));
    }

    private static int clampDelta(int value) {
        return Math.max(-30, Math.min(20, value));
    }
}
