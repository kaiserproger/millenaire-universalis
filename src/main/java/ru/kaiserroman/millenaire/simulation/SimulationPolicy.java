package ru.kaiserroman.millenaire.simulation;

/** Hard runtime budgets plus historical-time lifecycle hysteresis. */
public record SimulationPolicy(
        int maximumSettlements,
        int commodityCount,
        long cycleIntervalTicks,
        long historicalYearTicks,
        int rowsPerTick,
        int declineGraceYears,
        int abandonmentGraceYears,
        int missingYearsBeforeRuin,
        int foundingCooldownYears,
        int foundingPopulation,
        int minimumViablePopulation,
        int maximumCatchUpCycles) {

    public SimulationPolicy {
        if (maximumSettlements <= 0 || commodityCount <= 0 || commodityCount > 64
                || cycleIntervalTicks <= 0L || historicalYearTicks <= 0L || rowsPerTick <= 0
                || declineGraceYears <= 0 || abandonmentGraceYears <= declineGraceYears
                || missingYearsBeforeRuin <= 0 || foundingCooldownYears <= 0
                || foundingPopulation <= 0 || minimumViablePopulation <= 0
                || maximumCatchUpCycles <= 0) {
            throw new IllegalArgumentException("Invalid simulation policy");
        }
    }

    public int declineGraceMilliYears() {
        return yearsToMilliYears(declineGraceYears);
    }

    public int abandonmentGraceMilliYears() {
        return yearsToMilliYears(abandonmentGraceYears);
    }

    public int missingMilliYearsBeforeRuin() {
        return yearsToMilliYears(missingYearsBeforeRuin);
    }

    public int foundingCooldownMilliYears() {
        return yearsToMilliYears(foundingCooldownYears);
    }

    public static SimulationPolicy defaults(int commodityCount) {
        return new SimulationPolicy(
                4_096,
                commodityCount,
                24_000L,
                1_728_000L,
                16,
                8,
                25,
                5,
                30,
                120,
                8,
                32);
    }

    private static int yearsToMilliYears(int years) {
        return years > Integer.MAX_VALUE / 1000 ? Integer.MAX_VALUE : years * 1000;
    }
}
