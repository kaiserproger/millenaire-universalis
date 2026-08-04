package ru.kaiserroman.millenairearmies.server.realm;

import com.mojang.logging.LogUtils;
import java.util.Arrays;
import org.slf4j.Logger;
import ru.kaiserroman.millenaire.realm.AdministrativeAssessment;
import ru.kaiserroman.millenaire.realm.Constitution;
import ru.kaiserroman.millenaire.realm.EvolutionDecision;
import ru.kaiserroman.millenaire.realm.GovernmentEvolution;
import ru.kaiserroman.millenaire.realm.GovernmentForm;
import ru.kaiserroman.millenaire.realm.RealmAdministrationPolicy;
import ru.kaiserroman.millenaire.realm.RealmIndicators;
import ru.kaiserroman.millenaire.realm.RealmInstitutionLedger;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenaire.simulation.PackedSettlementSimulationState;
import ru.kaiserroman.millenaire.simulation.SettlementStatus;
import ru.kaiserroman.millenaire.simulation.SettlementTier;
import ru.kaiserroman.millenaire.simulation.SimulationShockLedger;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;

/**
 * Bounded server-thread constitutional evolution driven by the persisted Simulation projection.
 * A snapshot aggregates all settlement rows once; Realm decisions are then striped across ticks.
 */
public final class RealmEvolutionService {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final RealmSavedData realms;
    private final SimulationSavedData simulation;
    private final RealmRegistry registry;
    private final RealmInstitutionLedger institutions;
    private final GovernmentEvolution evolution = new GovernmentEvolution();
    private final RealmAdministrationPolicy administration = new RealmAdministrationPolicy();
    private final int intervalCycles;
    private final int realmsPerTick;
    private final int reformStep;
    private final long simulationCycleTicks;
    private final long historicalYearTicks;

    private final long[] realmIds;
    private final int[] settlementCounts;
    private final long[] populations;
    private final long[] urbanPopulations;
    private final long[] largestSettlementPopulations;
    private final long[] marketSums;
    private final long[] tradeSums;
    private final long[] bureaucracySums;
    private final long[] damageSums;
    private final long[] prosperitySums;
    private final long[] shockSums;
    private final int[] maximumThreats;
    private final long[] dominantCulturePopulations;

    private final long[] realmMapKeys;
    private final int[] realmMapRows;
    private final int[] realmMapEpochs;
    private final int realmMapMask;
    private int realmMapEpoch;

    private final int[] cultureRealmRows;
    private final int[] cultureKeys;
    private final long[] culturePopulations;
    private final int[] cultureEpochs;
    private final int cultureMask;
    private int cultureEpoch;

    private int snapshotRealmCount;
    private int evaluationCursor;
    private long snapshotCycle = -1L;
    private long snapshotMilliYear = -1L;
    private long nextSnapshotCycle;
    private long snapshotCount;
    private long evaluatedRealmCount;
    private long governmentChangeCount;
    private long initialisedInstitutionCount;
    private long overextendedEvaluationCount;
    private long secessionRiskEvaluationCount;
    private long legitimacyAdjustmentCount;
    private long grossTaxRevenue;
    private long netTaxRevenue;
    private long tributeTransferred;
    private int lastTickWorkUnits;

    public RealmEvolutionService(
            RealmSavedData realms,
            SimulationSavedData simulation,
            int maximumRealms,
            int maximumSettlements,
            int intervalCycles,
            int realmsPerTick,
            int reformStep,
            long simulationCycleTicks,
            long historicalYearTicks) {
        if (realms == null || simulation == null) {
            throw new NullPointerException("Realm evolution stores");
        }
        if (maximumRealms <= 0 || maximumSettlements <= 0 || intervalCycles <= 0
                || realmsPerTick <= 0 || reformStep <= 0 || reformStep > 1000
                || simulationCycleTicks <= 0L || historicalYearTicks <= 0L) {
            throw new IllegalArgumentException("Invalid Realm evolution bounds");
        }
        this.realms = realms;
        this.simulation = simulation;
        registry = realms.registry();
        institutions = realms.institutions();
        this.intervalCycles = intervalCycles;
        this.realmsPerTick = realmsPerTick;
        this.reformStep = reformStep;
        this.simulationCycleTicks = simulationCycleTicks;
        this.historicalYearTicks = historicalYearTicks;

        realmIds = new long[maximumRealms];
        settlementCounts = new int[maximumRealms];
        populations = new long[maximumRealms];
        urbanPopulations = new long[maximumRealms];
        largestSettlementPopulations = new long[maximumRealms];
        marketSums = new long[maximumRealms];
        tradeSums = new long[maximumRealms];
        bureaucracySums = new long[maximumRealms];
        damageSums = new long[maximumRealms];
        prosperitySums = new long[maximumRealms];
        shockSums = new long[maximumRealms];
        maximumThreats = new int[maximumRealms];
        dominantCulturePopulations = new long[maximumRealms];

        int realmMapCapacity = powerOfTwoAtLeast(Math.max(16, maximumRealms * 2L));
        realmMapKeys = new long[realmMapCapacity];
        realmMapRows = new int[realmMapCapacity];
        realmMapEpochs = new int[realmMapCapacity];
        realmMapMask = realmMapCapacity - 1;

        int cultureCapacity = powerOfTwoAtLeast(Math.max(16, maximumSettlements * 2L));
        cultureRealmRows = new int[cultureCapacity];
        cultureKeys = new int[cultureCapacity];
        culturePopulations = new long[cultureCapacity];
        cultureEpochs = new int[cultureCapacity];
        cultureMask = cultureCapacity - 1;
    }

    public void tick(long gameTime) {
        if (gameTime < 0L) throw new IllegalArgumentException("Negative gameTime");
        lastTickWorkUnits = 0;
        long currentCycle = gameTime / simulationCycleTicks;
        long currentMilliYear = historicalMilliYear(gameTime);
        if (evaluationCursor >= snapshotRealmCount && currentCycle >= nextSnapshotCycle) {
            buildSnapshot(currentCycle, currentMilliYear);
            nextSnapshotCycle = saturatedAdd(currentCycle, intervalCycles);
        }
        if (evaluationCursor >= snapshotRealmCount) return;

        boolean changed = false;
        int budget = Math.min(realmsPerTick, snapshotRealmCount - evaluationCursor);
        for (int processed = 0; processed < budget; processed++) {
            changed |= evaluateRealm(evaluationCursor++, snapshotCycle, snapshotMilliYear);
            lastTickWorkUnits++;
        }
        if (changed) realms.markChanged();
    }

    private void buildSnapshot(long cycle, long milliYear) {
        beginRealmMapEpoch();
        beginCultureEpoch();
        snapshotRealmCount = 0;
        evaluationCursor = 0;
        registry.visitRealms((realmId, capitalMemberId, foundedCycle, government, legitimacy) -> {
            if (snapshotRealmCount == realmIds.length) {
                throw new IllegalStateException("Canonical Realm registry exceeds evolution capacity");
            }
            int row = snapshotRealmCount++;
            realmIds[row] = realmId;
            putRealmRow(realmId, row);
        });
        clearAggregates(snapshotRealmCount);

        PackedSettlementSimulationState state = simulation.state();
        SimulationShockLedger shocks = simulation.shocks();
        for (int settlement = 0; settlement < state.size(); settlement++) {
            if (state.statusAt(settlement) == SettlementStatus.RUINED) continue;
            long realmId = state.realmIdAt(settlement);
            int row = findRealmRow(realmId);
            if (row < 0) continue;
            long population = Math.max(0L, state.populationAt(settlement));
            settlementCounts[row]++;
            populations[row] = saturatedAdd(populations[row], population);
            largestSettlementPopulations[row] = Math.max(
                    largestSettlementPopulations[row], population);
            if (state.tierAt(settlement).ordinal() >= SettlementTier.TOWN.ordinal()) {
                urbanPopulations[row] = saturatedAdd(urbanPopulations[row], population);
            }
            marketSums[row] = saturatedAdd(marketSums[row], state.marketAccessAt(settlement));
            tradeSums[row] = saturatedAdd(
                    tradeSums[row],
                    (state.marketAccessAt(settlement) + state.specializationAt(settlement)) / 2L);
            bureaucracySums[row] = saturatedAdd(
                    bureaucracySums[row],
                    (state.educationAt(settlement) + state.productiveCapitalAt(settlement)) / 2L);
            damageSums[row] = saturatedAdd(damageSums[row], state.damageAt(settlement));
            prosperitySums[row] = saturatedAdd(
                    prosperitySums[row],
                    (state.productivityAt(settlement)
                                    + state.stabilityAt(settlement)
                                    + state.attractivenessAt(settlement)
                                    + state.productiveCapitalAt(settlement))
                            / 4L);
            addCulturePopulation(row, state.cultureKeyAt(settlement), population);
            for (int shock = 0; shock < shocks.size(); shock++) {
                if (!shocks.matchesAt(
                        shock,
                        state.settlementIdAt(settlement),
                        state.regionKeyAt(settlement),
                        state.cultureKeyAt(settlement),
                        cycle)) {
                    continue;
                }
                int magnitude = shocks.magnitudeAt(shock);
                shockSums[row] = saturatedAdd(shockSums[row], magnitude);
                maximumThreats[row] = Math.max(maximumThreats[row], magnitude);
            }
        }
        snapshotCycle = cycle;
        snapshotMilliYear = milliYear;
        snapshotCount++;
    }

    private boolean evaluateRealm(int row, long cycle, long milliYear) {
        long realmId = realmIds[row];
        GovernmentForm registryGovernment = registry.government(realmId);
        if (registryGovernment == null) return false;
        Constitution current = institutions.constitution(realmId);
        if (current == null) {
            current = Constitution.archetype(registryGovernment, registry.legitimacy(realmId));
            if (institutions.ensureRealm(realmId, current, milliYear) < 0) {
                throw new IllegalStateException("Realm institution capacity exhausted");
            }
            initialisedInstitutionCount++;
            return true;
        }

        long previousEvaluation = institutions.lastEvaluationMilliYear(realmId);
        int elapsedMilliYears = previousEvaluation < 0L
                ? Math.max(1, historicalDeltaForCycles(intervalCycles))
                : saturatedInt(Math.max(0L, milliYear - previousEvaluation));
        int elapsedCycles = previousEvaluation < 0L
                ? intervalCycles
                : historicalMilliYearsToCycles(elapsedMilliYears);
        if (settlementCounts[row] == 0) {
            institutions.update(
                    realmId,
                    current,
                    institutions.stableMilliYears(realmId),
                    milliYear);
            return true;
        }

        RealmIndicators indicators = indicators(row, current);
        AdministrativeAssessment assessment = administration.evaluate(current, indicators);
        if (assessment.overextended()) overextendedEvaluationCount++;
        if (assessment.secessionRisk()) secessionRiskEvaluationCount++;
        int stableMilliYears = saturatedAddInt(
                institutions.stableMilliYears(realmId), elapsedMilliYears);
        EvolutionDecision decision = evolution.evaluate(current, indicators, stableMilliYears);
        Constitution next;
        int nextStableMilliYears;
        if (decision.changesGovernment()) {
            next = evolution.apply(current, decision, reformStep);
            nextStableMilliYears = 0;
            governmentChangeCount++;
            LOGGER.info(
                    "[BANNEROK_REALM_EVOLUTION] realm={} from={} to={} pressure={} threshold={} reasons={} settlements={} population={} legitimacy={} year_milli={} cycle={}",
                    realmId,
                    current.government(),
                    next.government(),
                    decision.pressure(),
                    decision.requiredPressure(),
                    decision.reasonMask(),
                    indicators.settlementCount(),
                    indicators.population(),
                    next.legitimacy(),
                    milliYear,
                    cycle);
        } else {
            int legitimacyTarget = legitimacyTarget(indicators);
            int legitimacyStep = Math.max(5, reformStep / 4);
            next = current
                    .towards(current.government(), Math.max(1, reformStep / 8))
                    .withLegitimacy(move(current.legitimacy(), legitimacyTarget, legitimacyStep));
            nextStableMilliYears = stableMilliYears;
        }
        int legitimacyBeforeAdministration = next.legitimacy();
        next = assessment.applyLegitimacy(next);
        if (next.legitimacy() != legitimacyBeforeAdministration) {
            legitimacyAdjustmentCount++;
        }
        if (assessment.overextended() || assessment.secessionRisk()) {
            LOGGER.info(
                    "[BANNEROK_REALM_ADMINISTRATION] realm={} capacity={} load={} coverage={} corruption={} tax_efficiency={} separatism={} legitimacy_delta={} reasons={} year_milli={} cycle={}",
                    realmId,
                    assessment.capacity(),
                    assessment.load(),
                    assessment.coverage(),
                    assessment.corruption(),
                    assessment.taxEfficiency(),
                    assessment.separatismPressure(),
                    assessment.legitimacyDelta(),
                    assessment.reasonMask(),
                    milliYear,
                    cycle);
        }
        institutions.update(realmId, next, nextStableMilliYears, milliYear);
        registry.setGovernment(realmId, next.government());
        registry.setLegitimacy(realmId, next.legitimacy());
        collectRevenue(realmId, row, assessment, elapsedCycles);
        evaluatedRealmCount++;
        return true;
    }

    private void collectRevenue(
            long realmId,
            int row,
            AdministrativeAssessment assessment,
            int elapsedCycles) {
        if (realms.isLegacy(realmId) || realms.name(realmId) == null) return;
        int taxRate = realms.taxRate(realmId);
        if (taxRate <= 0 || populations[row] <= 0L || elapsedCycles <= 0) return;
        int prosperity = average(prosperitySums[row], settlementCounts[row]);
        long taxableOutput = scale(populations[row], prosperity, 1000);
        long nominalTax = scale(taxableOutput, taxRate, 100);
        nominalTax = saturatedMultiply(nominalTax, elapsedCycles);
        long collected = assessment.collectibleRevenue(nominalTax);
        if (collected <= 0L) return;

        long overlord = realms.dependencies().overlordOf(realmId);
        long tribute = 0L;
        if (overlord != RealmRegistry.NO_REALM
                && registry.exists(overlord)
                && !realms.isLegacy(overlord)
                && realms.name(overlord) != null) {
            tribute = Math.min(collected, realms.dependencies().tributeDue(realmId, collected));
        }
        long retained = collected - tribute;
        if (!realms.adjustTreasury(realmId, retained)) return;
        if (tribute != 0L && !realms.adjustTreasury(overlord, tribute)) {
            realms.adjustTreasury(realmId, tribute);
            tribute = 0L;
        }
        grossTaxRevenue = saturatedAdd(grossTaxRevenue, nominalTax);
        netTaxRevenue = saturatedAdd(netTaxRevenue, collected);
        tributeTransferred = saturatedAdd(tributeTransferred, tribute);
        if (tribute != 0L || assessment.taxEfficiency() < 600) {
            LOGGER.info(
                    "[BANNEROK_REALM_FISCAL] realm={} nominal={} efficiency={} collected={} retained={} overlord={} tribute={} elapsed_cycles={}",
                    realmId,
                    nominalTax,
                    assessment.taxEfficiency(),
                    collected,
                    collected - tribute,
                    overlord,
                    tribute,
                    elapsedCycles);
        }
    }

    private RealmIndicators indicators(int row, Constitution current) {
        int settlements = settlementCounts[row];
        long population = populations[row];
        int urbanization = ratio(urbanPopulations[row], population);
        int marketIntegration = average(marketSums[row], settlements);
        int tradeDependence = average(tradeSums[row], settlements);
        int bureaucracyCapacity = average(bureaucracySums[row], settlements);
        int averageDamage = average(damageSums[row], settlements);
        int averageShock = average(shockSums[row], settlements);
        int prosperity = average(prosperitySums[row], settlements);
        int largestShare = ratio(largestSettlementPopulations[row], population);
        int landInequality = clamp(
                largestShare * 7 / 10 + current.landConcentration() * 3 / 10);
        int externalThreat = clamp(maximumThreats[row] + averageDamage / 3);
        int warExhaustion = clamp(averageDamage * 2 / 3 + averageShock / 3);
        int eliteCompetition = clamp(
                (current.noblePower() + current.merchantPower()) / 2
                        - Math.abs(current.noblePower() - current.merchantPower()) / 4
                        + landInequality / 4);
        int civicTradition = clamp(current.citizenPower() * 2 / 3 + urbanization / 3);
        int rulerAuthority = clamp(current.legitimacy() / 2 + current.centralization() / 2);
        int culturalCohesion = ratio(dominantCulturePopulations[row], population);
        if (population == 0L) culturalCohesion = 500;
        return new RealmIndicators(
                settlements,
                population,
                urbanization,
                marketIntegration,
                tradeDependence,
                landInequality,
                bureaucracyCapacity,
                externalThreat,
                warExhaustion,
                eliteCompetition,
                civicTradition,
                rulerAuthority,
                culturalCohesion,
                current.legitimacy(),
                prosperity);
    }

    private static int legitimacyTarget(RealmIndicators indicators) {
        long value = indicators.prosperity() * 30L
                + indicators.culturalCohesion() * 25L
                + (1000L - indicators.warExhaustion()) * 25L
                + indicators.rulerAuthority() * 20L;
        return clamp((int) (value / 100L));
    }

    private void addCulturePopulation(int realmRow, int cultureKey, long population) {
        int slot = hashPair(realmRow, cultureKey) & cultureMask;
        while (cultureEpochs[slot] == cultureEpoch) {
            if (cultureRealmRows[slot] == realmRow && cultureKeys[slot] == cultureKey) {
                culturePopulations[slot] = saturatedAdd(culturePopulations[slot], population);
                dominantCulturePopulations[realmRow] = Math.max(
                        dominantCulturePopulations[realmRow], culturePopulations[slot]);
                return;
            }
            slot = (slot + 1) & cultureMask;
        }
        cultureEpochs[slot] = cultureEpoch;
        cultureRealmRows[slot] = realmRow;
        cultureKeys[slot] = cultureKey;
        culturePopulations[slot] = population;
        dominantCulturePopulations[realmRow] = Math.max(
                dominantCulturePopulations[realmRow], population);
    }

    private void putRealmRow(long realmId, int row) {
        int slot = hashLong(realmId) & realmMapMask;
        while (realmMapEpochs[slot] == realmMapEpoch) {
            if (realmMapKeys[slot] == realmId) {
                throw new IllegalStateException("Duplicate Realm id in canonical registry");
            }
            slot = (slot + 1) & realmMapMask;
        }
        realmMapEpochs[slot] = realmMapEpoch;
        realmMapKeys[slot] = realmId;
        realmMapRows[slot] = row;
    }

    private int findRealmRow(long realmId) {
        if (realmId <= 0L) return -1;
        int slot = hashLong(realmId) & realmMapMask;
        while (realmMapEpochs[slot] == realmMapEpoch) {
            if (realmMapKeys[slot] == realmId) return realmMapRows[slot];
            slot = (slot + 1) & realmMapMask;
        }
        return -1;
    }

    private void clearAggregates(int count) {
        Arrays.fill(settlementCounts, 0, count, 0);
        Arrays.fill(populations, 0, count, 0L);
        Arrays.fill(urbanPopulations, 0, count, 0L);
        Arrays.fill(largestSettlementPopulations, 0, count, 0L);
        Arrays.fill(marketSums, 0, count, 0L);
        Arrays.fill(tradeSums, 0, count, 0L);
        Arrays.fill(bureaucracySums, 0, count, 0L);
        Arrays.fill(damageSums, 0, count, 0L);
        Arrays.fill(prosperitySums, 0, count, 0L);
        Arrays.fill(shockSums, 0, count, 0L);
        Arrays.fill(maximumThreats, 0, count, 0);
        Arrays.fill(dominantCulturePopulations, 0, count, 0L);
    }

    private void beginRealmMapEpoch() {
        realmMapEpoch++;
        if (realmMapEpoch == 0) {
            Arrays.fill(realmMapEpochs, 0);
            realmMapEpoch = 1;
        }
    }

    private void beginCultureEpoch() {
        cultureEpoch++;
        if (cultureEpoch == 0) {
            Arrays.fill(cultureEpochs, 0);
            cultureEpoch = 1;
        }
    }

    public long snapshotCount() { return snapshotCount; }
    public long evaluatedRealmCount() { return evaluatedRealmCount; }
    public long governmentChangeCount() { return governmentChangeCount; }
    public long initialisedInstitutionCount() { return initialisedInstitutionCount; }
    public long overextendedEvaluationCount() { return overextendedEvaluationCount; }
    public long secessionRiskEvaluationCount() { return secessionRiskEvaluationCount; }
    public long legitimacyAdjustmentCount() { return legitimacyAdjustmentCount; }
    public long grossTaxRevenue() { return grossTaxRevenue; }
    public long netTaxRevenue() { return netTaxRevenue; }
    public long tributeTransferred() { return tributeTransferred; }
    public int lastTickWorkUnits() { return lastTickWorkUnits; }
    public boolean hasPendingEvaluation() { return evaluationCursor < snapshotRealmCount; }

    public void logShutdownMetrics() {
        LOGGER.info(
                "[BANNEROK_REALM_EVOLUTION_METRICS] snapshots={} evaluated={} government_changes={} institution_initialisations={} overextended={} secession_risk={} legitimacy_adjustments={} gross_tax={} net_tax={} tribute={} pending={} last_tick_work={}",
                snapshotCount,
                evaluatedRealmCount,
                governmentChangeCount,
                initialisedInstitutionCount,
                overextendedEvaluationCount,
                secessionRiskEvaluationCount,
                legitimacyAdjustmentCount,
                grossTaxRevenue,
                netTaxRevenue,
                tributeTransferred,
                snapshotRealmCount - evaluationCursor,
                lastTickWorkUnits);
    }

    private long historicalMilliYear(long gameTime) {
        long years = gameTime / historicalYearTicks;
        long remainder = gameTime % historicalYearTicks;
        if (years > Long.MAX_VALUE / 1000L) return Long.MAX_VALUE;
        return years * 1000L + remainder * 1000L / historicalYearTicks;
    }

    private int historicalDeltaForCycles(int cycles) {
        if (cycles <= 0) return 0;
        long ticks = saturatedMultiply(simulationCycleTicks, cycles);
        long wholeYears = ticks / historicalYearTicks;
        long remainder = ticks % historicalYearTicks;
        long milliYears = wholeYears > Long.MAX_VALUE / 1000L
                ? Long.MAX_VALUE
                : wholeYears * 1000L + remainder * 1000L / historicalYearTicks;
        return saturatedInt(milliYears);
    }

    private int historicalMilliYearsToCycles(int milliYears) {
        if (milliYears <= 0) return 0;
        long wholeYears = milliYears / 1000L;
        long remainder = milliYears % 1000L;
        long ticks = wholeYears > Long.MAX_VALUE / historicalYearTicks
                ? Long.MAX_VALUE
                : wholeYears * historicalYearTicks;
        long tail = remainder * historicalYearTicks / 1000L;
        ticks = ticks > Long.MAX_VALUE - tail ? Long.MAX_VALUE : ticks + tail;
        return Math.max(1, saturatedInt(ticks / simulationCycleTicks));
    }

    private static int average(long sum, int count) {
        return count <= 0 ? 0 : clamp((int) Math.min(Integer.MAX_VALUE, sum / count));
    }

    private static long scale(long value, int numerator, int denominator) {
        if (value <= 0L || numerator <= 0 || denominator <= 0) return 0L;
        long whole = value / denominator;
        long remainder = value % denominator;
        if (whole > Long.MAX_VALUE / numerator) return Long.MAX_VALUE;
        long result = whole * numerator;
        long tail = remainder * numerator / denominator;
        return result > Long.MAX_VALUE - tail ? Long.MAX_VALUE : result + tail;
    }

    private static long saturatedMultiply(long value, int multiplier) {
        if (value <= 0L || multiplier <= 0) return 0L;
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }

    private static int ratio(long part, long total) {
        if (total <= 0L || part <= 0L) return 0;
        if (part >= total) return 1000;
        return (int) Math.min(1000L, part * 1000L / total);
    }

    private static int move(int value, int target, int step) {
        if (value < target) return Math.min(target, value + step);
        if (value > target) return Math.max(target, value - step);
        return value;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(1000, value));
    }

    private static long saturatedAdd(long left, long right) {
        if (right <= 0L) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long saturatedAdd(long left, int right) {
        return saturatedAdd(left, (long) right);
    }

    private static int saturatedAddInt(int left, int right) {
        return right > Integer.MAX_VALUE - left ? Integer.MAX_VALUE : left + right;
    }

    private static int saturatedInt(long value) {
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, value);
    }

    private static int powerOfTwoAtLeast(long requested) {
        int capacity = 1;
        while (capacity < requested) {
            if (capacity >= 1 << 29) {
                throw new IllegalArgumentException("Evolution table capacity is too large");
            }
            capacity <<= 1;
        }
        return capacity;
    }

    private static int hashLong(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return (int) value;
    }

    private static int hashPair(int left, int right) {
        long value = ((long) left << 32) ^ Integer.toUnsignedLong(right);
        return hashLong(value);
    }
}
