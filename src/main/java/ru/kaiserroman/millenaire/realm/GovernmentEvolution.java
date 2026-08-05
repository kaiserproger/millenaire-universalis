package ru.kaiserroman.millenaire.realm;

/**
 * Deterministic, path-dependent constitutional evolution. Forms compete as institutional
 * attractors; hysteresis prevents a Realm from changing government without years of pressure.
 */
public final class GovernmentEvolution {
    public static final int REASON_TRADE = 1;
    public static final int REASON_URBANIZATION = 1 << 1;
    public static final int REASON_EXTERNAL_THREAT = 1 << 2;
    public static final int REASON_CIVIC_TRADITION = 1 << 3;
    public static final int REASON_ELITE_CONFLICT = 1 << 4;
    public static final int REASON_LEGITIMACY_CRISIS = 1 << 5;
    public static final int REASON_BUREAUCRACY = 1 << 6;
    public static final int REASON_INEQUALITY = 1 << 7;

    private static final int BASE_CHANGE_THRESHOLD = 145;
    private static final int MINIMUM_STABLE_MILLI_YEARS = 12_000;

    public EvolutionDecision evaluate(
            Constitution constitution,
            RealmIndicators indicators,
            int stableMilliYears) {
        if (constitution == null || indicators == null) {
            throw new NullPointerException("evolution input");
        }
        if (stableMilliYears < 0) {
            throw new IllegalArgumentException("stableMilliYears must be non-negative");
        }

        GovernmentForm current = constitution.government();
        GovernmentForm best = current;
        int currentScore = score(current, constitution, indicators);
        int bestScore = currentScore;
        for (GovernmentForm candidate : GovernmentForm.values()) {
            int candidateScore = score(candidate, constitution, indicators);
            if (candidateScore > bestScore) {
                bestScore = candidateScore;
                best = candidate;
            }
        }

        boolean crisis = indicators.legitimacy() < 250
                || indicators.warExhaustion() > 850
                || indicators.eliteCompetition() > 900;
        int missingMilliYears = Math.max(0, MINIMUM_STABLE_MILLI_YEARS - stableMilliYears);
        int waitingPenalty = saturatedInt((missingMilliYears * 18L + 999L) / 1000L);
        int required = BASE_CHANGE_THRESHOLD + waitingPenalty - (crisis ? 80 : 0);
        required = Math.max(55, required);
        int pressure = bestScore - currentScore;
        boolean changes = best != current && pressure >= required;
        return new EvolutionDecision(
                current,
                changes ? best : current,
                pressure,
                required,
                reasons(indicators),
                changes);
    }

    /** Applies a decision gradually so institutional values do not teleport with the label. */
    public Constitution apply(Constitution constitution, EvolutionDecision decision, int reformStep) {
        if (constitution == null || decision == null) {
            throw new NullPointerException("evolution decision");
        }
        if (!decision.changesGovernment()) {
            return constitution;
        }
        int legitimacyCost = Math.max(15, Math.min(120, decision.pressure() / 4));
        return constitution
                .towards(decision.proposed(), reformStep)
                .withLegitimacy(constitution.legitimacy() - legitimacyCost);
    }

    private static int score(
            GovernmentForm form,
            Constitution current,
            RealmIndicators indicators) {
        int populationScale = populationScale(indicators.population());
        int desiredCentralization = clamp(
                170
                        + indicators.externalThreat() * 3 / 10
                        + indicators.rulerAuthority() * 3 / 10
                        + populationScale / 5
                        - indicators.civicTradition() / 5);
        int desiredBureaucracy = clamp(
                indicators.bureaucracyCapacity() * 3 / 4
                        + indicators.urbanization() / 6
                        + populationScale / 8);
        int desiredNoblePower = clamp(
                indicators.landInequality() * 3 / 4
                        + indicators.eliteCompetition() / 4
                        - indicators.marketIntegration() / 5);
        int desiredMerchantPower = clamp(
                indicators.marketIntegration() * 2 / 5
                        + indicators.tradeDependence() * 2 / 5
                        + indicators.prosperity() / 5);
        int desiredCitizenPower = clamp(
                indicators.civicTradition() * 3 / 5
                        + indicators.urbanization() / 5
                        + indicators.culturalCohesion() / 5);
        int desiredMarketFreedom = clamp(
                indicators.marketIntegration() * 3 / 5
                        + indicators.tradeDependence() / 5
                        + indicators.prosperity() / 5);
        int desiredLandConcentration = indicators.landInequality();
        int desiredMilitarization = clamp(
                indicators.externalThreat() / 2
                        + indicators.rulerAuthority() / 5
                        + 180
                        - indicators.warExhaustion() / 4);

        long distance = 0L;
        distance += Math.abs(form.centralization() - desiredCentralization);
        distance += Math.abs(form.bureaucracy() - desiredBureaucracy);
        distance += Math.abs(form.noblePower() - desiredNoblePower);
        distance += Math.abs(form.merchantPower() - desiredMerchantPower);
        distance += Math.abs(form.citizenPower() - desiredCitizenPower);
        distance += Math.abs(form.marketFreedom() - desiredMarketFreedom);
        distance += Math.abs(form.landConcentration() - desiredLandConcentration);
        distance += Math.abs(form.militarization() - desiredMilitarization);
        int value = 1000 - (int) (distance / 8L);

        if (form == current.government()) value += 90;
        value += archetypeBonus(form, indicators, desiredMerchantPower, desiredCitizenPower);
        return value;
    }

    private static int archetypeBonus(
            GovernmentForm form,
            RealmIndicators indicators,
            int desiredMerchantPower,
            int desiredCitizenPower) {
        return switch (form) {
            case CLAN_CONFEDERATION -> indicators.settlementCount() <= 3
                    ? (1000 - indicators.bureaucracyCapacity()) / 6
                    : -100;
            case FEUDAL_MONARCHY -> (indicators.landInequality() - indicators.urbanization()) / 5;
            case ESTATE_MONARCHY -> indicators.eliteCompetition() / 8
                    + indicators.marketIntegration() / 12;
            case BUREAUCRATIC_MONARCHY -> indicators.bureaucracyCapacity() / 6
                    + (indicators.settlementCount() >= 6 ? 70 : 0);
            case COMMERCIAL_MONARCHY -> desiredMerchantPower / 6
                    + indicators.rulerAuthority() / 8;
            case MERCHANT_REPUBLIC -> desiredMerchantPower / 5
                    + indicators.civicTradition() / 8
                    - indicators.rulerAuthority() / 10;
            case CITY_LEAGUE -> indicators.settlementCount() >= 2 && indicators.settlementCount() <= 9
                    ? desiredMerchantPower / 6 + desiredCitizenPower / 8
                    : -120;
            case CITIZEN_POLITY -> indicators.settlementCount() <= 5
                    ? desiredCitizenPower / 5 - indicators.landInequality() / 8
                    : -140;
            case OLIGARCHIC_POLITY -> desiredMerchantPower / 7
                    + indicators.landInequality() / 8
                    - indicators.civicTradition() / 10;
            case MILITARY_AUTOCRACY -> indicators.externalThreat() / 5
                    + (1000 - indicators.legitimacy()) / 8
                    - indicators.warExhaustion() / 10;
        };
    }

    private static int reasons(RealmIndicators indicators) {
        int reasons = 0;
        if (indicators.marketIntegration() >= 650 || indicators.tradeDependence() >= 650) {
            reasons |= REASON_TRADE;
        }
        if (indicators.urbanization() >= 600) reasons |= REASON_URBANIZATION;
        if (indicators.externalThreat() >= 650) reasons |= REASON_EXTERNAL_THREAT;
        if (indicators.civicTradition() >= 650) reasons |= REASON_CIVIC_TRADITION;
        if (indicators.eliteCompetition() >= 650) reasons |= REASON_ELITE_CONFLICT;
        if (indicators.legitimacy() <= 350) reasons |= REASON_LEGITIMACY_CRISIS;
        if (indicators.bureaucracyCapacity() >= 650) reasons |= REASON_BUREAUCRACY;
        if (indicators.landInequality() >= 650) reasons |= REASON_INEQUALITY;
        return reasons;
    }

    private static int populationScale(long population) {
        if (population < 50L) return 100;
        if (population < 200L) return 300;
        if (population < 1_000L) return 500;
        if (population < 5_000L) return 700;
        return 900;
    }

    private static int saturatedInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(1000, value));
    }
}
