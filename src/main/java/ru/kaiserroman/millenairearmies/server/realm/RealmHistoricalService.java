package ru.kaiserroman.millenairearmies.server.realm;

import com.mojang.logging.LogUtils;
import java.util.Arrays;
import java.util.UUID;
import org.slf4j.Logger;
import ru.kaiserroman.millenaire.realm.AdministrativeAssessment;
import ru.kaiserroman.millenaire.realm.Constitution;
import ru.kaiserroman.millenaire.realm.GovernmentForm;
import ru.kaiserroman.millenaire.realm.RealmAdministrationPolicy;
import ru.kaiserroman.millenaire.realm.RealmHistoricalAssessment;
import ru.kaiserroman.millenaire.realm.RealmHistoricalInputs;
import ru.kaiserroman.millenaire.realm.RealmHistoricalPhase;
import ru.kaiserroman.millenaire.realm.RealmHistoricalPolicy;
import ru.kaiserroman.millenaire.realm.RealmHistoryLedger;
import ru.kaiserroman.millenaire.realm.RealmIndicators;
import ru.kaiserroman.millenaire.realm.RealmInstitutionLedger;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenaire.realm.RealmScale;
import ru.kaiserroman.millenaire.simulation.PackedSettlementSimulationState;
import ru.kaiserroman.millenaire.simulation.SettlementStatus;
import ru.kaiserroman.millenaire.simulation.SettlementTier;
import ru.kaiserroman.millenaire.simulation.SimulationShockLedger;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;

/**
 * Converts live Simulation aggregates into variable-tempo historical Realm phases. Technical
 * evaluation frequency is independent from the configured historical year length.
 */
public final class RealmHistoricalService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FOOD_COMMODITY = 0;
    private static final int ARMS_COMMODITY = 6;

    private final RealmSavedData realms;
    private final SimulationSavedData simulation;
    private final RealmRegistry registry;
    private final RealmInstitutionLedger institutions;
    private final RealmHistoryLedger history;
    private final RealmHistoricalPolicy policy = new RealmHistoricalPolicy();
    private final RealmAdministrationPolicy administration = new RealmAdministrationPolicy();
    private final int realmsPerTick;
    private final int minimumCityPopulation;
    private final long evaluationIntervalTicks;
    private final long historicalYearTicks;
    private final long simulationCycleTicks;

    private final long[] realmIds;
    private final int[] settlementCounts;
    private final long[] populations;
    private final long[] urbanPopulations;
    private final long[] largestPopulations;
    private final long[] marketSums;
    private final long[] specializationSums;
    private final long[] bureaucracySums;
    private final long[] securitySums;
    private final long[] stabilitySums;
    private final long[] capitalSums;
    private final long[] prosperitySums;
    private final long[] foodStocks;
    private final long[] foodFlows;
    private final long[] armsStocks;
    private final long[] damageSums;
    private final long[] shockSums;
    private final int[] maximumThreats;
    private final int[] cultureCandidates;
    private final long[] cultureBalances;
    private final long[] dominantCulturePopulations;

    private final long[] realmMapKeys;
    private final int[] realmMapRows;
    private final int[] realmMapEpochs;
    private final int realmMapMask;
    private int realmMapEpoch;

    private int snapshotRealmCount;
    private int evaluationCursor;
    private long snapshotGameTime;
    private long snapshotSimulationCycle;
    private long snapshotMilliYear;
    private long nextSnapshotTick;
    private long snapshotCount;
    private long evaluationCount;
    private long phaseChangeCount;
    private long scaleChangeCount;
    private long expansionReadyCount;
    private long terminalCollapseCount;
    private int lastTickWorkUnits;

    public RealmHistoricalService(
            RealmSavedData realms,
            SimulationSavedData simulation,
            int maximumRealms,
            int maximumSettlements,
            int realmsPerTick,
            int minimumCityPopulation,
            long evaluationIntervalTicks,
            long historicalYearTicks,
            long simulationCycleTicks) {
        if (realms == null || simulation == null) throw new NullPointerException("historical stores");
        if (maximumRealms <= 0 || maximumSettlements <= 0 || realmsPerTick <= 0
                || minimumCityPopulation <= 0 || evaluationIntervalTicks <= 0L
                || historicalYearTicks <= 0L || simulationCycleTicks <= 0L) {
            throw new IllegalArgumentException("Invalid historical bounds");
        }
        this.realms = realms;
        this.simulation = simulation;
        registry = realms.registry();
        institutions = realms.institutions();
        history = realms.history();
        this.realmsPerTick = realmsPerTick;
        this.minimumCityPopulation = minimumCityPopulation;
        this.evaluationIntervalTicks = evaluationIntervalTicks;
        this.historicalYearTicks = historicalYearTicks;
        this.simulationCycleTicks = simulationCycleTicks;

        realmIds = new long[maximumRealms];
        settlementCounts = new int[maximumRealms];
        populations = new long[maximumRealms];
        urbanPopulations = new long[maximumRealms];
        largestPopulations = new long[maximumRealms];
        marketSums = new long[maximumRealms];
        specializationSums = new long[maximumRealms];
        bureaucracySums = new long[maximumRealms];
        securitySums = new long[maximumRealms];
        stabilitySums = new long[maximumRealms];
        capitalSums = new long[maximumRealms];
        prosperitySums = new long[maximumRealms];
        foodStocks = new long[maximumRealms];
        foodFlows = new long[maximumRealms];
        armsStocks = new long[maximumRealms];
        damageSums = new long[maximumRealms];
        shockSums = new long[maximumRealms];
        maximumThreats = new int[maximumRealms];
        cultureCandidates = new int[maximumRealms];
        cultureBalances = new long[maximumRealms];
        dominantCulturePopulations = new long[maximumRealms];

        int mapCapacity = powerOfTwoAtLeast(Math.max(16L, maximumRealms * 2L));
        realmMapKeys = new long[mapCapacity];
        realmMapRows = new int[mapCapacity];
        realmMapEpochs = new int[mapCapacity];
        realmMapMask = mapCapacity - 1;
    }

    public void tick(long gameTime) {
        if (gameTime < 0L) throw new IllegalArgumentException("Negative gameTime");
        lastTickWorkUnits = 0;
        if (evaluationCursor >= snapshotRealmCount && gameTime >= nextSnapshotTick) {
            buildSnapshot(gameTime);
            nextSnapshotTick = saturatedAdd(gameTime, evaluationIntervalTicks);
        }
        if (evaluationCursor >= snapshotRealmCount) return;

        boolean changed = false;
        int budget = Math.min(realmsPerTick, snapshotRealmCount - evaluationCursor);
        for (int processed = 0; processed < budget; processed++) {
            changed |= evaluateRealm(evaluationCursor++);
            lastTickWorkUnits++;
        }
        if (changed) realms.markChanged();
    }

    private void buildSnapshot(long gameTime) {
        beginRealmMapEpoch();
        snapshotRealmCount = 0;
        evaluationCursor = 0;
        registry.visitRealms((realmId, capitalMemberId, foundedCycle, government, legitimacy) -> {
            if (snapshotRealmCount == realmIds.length) {
                throw new IllegalStateException("Realm history snapshot capacity exhausted");
            }
            int row = snapshotRealmCount++;
            realmIds[row] = realmId;
            putRealmRow(realmId, row);
        });
        clearAggregates(snapshotRealmCount);

        PackedSettlementSimulationState state = simulation.state();
        SimulationShockLedger shocks = simulation.shocks();
        long simulationCycle = gameTime / simulationCycleTicks;
        for (int settlement = 0; settlement < state.size(); settlement++) {
            if (state.statusAt(settlement) == SettlementStatus.RUINED) continue;
            int row = findRealmRow(state.realmIdAt(settlement));
            if (row < 0) continue;
            long population = Math.max(0L, state.populationAt(settlement));
            settlementCounts[row]++;
            populations[row] = saturatedAdd(populations[row], population);
            largestPopulations[row] = Math.max(largestPopulations[row], population);
            if (state.tierAt(settlement).ordinal() >= SettlementTier.TOWN.ordinal()) {
                urbanPopulations[row] = saturatedAdd(urbanPopulations[row], population);
            }
            marketSums[row] = saturatedAdd(marketSums[row], state.marketAccessAt(settlement));
            specializationSums[row] = saturatedAdd(
                    specializationSums[row], state.specializationAt(settlement));
            bureaucracySums[row] = saturatedAdd(
                    bureaucracySums[row],
                    (state.educationAt(settlement) + state.productiveCapitalAt(settlement)) / 2L);
            securitySums[row] = saturatedAdd(securitySums[row], state.securityAt(settlement));
            stabilitySums[row] = saturatedAdd(stabilitySums[row], state.stabilityAt(settlement));
            capitalSums[row] = saturatedAdd(capitalSums[row], state.productiveCapitalAt(settlement));
            prosperitySums[row] = saturatedAdd(
                    prosperitySums[row],
                    (state.productivityAt(settlement)
                                    + state.stabilityAt(settlement)
                                    + state.attractivenessAt(settlement)
                                    + state.productiveCapitalAt(settlement))
                            / 4L);
            foodStocks[row] = saturatedAdd(foodStocks[row], state.stockAt(settlement, FOOD_COMMODITY));
            foodFlows[row] = saturatedAddSigned(foodFlows[row], state.netFlowAt(settlement, FOOD_COMMODITY));
            if (state.commodityCount() > ARMS_COMMODITY) {
                armsStocks[row] = saturatedAdd(armsStocks[row], state.stockAt(settlement, ARMS_COMMODITY));
            }
            damageSums[row] = saturatedAdd(damageSums[row], state.damageAt(settlement));
            updateCultureCandidate(row, state.cultureKeyAt(settlement), population);
            for (int shock = 0; shock < shocks.size(); shock++) {
                if (!shocks.matchesAt(
                        shock,
                        state.settlementIdAt(settlement),
                        state.regionKeyAt(settlement),
                        state.cultureKeyAt(settlement),
                        simulationCycle)) {
                    continue;
                }
                int magnitude = shocks.magnitudeAt(shock);
                shockSums[row] = saturatedAdd(shockSums[row], magnitude);
                maximumThreats[row] = Math.max(maximumThreats[row], magnitude);
            }
        }
        for (int settlement = 0; settlement < state.size(); settlement++) {
            int row = findRealmRow(state.realmIdAt(settlement));
            if (row >= 0
                    && state.statusAt(settlement) != SettlementStatus.RUINED
                    && state.cultureKeyAt(settlement) == cultureCandidates[row]) {
                dominantCulturePopulations[row] = saturatedAdd(
                        dominantCulturePopulations[row], state.populationAt(settlement));
            }
        }
        snapshotGameTime = gameTime;
        snapshotSimulationCycle = simulationCycle;
        snapshotMilliYear = historicalMilliYear(gameTime);
        snapshotCount++;
    }

    private boolean evaluateRealm(int row) {
        long realmId = realmIds[row];
        GovernmentForm government = registry.government(realmId);
        if (government == null) return false;
        Constitution constitution = institutions.constitution(realmId);
        if (constitution == null) {
            constitution = Constitution.archetype(government, registry.legitimacy(realmId));
            if (institutions.ensureRealm(realmId, constitution, snapshotMilliYear) < 0) {
                throw new IllegalStateException("Realm institution capacity exhausted");
            }
        }

        RealmIndicators indicators = indicators(row, constitution);
        AdministrativeAssessment administrative = administration.evaluate(constitution, indicators);
        RealmHistoricalInputs inputs = historicalInputs(row, realmId, constitution, administrative);
        RealmHistoricalPhase previousPhase = history.phase(realmId);
        RealmScale previousScale = history.scale(realmId);
        boolean seeded = previousPhase == null || previousScale == null;
        RealmHistoricalAssessment assessment;
        if (seeded) {
            assessment = policy.initial(inputs, snapshotMilliYear);
            if (history.ensureRealm(realmId, assessment, snapshotMilliYear) < 0) {
                throw new IllegalStateException("Realm history capacity exhausted");
            }
        } else {
            long previousEvaluation = history.lastEvaluationMilliYear(realmId);
            int elapsed = previousEvaluation < 0L
                    ? 0
                    : saturatedInt(Math.max(0L, snapshotMilliYear - previousEvaluation));
            assessment = policy.evaluate(
                    previousPhase,
                    previousScale,
                    history.crisisMomentum(realmId),
                    history.recoveryMomentum(realmId),
                    history.phaseSinceMilliYear(realmId),
                    inputs,
                    elapsed,
                    snapshotMilliYear);
            history.update(realmId, assessment, snapshotMilliYear);
        }

        if (!seeded && assessment.phaseChanged()) {
            phaseChangeCount++;
            applyPhaseLegitimacy(realmId, constitution, assessment.phase());
        }
        if (!seeded && assessment.scaleChanged()) scaleChangeCount++;
        if (assessment.mayExpand()) expansionReadyCount++;
        if (assessment.terminalCollapse()) terminalCollapseCount++;
        evaluationCount++;
        if (assessment.phaseChanged() || assessment.scaleChanged() || assessment.terminalCollapse()) {
            LOGGER.info(
                    "[BANNEROK_REALM_HISTORY] realm={} year_milli={} age_years={} phase={} scale={} viability={} capacity={} burden={} expansion={} crisis_momentum={} recovery_momentum={} crisis_rate={} recovery_rate={} reasons={} settlements={} population={} may_expand={} terminal={}",
                    realmId,
                    snapshotMilliYear,
                    Math.max(0L, snapshotMilliYear - history.foundedMilliYear(realmId)) / 1000L,
                    assessment.phase(),
                    assessment.scale(),
                    assessment.viability(),
                    assessment.stateCapacity(),
                    assessment.crisisBurden(),
                    assessment.expansionReadiness(),
                    assessment.crisisMomentum(),
                    assessment.recoveryMomentum(),
                    assessment.crisisRatePerYear(),
                    assessment.recoveryRatePerYear(),
                    assessment.reasonMask(),
                    settlementCounts[row],
                    populations[row],
                    assessment.mayExpand(),
                    assessment.terminalCollapse());
        }
        return true;
    }

    private RealmHistoricalInputs historicalInputs(
            int row,
            long realmId,
            Constitution constitution,
            AdministrativeAssessment administrative) {
        int settlements = settlementCounts[row];
        long population = populations[row];
        long populationTarget = Math.max(
                minimumCityPopulation,
                Math.max(1L, settlements) * (long) minimumCityPopulation / 2L);
        int populationReadiness = ratio(population, populationTarget);
        long foodTarget = Math.max(1L, population * 6L);
        int foodCoverage = ratio(foodStocks[row], foodTarget);
        if (foodFlows[row] < 0L) {
            foodCoverage = clamp(foodCoverage - ratio(absSaturated(foodFlows[row]), Math.max(1L, population)) / 3);
        } else if (foodFlows[row] > 0L) {
            foodCoverage = clamp(foodCoverage + ratio(foodFlows[row], Math.max(1L, population)) / 5);
        }
        int prosperity = average(prosperitySums[row], settlements);
        int treasuryCoverage = ratio(realms.treasury(realmId), Math.max(1L, population * 20L));
        int fiscalCapacity = clamp(treasuryCoverage * 35 / 100
                + prosperity * 40 / 100
                + average(marketSums[row], settlements) * 25 / 100);
        int armsCoverage = ratio(armsStocks[row], Math.max(1L, population * 2L));
        int militaryPower = clamp(average(securitySums[row], settlements) * 45 / 100
                + armsCoverage * 30 / 100
                + constitution.militarization() * 25 / 100);
        int headroom = administrative.capacity() <= 0
                ? 0
                : clamp((int) ((administrative.capacity() - (long) administrative.load())
                        * 1000L / administrative.capacity()));
        int administrativeReserve = clamp(administrative.coverage() * 70 / 100 + headroom * 30 / 100);
        int cohesion = ratio(dominantCulturePopulations[row], population);
        if (population == 0L) cohesion = 0;
        int averageDamage = average(damageSums[row], settlements);
        int averageShock = average(shockSums[row], settlements);
        int warExhaustion = clamp(averageDamage * 2 / 3 + averageShock / 3);
        int crisisSeverity = clamp(Math.max(maximumThreats[row], averageDamage));
        int ambition = clamp(constitution.militarization() * 40 / 100
                + constitution.legitimacy() * 25 / 100
                + average(specializationSums[row], settlements) * 20 / 100
                + Math.min(1000, realms.capturedSettlementCount(realmId) * 100) * 15 / 100);
        return new RealmHistoricalInputs(
                settlements,
                population,
                populationReadiness,
                foodCoverage,
                fiscalCapacity,
                militaryPower,
                average(stabilitySums[row], settlements),
                average(capitalSums[row], settlements),
                average(marketSums[row], settlements),
                constitution.legitimacy(),
                administrativeReserve,
                cohesion,
                warExhaustion,
                crisisSeverity,
                ambition,
                capitalExists(realmId));
    }

    private RealmIndicators indicators(int row, Constitution constitution) {
        int settlements = settlementCounts[row];
        long population = populations[row];
        int urbanization = ratio(urbanPopulations[row], population);
        int market = average(marketSums[row], settlements);
        int trade = clamp((market + average(specializationSums[row], settlements)) / 2);
        int bureaucracy = average(bureaucracySums[row], settlements);
        int averageDamage = average(damageSums[row], settlements);
        int averageShock = average(shockSums[row], settlements);
        int prosperity = average(prosperitySums[row], settlements);
        int largestShare = ratio(largestPopulations[row], population);
        int landInequality = clamp(largestShare * 7 / 10 + constitution.landConcentration() * 3 / 10);
        int externalThreat = clamp(maximumThreats[row] + averageDamage / 3);
        int warExhaustion = clamp(averageDamage * 2 / 3 + averageShock / 3);
        int eliteCompetition = clamp(
                (constitution.noblePower() + constitution.merchantPower()) / 2
                        - Math.abs(constitution.noblePower() - constitution.merchantPower()) / 4
                        + landInequality / 4);
        int civicTradition = clamp(constitution.citizenPower() * 2 / 3 + urbanization / 3);
        int rulerAuthority = clamp(constitution.legitimacy() / 2 + constitution.centralization() / 2);
        int cohesion = ratio(dominantCulturePopulations[row], population);
        if (population == 0L) cohesion = 0;
        return new RealmIndicators(
                settlements,
                population,
                urbanization,
                market,
                trade,
                landInequality,
                bureaucracy,
                externalThreat,
                warExhaustion,
                eliteCompetition,
                civicTradition,
                rulerAuthority,
                cohesion,
                constitution.legitimacy(),
                prosperity);
    }

    private void applyPhaseLegitimacy(
            long realmId,
            Constitution current,
            RealmHistoricalPhase phase) {
        int delta = switch (phase) {
            case ASCENDANT -> 25;
            case STABLE -> 10;
            case STRAINED -> -20;
            case DECADENT -> -60;
            case COLLAPSING -> -120;
            case RESTORING -> 35;
        };
        int nextLegitimacy = clamp(current.legitimacy() + delta);
        if (nextLegitimacy == current.legitimacy()) return;
        Constitution next = current.withLegitimacy(nextLegitimacy);
        institutions.update(
                realmId,
                next,
                institutions.stableMilliYears(realmId),
                snapshotMilliYear);
        registry.setLegitimacy(realmId, nextLegitimacy);
    }

    private boolean capitalExists(long realmId) {
        long capitalSubject = registry.capitalMemberId(realmId);
        if (!realms.keys().valid(capitalSubject)) return false;
        UUID capital = realms.keys().uuid(capitalSubject);
        long simulationSettlement = simulation.keys().findSettlement(capital);
        if (simulationSettlement == 0L) return false;
        int row = simulation.state().find(simulationSettlement);
        return row >= 0
                && simulation.state().realmIdAt(row) == realmId
                && simulation.state().statusAt(row) != SettlementStatus.RUINED
                && simulation.state().physicallyPresentAt(row);
    }

    private void updateCultureCandidate(int realmRow, int cultureKey, long population) {
        if (population <= 0L || cultureKey <= 0) return;
        if (cultureBalances[realmRow] == 0L) {
            cultureCandidates[realmRow] = cultureKey;
            cultureBalances[realmRow] = population;
        } else if (cultureCandidates[realmRow] == cultureKey) {
            cultureBalances[realmRow] = saturatedAdd(cultureBalances[realmRow], population);
        } else if (cultureBalances[realmRow] > population) {
            cultureBalances[realmRow] -= population;
        } else {
            cultureCandidates[realmRow] = cultureKey;
            cultureBalances[realmRow] = population - cultureBalances[realmRow];
        }
    }

    private void putRealmRow(long realmId, int row) {
        int slot = hashLong(realmId) & realmMapMask;
        while (realmMapEpochs[slot] == realmMapEpoch) slot = (slot + 1) & realmMapMask;
        realmMapEpochs[slot] = realmMapEpoch;
        realmMapKeys[slot] = realmId;
        realmMapRows[slot] = row;
    }

    private int findRealmRow(long realmId) {
        if (realmId == RealmRegistry.NO_REALM) return -1;
        int slot = hashLong(realmId) & realmMapMask;
        while (realmMapEpochs[slot] == realmMapEpoch) {
            if (realmMapKeys[slot] == realmId) return realmMapRows[slot];
            slot = (slot + 1) & realmMapMask;
        }
        return -1;
    }

    private void beginRealmMapEpoch() {
        if (realmMapEpoch == Integer.MAX_VALUE) {
            Arrays.fill(realmMapEpochs, 0);
            realmMapEpoch = 1;
        } else {
            realmMapEpoch++;
        }
    }

    private void clearAggregates(int count) {
        Arrays.fill(settlementCounts, 0, count, 0);
        Arrays.fill(populations, 0, count, 0L);
        Arrays.fill(urbanPopulations, 0, count, 0L);
        Arrays.fill(largestPopulations, 0, count, 0L);
        Arrays.fill(marketSums, 0, count, 0L);
        Arrays.fill(specializationSums, 0, count, 0L);
        Arrays.fill(bureaucracySums, 0, count, 0L);
        Arrays.fill(securitySums, 0, count, 0L);
        Arrays.fill(stabilitySums, 0, count, 0L);
        Arrays.fill(capitalSums, 0, count, 0L);
        Arrays.fill(prosperitySums, 0, count, 0L);
        Arrays.fill(foodStocks, 0, count, 0L);
        Arrays.fill(foodFlows, 0, count, 0L);
        Arrays.fill(armsStocks, 0, count, 0L);
        Arrays.fill(damageSums, 0, count, 0L);
        Arrays.fill(shockSums, 0, count, 0L);
        Arrays.fill(maximumThreats, 0, count, 0);
        Arrays.fill(cultureCandidates, 0, count, 0);
        Arrays.fill(cultureBalances, 0, count, 0L);
        Arrays.fill(dominantCulturePopulations, 0, count, 0L);
    }

    public long snapshotCount() { return snapshotCount; }
    public long evaluationCount() { return evaluationCount; }
    public long phaseChangeCount() { return phaseChangeCount; }
    public long scaleChangeCount() { return scaleChangeCount; }
    public long expansionReadyCount() { return expansionReadyCount; }
    public long terminalCollapseCount() { return terminalCollapseCount; }
    public int lastTickWorkUnits() { return lastTickWorkUnits; }
    public long snapshotGameTime() { return snapshotGameTime; }
    public long snapshotMilliYear() { return snapshotMilliYear; }

    public void logShutdownMetrics() {
        LOGGER.info(
                "[BANNEROK_REALM_HISTORY_METRICS] snapshots={} evaluated={} phase_changes={} scale_changes={} expansion_ready={} terminal_collapses={} historical_milli_year={} pending={} last_tick_work={}",
                snapshotCount,
                evaluationCount,
                phaseChangeCount,
                scaleChangeCount,
                expansionReadyCount,
                terminalCollapseCount,
                snapshotMilliYear,
                Math.max(0, snapshotRealmCount - evaluationCursor),
                lastTickWorkUnits);
    }

    private long historicalMilliYear(long gameTime) {
        long years = gameTime / historicalYearTicks;
        long remainder = gameTime % historicalYearTicks;
        if (years > Long.MAX_VALUE / 1000L) return Long.MAX_VALUE;
        return years * 1000L + remainder * 1000L / historicalYearTicks;
    }

    private static int average(long sum, int count) {
        return count <= 0 ? 0 : clamp((int) Math.min(Integer.MAX_VALUE, sum / count));
    }

    private static int ratio(long numerator, long denominator) {
        if (numerator <= 0L || denominator <= 0L) return 0;
        if (numerator >= denominator) return 1000;
        long whole = numerator / denominator;
        long remainder = numerator % denominator;
        return (int) Math.min(1000L, whole * 1000L + remainder * 1000L / denominator);
    }

    private static long absSaturated(long value) {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(1000, value));
    }

    private static int saturatedInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static long saturatedAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static long saturatedAddSigned(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0L && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }

    private static int powerOfTwoAtLeast(long required) {
        if (required > 1L << 30) throw new IllegalArgumentException("Historical map too large");
        int value = 1;
        while (value < required) value <<= 1;
        return value;
    }

    private static int hashLong(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53l;
        value ^= value >>> 33;
        return (int) value;
    }
}
