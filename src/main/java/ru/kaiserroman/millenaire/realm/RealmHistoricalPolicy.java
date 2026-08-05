package ru.kaiserroman.millenaire.realm;

/**
 * Variable-tempo historical state machine. Mild structural weakness can persist for centuries;
 * severe war, famine and capital loss create self-reinforcing collapses measured in decades.
 */
public final class RealmHistoricalPolicy {
    public static final int MOMENTUM_THRESHOLD = 1_000_000;
    public static final int EXPANSION_THRESHOLD = 700;

    public static final int REASON_POPULATION = 1;
    public static final int REASON_FOOD = 1 << 1;
    public static final int REASON_FISCAL = 1 << 2;
    public static final int REASON_MILITARY = 1 << 3;
    public static final int REASON_ADMINISTRATION = 1 << 4;
    public static final int REASON_WAR = 1 << 5;
    public static final int REASON_CRISIS = 1 << 6;
    public static final int REASON_CAPITAL = 1 << 7;
    public static final int REASON_RECOVERY = 1 << 8;
    public static final int REASON_EXPANSION = 1 << 9;

    public RealmHistoricalAssessment initial(RealmHistoricalInputs inputs, long currentMilliYear) {
        if (inputs == null) throw new NullPointerException("inputs");
        if (currentMilliYear < 0L) throw new IllegalArgumentException("Negative historical year");
        Scores scores = scores(inputs);
        RealmHistoricalPhase phase = initialPhase(scores.viability, scores.expansionReadiness);
        RealmScale scale = desiredScale(inputs, scores.viability);
        return new RealmHistoricalAssessment(
                phase,
                scale,
                scores.stateCapacity,
                scores.crisisBurden,
                scores.viability,
                scores.expansionReadiness,
                0,
                0,
                crisisRate(phase, scores.viability, inputs),
                recoveryRate(phase, scores.viability),
                scores.reasonMask,
                currentMilliYear,
                true,
                true,
                mayExpand(phase, scores.viability, scores.expansionReadiness, inputs),
                terminal(inputs));
    }

    public RealmHistoricalAssessment evaluate(
            RealmHistoricalPhase currentPhase,
            RealmScale currentScale,
            int currentCrisisMomentum,
            int currentRecoveryMomentum,
            long phaseSinceMilliYear,
            RealmHistoricalInputs inputs,
            int elapsedMilliYears,
            long currentMilliYear) {
        if (currentPhase == null || currentScale == null || inputs == null) {
            throw new NullPointerException("historical evaluation");
        }
        if (currentCrisisMomentum < 0 || currentCrisisMomentum > MOMENTUM_THRESHOLD
                || currentRecoveryMomentum < 0 || currentRecoveryMomentum > MOMENTUM_THRESHOLD
                || phaseSinceMilliYear < 0L || elapsedMilliYears < 0 || currentMilliYear < phaseSinceMilliYear) {
            throw new IllegalArgumentException("Invalid historical state");
        }

        Scores scores = scores(inputs);
        int crisisRate = crisisRate(currentPhase, scores.viability, inputs);
        int recoveryRate = recoveryRate(currentPhase, scores.viability);
        int crisisMomentum = moveMomentum(
                currentCrisisMomentum,
                crisisRate,
                recoveryRate / 2,
                elapsedMilliYears);
        int recoveryMomentum = moveMomentum(
                currentRecoveryMomentum,
                recoveryRate,
                crisisRate / 2,
                elapsedMilliYears);

        RealmHistoricalPhase nextPhase = currentPhase;
        if (terminal(inputs)) {
            nextPhase = RealmHistoricalPhase.COLLAPSING;
            crisisMomentum = MOMENTUM_THRESHOLD;
            recoveryMomentum = 0;
        } else if (crisisMomentum >= MOMENTUM_THRESHOLD
                && (scores.viability < 650
                        || inputs.warExhaustion() >= 800
                        || inputs.crisisSeverity() >= 800)) {
            nextPhase = decline(currentPhase);
            crisisMomentum = 0;
            recoveryMomentum = 0;
        } else if (recoveryMomentum >= MOMENTUM_THRESHOLD && scores.viability >= 550) {
            nextPhase = recover(currentPhase, scores.viability, scores.expansionReadiness);
            crisisMomentum = 0;
            recoveryMomentum = 0;
        }

        RealmScale nextScale = desiredScale(inputs, scores.viability);
        if (nextPhase == RealmHistoricalPhase.COLLAPSING) nextScale = nextScale.lower();
        boolean phaseChanged = nextPhase != currentPhase;
        boolean scaleChanged = nextScale != currentScale;
        long nextPhaseSince = phaseChanged ? currentMilliYear : phaseSinceMilliYear;
        int reasons = scores.reasonMask;
        if (recoveryRate > crisisRate) reasons |= REASON_RECOVERY;
        boolean expansion = mayExpand(nextPhase, scores.viability, scores.expansionReadiness, inputs);
        if (expansion) reasons |= REASON_EXPANSION;
        return new RealmHistoricalAssessment(
                nextPhase,
                nextScale,
                scores.stateCapacity,
                scores.crisisBurden,
                scores.viability,
                scores.expansionReadiness,
                crisisMomentum,
                recoveryMomentum,
                crisisRate,
                recoveryRate,
                reasons,
                nextPhaseSince,
                phaseChanged,
                scaleChanged,
                expansion,
                terminal(inputs));
    }

    public int formationPressure(RealmHistoricalInputs inputs) {
        Scores scores = scores(inputs);
        if (!inputs.capitalExists() || inputs.settlementCount() <= 0 || inputs.population() <= 0L) return 0;
        int foundation = scores.stateCapacity * 60 / 100
                + scores.viability * 25 / 100
                + inputs.strategicAmbition() * 15 / 100;
        if (inputs.settlementCount() == 1 && inputs.populationReadiness() < 450) foundation -= 180;
        return clamp(foundation);
    }

    public boolean mayFormCityState(RealmHistoricalInputs inputs, int qualifyingMilliYears) {
        return mayFormCityState(inputs, qualifyingMilliYears, 8_000);
    }

    public boolean mayFormCityState(
            RealmHistoricalInputs inputs,
            int qualifyingMilliYears,
            int requiredMilliYears) {
        if (qualifyingMilliYears < 0 || requiredMilliYears <= 0) {
            throw new IllegalArgumentException("Invalid historical qualification");
        }
        Scores scores = scores(inputs);
        return inputs.settlementCount() == 1
                && inputs.capitalExists()
                && inputs.populationReadiness() >= 450
                && scores.stateCapacity >= 600
                && scores.viability >= 550
                && formationPressure(inputs) >= 650
                && qualifyingMilliYears >= requiredMilliYears;
    }

    private static Scores scores(RealmHistoricalInputs inputs) {
        int capacity = clamp((int) ((inputs.populationReadiness() * 18L
                + inputs.foodCoverage() * 17L
                + inputs.fiscalCapacity() * 15L
                + inputs.militaryPower() * 14L
                + inputs.stability() * 12L
                + inputs.productiveCapital() * 10L
                + inputs.marketAccess() * 6L
                + inputs.legitimacy() * 8L) / 100L));
        int burden = clamp((int) ((inputs.warExhaustion() * 25L
                + inputs.crisisSeverity() * 22L
                + (1000 - inputs.administrativeReserve()) * 18L
                + (1000 - inputs.stability()) * 14L
                + (1000 - inputs.foodCoverage()) * 12L
                + (1000 - inputs.legitimacy()) * 9L) / 100L));
        int viability = clamp(capacity + 200 - burden * 3 / 5
                - (inputs.capitalExists() ? 0 : 350)
                - (inputs.populationReadiness() >= 200 ? 0 : 100));
        int expansion = clamp((int) ((inputs.fiscalCapacity() * 20L
                + inputs.militaryPower() * 25L
                + inputs.legitimacy() * 15L
                + inputs.administrativeReserve() * 15L
                + inputs.foodCoverage() * 10L
                + inputs.strategicAmbition() * 15L) / 100L));
        int reasons = 0;
        if (inputs.populationReadiness() < 450) reasons |= REASON_POPULATION;
        if (inputs.foodCoverage() < 500) reasons |= REASON_FOOD;
        if (inputs.fiscalCapacity() < 500) reasons |= REASON_FISCAL;
        if (inputs.militaryPower() < 400) reasons |= REASON_MILITARY;
        if (inputs.administrativeReserve() < 500) reasons |= REASON_ADMINISTRATION;
        if (inputs.warExhaustion() > 500) reasons |= REASON_WAR;
        if (inputs.crisisSeverity() > 500) reasons |= REASON_CRISIS;
        if (!inputs.capitalExists()) reasons |= REASON_CAPITAL;
        return new Scores(capacity, burden, viability, expansion, reasons);
    }

    private static int crisisRate(
            RealmHistoricalPhase phase,
            int viability,
            RealmHistoricalInputs inputs) {
        int rate;
        if (!inputs.capitalExists()) rate = 250_000;
        else if (viability < 150) rate = 100_000;
        else if (viability < 250) rate = 50_000;
        else if (viability < 380) rate = 20_000;
        else if (viability < 520) rate = 5_000;
        else rate = 0;
        rate += Math.max(0, inputs.warExhaustion() - 600) * 75;
        rate += Math.max(0, inputs.crisisSeverity() - 600) * 75;
        rate += switch (phase) {
            case STRAINED -> 5_000;
            case DECADENT -> 15_000;
            case COLLAPSING -> 30_000;
            default -> 0;
        };
        return Math.min(MOMENTUM_THRESHOLD, rate);
    }

    private static int recoveryRate(RealmHistoricalPhase phase, int viability) {
        int rate;
        if (viability >= 820) rate = 30_000;
        else if (viability >= 720) rate = 18_000;
        else if (viability >= 620) rate = 9_000;
        else if (viability >= 550) rate = 3_000;
        else rate = 0;
        if (phase == RealmHistoricalPhase.RESTORING) rate += 10_000;
        return rate;
    }

    private static int moveMomentum(int current, int gainRate, int decayRate, int elapsedMilliYears) {
        long gain = gainRate * (long) elapsedMilliYears / 1000L;
        long decay = decayRate * (long) elapsedMilliYears / 1000L;
        long next = current + gain - decay;
        return (int) Math.max(0L, Math.min(MOMENTUM_THRESHOLD, next));
    }

    private static RealmHistoricalPhase initialPhase(int viability, int expansionReadiness) {
        if (viability >= 780 && expansionReadiness >= EXPANSION_THRESHOLD) {
            return RealmHistoricalPhase.ASCENDANT;
        }
        if (viability >= 550) return RealmHistoricalPhase.STABLE;
        if (viability >= 400) return RealmHistoricalPhase.STRAINED;
        if (viability >= 250) return RealmHistoricalPhase.DECADENT;
        return RealmHistoricalPhase.COLLAPSING;
    }

    private static RealmHistoricalPhase decline(RealmHistoricalPhase phase) {
        return switch (phase) {
            case ASCENDANT -> RealmHistoricalPhase.STABLE;
            case STABLE, RESTORING -> RealmHistoricalPhase.STRAINED;
            case STRAINED -> RealmHistoricalPhase.DECADENT;
            case DECADENT, COLLAPSING -> RealmHistoricalPhase.COLLAPSING;
        };
    }

    private static RealmHistoricalPhase recover(
            RealmHistoricalPhase phase,
            int viability,
            int expansionReadiness) {
        return switch (phase) {
            case COLLAPSING, DECADENT, STRAINED -> RealmHistoricalPhase.RESTORING;
            case RESTORING -> viability >= 780 && expansionReadiness >= EXPANSION_THRESHOLD
                    ? RealmHistoricalPhase.ASCENDANT
                    : RealmHistoricalPhase.STABLE;
            case STABLE -> viability >= 780 && expansionReadiness >= EXPANSION_THRESHOLD
                    ? RealmHistoricalPhase.ASCENDANT
                    : RealmHistoricalPhase.STABLE;
            case ASCENDANT -> RealmHistoricalPhase.ASCENDANT;
        };
    }

    private static RealmScale desiredScale(RealmHistoricalInputs inputs, int viability) {
        RealmScale scale;
        if (inputs.settlementCount() <= 1) scale = RealmScale.CITY_STATE;
        else if (inputs.settlementCount() <= 4) scale = RealmScale.REGIONAL_STATE;
        else if (inputs.settlementCount() <= 11) scale = RealmScale.KINGDOM;
        else scale = RealmScale.EMPIRE;
        if (viability < 450 || inputs.administrativeReserve() < 400) scale = scale.lower();
        return scale;
    }

    private static boolean mayExpand(
            RealmHistoricalPhase phase,
            int viability,
            int expansionReadiness,
            RealmHistoricalInputs inputs) {
        return phase.permitsExpansion()
                && viability >= 600
                && expansionReadiness >= EXPANSION_THRESHOLD
                && inputs.administrativeReserve() >= 550
                && inputs.foodCoverage() >= 550
                && inputs.capitalExists();
    }

    private static boolean terminal(RealmHistoricalInputs inputs) {
        return !inputs.capitalExists()
                && (inputs.population() == 0L || inputs.populationReadiness() == 0);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(1000, value));
    }

    private record Scores(
            int stateCapacity,
            int crisisBurden,
            int viability,
            int expansionReadiness,
            int reasonMask) {}
}
