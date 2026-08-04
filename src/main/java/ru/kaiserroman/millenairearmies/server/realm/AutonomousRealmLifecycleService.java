package ru.kaiserroman.millenairearmies.server.realm;

import com.mojang.logging.LogUtils;
import java.util.Arrays;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import ru.kaiserroman.millenaire.realm.Constitution;
import ru.kaiserroman.millenaire.realm.GovernmentForm;
import ru.kaiserroman.millenaire.realm.RealmHistoricalInputs;
import ru.kaiserroman.millenaire.realm.RealmHistoricalPhase;
import ru.kaiserroman.millenaire.realm.RealmHistoricalPolicy;
import ru.kaiserroman.millenaire.realm.RealmInstitutionLedger;
import ru.kaiserroman.millenaire.realm.RealmLifecycleLedger;
import ru.kaiserroman.millenaire.realm.RealmMemberKind;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenaire.realm.RealmStatePriority;
import ru.kaiserroman.millenaire.realm.RealmSecessionDecision;
import ru.kaiserroman.millenaire.realm.RealmSecessionInputs;
import ru.kaiserroman.millenaire.realm.RealmSecessionPolicy;
import ru.kaiserroman.millenaire.simulation.PackedSettlementSimulationState;
import ru.kaiserroman.millenaire.simulation.SettlementStatus;
import ru.kaiserroman.millenaire.simulation.SettlementTier;
import ru.kaiserroman.millenaire.simulation.SimulationShockLedger;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.SimulationKeyTable;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;

/**
 * Forms culture/region NPC Realms and dissolves terminal NPC-only states. Player and mixed Realms
 * may accumulate political pressure elsewhere, but this service never deletes them automatically.
 */
public final class AutonomousRealmLifecycleService {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final RealmSavedData realms;
    private final SimulationSavedData simulation;
    private final RealmRegistry registry;
    private final RealmInstitutionLedger institutions;
    private final RealmLifecycleLedger lifecycle;
    private final SimulationKeyTable simulationKeys;
    private final RealmHistoricalPolicy historicalPolicy = new RealmHistoricalPolicy();
    private final RealmSecessionPolicy secessionPolicy = new RealmSecessionPolicy();
    private final int intervalCycles;
    private final int maximumTransitions;
    private final int minimumCityPopulation;
    private final int cityStateFormationMilliYears;
    private final int regionalFormationMilliYears;
    private final int collapseDissolutionMilliYears;
    private final int capitalLossDissolutionMilliYears;
    private final boolean secessionEnabled;
    private final int secessionMinimumPhaseMilliYears;
    private final int secessionCooldownMilliYears;
    private final long simulationCycleTicks;
    private final long historicalYearTicks;

    private final long[] clusterRegions;
    private final int[] clusterCultures;
    private final int[] clusterSettlementCounts;
    private final long[] clusterPopulations;
    private final long[] clusterMarketSums;
    private final long[] clusterSecuritySums;
    private final long[] clusterStabilitySums;
    private final long[] clusterCapitalSums;
    private final long[] clusterProsperitySums;
    private final long[] clusterFoodStocks;
    private final long[] clusterFoodFlows;
    private final long[] clusterArmsStocks;
    private final int[] clusterLeaderAuthorities;
    private final long[] clusterBestSettlementIds;
    private final long[] clusterBestScores;
    private final int[] clusterMapEpochs;
    private final int[] clusterMapRows;
    private final int clusterMapMask;
    private int clusterMapEpoch;
    private int clusterCount;

    private final long[] npcRealmIds;
    private final int[] npcSettlementCounts;
    private final long[] npcPopulations;
    private final long[] npcProsperitySums;
    private final long[] npcDamageSums;
    private final long[] npcShockSums;
    private final int[] npcCultureCandidates;
    private final long[] npcCultureBalances;
    private final long[] npcDominantPopulations;
    private final long[] npcRealmMapKeys;
    private final int[] npcRealmMapRows;
    private final int[] npcRealmMapEpochs;
    private final int npcRealmMapMask;
    private int npcRealmMapEpoch;
    private int npcRealmCount;

    private final int[] eligibleRows;
    private final long[] eligibleSubjects;

    private long lastEvaluationCycle = -1L;
    private long lastEvaluationMilliYear = -1L;
    private long nextEvaluationCycle;
    private long evaluationCount;
    private long formedRealmCount;
    private long peacefulExpansionCount;
    private long secessionCount;
    private long successorStateCount;
    private long statelessProvinceCount;
    private long dissolvedRealmCount;
    private long rejectedFormationCount;
    private int lastWorkUnits;

    public AutonomousRealmLifecycleService(
            RealmSavedData realms,
            SimulationSavedData simulation,
            int maximumRealms,
            int maximumSettlements,
            int intervalCycles,
            int maximumTransitions,
            int minimumCityPopulation,
            int cityStateFormationYears,
            int regionalFormationYears,
            int collapseDissolutionYears,
            int capitalLossDissolutionYears,
            boolean secessionEnabled,
            int secessionMinimumPhaseYears,
            int secessionCooldownYears,
            long simulationCycleTicks,
            long historicalYearTicks) {
        if (realms == null || simulation == null) {
            throw new NullPointerException("autonomous Realm stores");
        }
        if (maximumRealms <= 0 || maximumSettlements <= 0 || intervalCycles <= 0
                || maximumTransitions <= 0 || minimumCityPopulation <= 0
                || cityStateFormationYears <= 0 || regionalFormationYears <= 0
                || collapseDissolutionYears <= 0 || capitalLossDissolutionYears <= 0
                || secessionMinimumPhaseYears <= 0 || secessionCooldownYears <= 0
                || simulationCycleTicks <= 0L || historicalYearTicks <= 0L) {
            throw new IllegalArgumentException("Invalid autonomous Realm bounds");
        }
        this.realms = realms;
        this.simulation = simulation;
        registry = realms.registry();
        institutions = realms.institutions();
        lifecycle = realms.lifecycle();
        simulationKeys = simulation.keys();
        this.intervalCycles = intervalCycles;
        this.maximumTransitions = maximumTransitions;
        this.minimumCityPopulation = minimumCityPopulation;
        this.cityStateFormationMilliYears = yearsToMilliYears(cityStateFormationYears);
        this.regionalFormationMilliYears = yearsToMilliYears(regionalFormationYears);
        this.collapseDissolutionMilliYears = yearsToMilliYears(collapseDissolutionYears);
        this.capitalLossDissolutionMilliYears = yearsToMilliYears(capitalLossDissolutionYears);
        this.secessionEnabled = secessionEnabled;
        this.secessionMinimumPhaseMilliYears = yearsToMilliYears(secessionMinimumPhaseYears);
        this.secessionCooldownMilliYears = yearsToMilliYears(secessionCooldownYears);
        this.simulationCycleTicks = simulationCycleTicks;
        this.historicalYearTicks = historicalYearTicks;

        clusterRegions = new long[maximumSettlements];
        clusterCultures = new int[maximumSettlements];
        clusterSettlementCounts = new int[maximumSettlements];
        clusterPopulations = new long[maximumSettlements];
        clusterMarketSums = new long[maximumSettlements];
        clusterSecuritySums = new long[maximumSettlements];
        clusterStabilitySums = new long[maximumSettlements];
        clusterCapitalSums = new long[maximumSettlements];
        clusterProsperitySums = new long[maximumSettlements];
        clusterFoodStocks = new long[maximumSettlements];
        clusterFoodFlows = new long[maximumSettlements];
        clusterArmsStocks = new long[maximumSettlements];
        clusterLeaderAuthorities = new int[maximumSettlements];
        clusterBestSettlementIds = new long[maximumSettlements];
        clusterBestScores = new long[maximumSettlements];
        int clusterMapCapacity = powerOfTwoAtLeast(Math.max(16L, maximumSettlements * 2L));
        clusterMapEpochs = new int[clusterMapCapacity];
        clusterMapRows = new int[clusterMapCapacity];
        clusterMapMask = clusterMapCapacity - 1;

        npcRealmIds = new long[maximumRealms];
        npcSettlementCounts = new int[maximumRealms];
        npcPopulations = new long[maximumRealms];
        npcProsperitySums = new long[maximumRealms];
        npcDamageSums = new long[maximumRealms];
        npcShockSums = new long[maximumRealms];
        npcCultureCandidates = new int[maximumRealms];
        npcCultureBalances = new long[maximumRealms];
        npcDominantPopulations = new long[maximumRealms];
        int realmMapCapacity = powerOfTwoAtLeast(Math.max(16L, maximumRealms * 2L));
        npcRealmMapKeys = new long[realmMapCapacity];
        npcRealmMapRows = new int[realmMapCapacity];
        npcRealmMapEpochs = new int[realmMapCapacity];
        npcRealmMapMask = realmMapCapacity - 1;

        eligibleRows = new int[maximumSettlements];
        eligibleSubjects = new long[maximumSettlements];
    }

    public void tick(long gameTime) {
        if (gameTime < 0L) throw new IllegalArgumentException("Negative gameTime");
        long cycle = gameTime / simulationCycleTicks;
        long milliYear = historicalMilliYear(gameTime);
        lastWorkUnits = 0;
        if (cycle < nextEvaluationCycle) return;
        int minimumDelta = Math.max(
                1,
                saturatedInt(intervalCycles * simulationCycleTicks * 1000L / historicalYearTicks));
        int qualifyingDelta = lastEvaluationMilliYear < 0L
                ? minimumDelta
                : Math.max(1, saturatedInt(milliYear - lastEvaluationMilliYear));
        lastEvaluationCycle = cycle;
        lastEvaluationMilliYear = milliYear;
        nextEvaluationCycle = saturatedAdd(cycle, intervalCycles);
        evaluate(cycle, milliYear, qualifyingDelta);
        evaluationCount++;
    }

    private void evaluate(long cycle, long milliYear, int qualifyingDelta) {
        long lifecycleRevision = lifecycle.revision();
        buildFormationClusters();
        buildNpcRealmAggregates(cycle);
        int transitions = 0;

        for (int row = 0; row < clusterCount; row++) {
            RealmHistoricalInputs inputs = formationInputs(row);
            int pressure = historicalPolicy.formationPressure(inputs);
            int qualifying = lifecycle.recordFormation(
                    clusterRegions[row],
                    clusterCultures[row],
                    pressure,
                    650,
                    qualifyingDelta,
                    milliYear);
            if (qualifying < 0) {
                rejectedFormationCount++;
                continue;
            }
            boolean mayForm = clusterSettlementCounts[row] == 1
                    ? historicalPolicy.mayFormCityState(
                            inputs, qualifying, cityStateFormationMilliYears)
                    : pressure >= 650 && qualifying >= regionalFormationMilliYears;
            if (transitions < maximumTransitions && mayForm) {
                if (formRealm(row, pressure, cycle, milliYear)) {
                    transitions++;
                } else {
                    rejectedFormationCount++;
                }
            }
        }
        lifecycle.finishFormationSweep(milliYear);

        for (int row = 0; row < npcRealmCount && transitions < maximumTransitions; row++) {
            long realmId = npcRealmIds[row];
            RealmHistoricalPhase phase = realms.history().phase(realmId);
            RealmStatePriority priority = realms.statePriority(realmId);
            if (phase == null
                    || !phase.permitsExpansion()
                    || priority != RealmStatePriority.NONE
                            && priority != RealmStatePriority.EXPANSION
                    || realms.history().viability(realmId) < 600
                    || realms.history().expansionReadiness(realmId) < 700
                    || !realms.dependencies().mayConductIndependentDiplomacy(realmId)) {
                continue;
            }
            if (expandRealmPeacefully(row, realmId, cycle, milliYear)) transitions++;
        }

        if (secessionEnabled) {
            for (int row = 0; row < npcRealmCount && transitions < maximumTransitions; row++) {
                long realmId = npcRealmIds[row];
                RealmHistoricalPhase phase = realms.history().phase(realmId);
                if ((phase != RealmHistoricalPhase.DECADENT
                                && phase != RealmHistoricalPhase.COLLAPSING)
                        || npcSettlementCounts[row] < 2) {
                    continue;
                }
                long phaseSince = realms.history().phaseSinceMilliYear(realmId);
                long lastSecession = realms.history().lastSecessionMilliYear(realmId);
                if (phaseSince < 0L
                        || milliYear < phaseSince
                        || milliYear - phaseSince < secessionMinimumPhaseMilliYears
                        || lastSecession >= 0L
                                && (milliYear < lastSecession
                                        || milliYear - lastSecession < secessionCooldownMilliYears)) {
                    continue;
                }
                if (secedeProvince(row, realmId, phase, cycle, milliYear)) transitions++;
            }
        }

        for (int row = 0; row < npcRealmCount; row++) {
            long realmId = npcRealmIds[row];
            boolean capitalExists = capitalExists(realmId);
            boolean terminal = !capitalExists && npcPopulations[row] <= 0L;
            RealmHistoricalPhase phase = realms.history().phase(realmId);
            int viability = realms.history().viability(realmId);
            if (!terminal && (phase != RealmHistoricalPhase.COLLAPSING || viability >= 250)) {
                lifecycle.removeCrisis(realmId);
                continue;
            }
            int pressure = terminal
                    ? 1000
                    : Math.max(700, realms.history().crisisBurden(realmId));
            int qualifying = lifecycle.recordCrisis(
                    realmId,
                    pressure,
                    700,
                    qualifyingDelta,
                    milliYear);
            if (qualifying < 0) continue;
            int requiredMilliYears = capitalExists
                    ? collapseDissolutionMilliYears
                    : capitalLossDissolutionMilliYears;
            if (transitions < maximumTransitions
                    && (terminal || qualifying >= requiredMilliYears)
                    && dissolveRealm(realmId, pressure, cycle, milliYear)) {
                transitions++;
            }
        }
        lifecycle.finishCrisisSweep(milliYear);
        if (lifecycle.revision() != lifecycleRevision) realms.markChanged();
    }

    private void buildFormationClusters() {
        beginClusterEpoch();
        clusterCount = 0;
        PackedSettlementSimulationState state = simulation.state();
        for (int row = 0; row < state.size(); row++) {
            lastWorkUnits++;
            if (state.realmIdAt(row) != RealmRegistry.NO_REALM
                    || state.statusAt(row) != SettlementStatus.ACTIVE
                    || !state.physicallyPresentAt(row)
                    || state.populationAt(row) < minimumCityPopulation
                    || state.buildingCountAt(row) < 4
                    || state.productiveCapitalAt(row) < 300
                    || state.cultureKeyAt(row) <= 0) {
                continue;
            }
            int cluster = clusterRow(state.regionKeyAt(row), state.cultureKeyAt(row));
            long population = Math.max(0L, state.populationAt(row));
            clusterSettlementCounts[cluster]++;
            clusterPopulations[cluster] = saturatedAdd(clusterPopulations[cluster], population);
            clusterMarketSums[cluster] = saturatedAdd(
                    clusterMarketSums[cluster], state.marketAccessAt(row));
            clusterSecuritySums[cluster] = saturatedAdd(
                    clusterSecuritySums[cluster], state.securityAt(row));
            clusterStabilitySums[cluster] = saturatedAdd(
                    clusterStabilitySums[cluster], state.stabilityAt(row));
            clusterCapitalSums[cluster] = saturatedAdd(
                    clusterCapitalSums[cluster], state.productiveCapitalAt(row));
            clusterFoodStocks[cluster] = saturatedAdd(
                    clusterFoodStocks[cluster], state.stockAt(row, 0));
            clusterFoodFlows[cluster] = saturatedAddSigned(
                    clusterFoodFlows[cluster], state.netFlowAt(row, 0));
            if (state.commodityCount() > 6) {
                clusterArmsStocks[cluster] = saturatedAdd(
                        clusterArmsStocks[cluster], state.stockAt(row, 6));
            }
            int prosperity = (state.productivityAt(row)
                            + state.stabilityAt(row)
                            + state.attractivenessAt(row)
                            + state.productiveCapitalAt(row))
                    / 4;
            clusterProsperitySums[cluster] = saturatedAdd(clusterProsperitySums[cluster], prosperity);
            int authority = (state.stabilityAt(row)
                            + state.productivityAt(row)
                            + state.attractivenessAt(row))
                    / 3;
            clusterLeaderAuthorities[cluster] = Math.max(clusterLeaderAuthorities[cluster], authority);
            long score = saturatedAdd(population * 8L, authority * 4L + state.buildingCountAt(row) * 25L);
            if (score > clusterBestScores[cluster]) {
                clusterBestScores[cluster] = score;
                clusterBestSettlementIds[cluster] = state.settlementIdAt(row);
            }
        }
    }

    private void buildNpcRealmAggregates(long cycle) {
        beginNpcRealmEpoch();
        npcRealmCount = 0;
        registry.visitRealms((realmId, capitalMemberId, foundedCycle, government, legitimacy) -> {
            if (registry.hasPlayerMembers(realmId) || realms.isLegacy(realmId)) return;
            if (npcRealmCount == npcRealmIds.length) {
                throw new IllegalStateException("NPC Realm count exceeds lifecycle capacity");
            }
            int row = npcRealmCount++;
            npcRealmIds[row] = realmId;
            putNpcRealmRow(realmId, row);
        });
        clearNpcAggregates(npcRealmCount);

        PackedSettlementSimulationState state = simulation.state();
        SimulationShockLedger shocks = simulation.shocks();
        for (int settlement = 0; settlement < state.size(); settlement++) {
            lastWorkUnits++;
            int realmRow = findNpcRealmRow(state.realmIdAt(settlement));
            if (realmRow < 0 || state.statusAt(settlement) == SettlementStatus.RUINED) continue;
            long population = Math.max(0L, state.populationAt(settlement));
            npcSettlementCounts[realmRow]++;
            npcPopulations[realmRow] = saturatedAdd(npcPopulations[realmRow], population);
            npcProsperitySums[realmRow] = saturatedAdd(
                    npcProsperitySums[realmRow],
                    (state.productivityAt(settlement)
                                    + state.stabilityAt(settlement)
                                    + state.attractivenessAt(settlement)
                                    + state.productiveCapitalAt(settlement))
                            / 4L);
            npcDamageSums[realmRow] = saturatedAdd(
                    npcDamageSums[realmRow], state.damageAt(settlement));
            updateCultureCandidate(realmRow, state.cultureKeyAt(settlement), population);
            for (int shock = 0; shock < shocks.size(); shock++) {
                if (shocks.matchesAt(
                        shock,
                        state.settlementIdAt(settlement),
                        state.regionKeyAt(settlement),
                        state.cultureKeyAt(settlement),
                        cycle)) {
                    npcShockSums[realmRow] = saturatedAdd(
                            npcShockSums[realmRow], shocks.magnitudeAt(shock));
                }
            }
        }
        for (int settlement = 0; settlement < state.size(); settlement++) {
            int realmRow = findNpcRealmRow(state.realmIdAt(settlement));
            if (realmRow >= 0
                    && state.statusAt(settlement) != SettlementStatus.RUINED
                    && state.cultureKeyAt(settlement) == npcCultureCandidates[realmRow]) {
                npcDominantPopulations[realmRow] = saturatedAdd(
                        npcDominantPopulations[realmRow], state.populationAt(settlement));
            }
        }
    }

    private RealmHistoricalInputs formationInputs(int row) {
        int settlements = clusterSettlementCounts[row];
        long population = clusterPopulations[row];
        int populationReadiness = ratio(
                population,
                Math.max(minimumCityPopulation, settlements * (long) minimumCityPopulation / 2L));
        int foodCoverage = ratio(clusterFoodStocks[row], Math.max(1L, population * 6L));
        if (clusterFoodFlows[row] < 0L) {
            foodCoverage = clamp(foodCoverage
                    - ratio(absSaturated(clusterFoodFlows[row]), Math.max(1L, population)) / 3);
        } else if (clusterFoodFlows[row] > 0L) {
            foodCoverage = clamp(foodCoverage
                    + ratio(clusterFoodFlows[row], Math.max(1L, population)) / 5);
        }
        int prosperity = average(clusterProsperitySums[row], settlements);
        int market = average(clusterMarketSums[row], settlements);
        int fiscal = clamp(prosperity * 60 / 100 + market * 40 / 100);
        int security = average(clusterSecuritySums[row], settlements);
        int arms = ratio(clusterArmsStocks[row], Math.max(1L, population * 2L));
        int military = clamp(security * 65 / 100 + arms * 35 / 100);
        int stability = average(clusterStabilitySums[row], settlements);
        int capital = average(clusterCapitalSums[row], settlements);
        int administration = clamp(capital * 55 / 100 + market * 25 / 100 + stability * 20 / 100);
        int authority = clusterLeaderAuthorities[row];
        int ambition = clamp(authority * 45 / 100 + military * 35 / 100 + market * 20 / 100);
        return new RealmHistoricalInputs(
                settlements,
                population,
                populationReadiness,
                foodCoverage,
                fiscal,
                military,
                stability,
                capital,
                market,
                authority,
                administration,
                1000,
                0,
                0,
                ambition,
                true);
    }

    private boolean formRealm(int cluster, int pressure, long cycle, long milliYear) {
        PackedSettlementSimulationState state = simulation.state();
        int eligibleCount = 0;
        int capitalScratchRow = -1;
        long bestScore = Long.MIN_VALUE;
        for (int row = 0; row < state.size(); row++) {
            if (!matchesEligibleCluster(state, row, cluster)) continue;
            UUID village = simulationKeys.settlement(state.settlementIdAt(row));
            if (realms.realmForSettlement(village) != RealmRegistry.NO_REALM) continue;
            long subject = realms.keys().internSettlement(village);
            eligibleRows[eligibleCount] = row;
            eligibleSubjects[eligibleCount] = subject;
            long score = saturatedAdd(
                    state.populationAt(row) * 8L,
                    state.stabilityAt(row) * 4L + state.buildingCountAt(row) * 25L);
            if (score > bestScore) {
                bestScore = score;
                capitalScratchRow = eligibleCount;
            }
            eligibleCount++;
        }
        if (eligibleCount < 1 || capitalScratchRow < 0) return false;

        int market = average(clusterMarketSums[cluster], clusterSettlementCounts[cluster]);
        int securityPressure = 1000
                - average(clusterSecuritySums[cluster], clusterSettlementCounts[cluster]);
        GovernmentForm government = initialGovernment(market, securityPressure, eligibleCount);
        int legitimacy = clamp(500 + pressure / 3);
        long capitalSubject = eligibleSubjects[capitalScratchRow];
        long realmId = registry.createRealm(
                capitalSubject,
                RealmMemberKind.NPC_SETTLEMENT,
                0L,
                government,
                legitimacy,
                cycle);
        if (realmId == RealmRegistry.NO_REALM) return false;

        boolean success = true;
        for (int index = 0; index < eligibleCount; index++) {
            if (index == capitalScratchRow) continue;
            int stateRow = eligibleRows[index];
            int influence = clamp(
                    250 + state.tierAt(stateRow).ordinal() * 120 + state.stabilityAt(stateRow) / 4);
            if (!registry.addMember(
                    realmId,
                    eligibleSubjects[index],
                    RealmMemberKind.NPC_SETTLEMENT,
                    0L,
                    influence)) {
                success = false;
                break;
            }
        }
        if (!success) {
            registry.dissolveRealm(realmId);
            return false;
        }

        for (int index = 0; index < eligibleCount; index++) {
            state.assignRealm(state.settlementIdAt(eligibleRows[index]), realmId);
        }
        int institutionRow = institutions.ensureRealm(
                realmId,
                Constitution.archetype(government, legitimacy),
                milliYear);
        realms.upsertMetadata(
                realmId,
                autonomousName(clusterCultures[cluster], clusterRegions[cluster]),
                8,
                0L,
                false);
        var initialHistory = historicalPolicy.initial(formationInputs(cluster), milliYear);
        if (institutionRow < 0 || realms.history().ensureRealm(realmId, initialHistory, milliYear) < 0) {
            institutions.removeRealm(realmId);
            realms.history().removeRealm(realmId);
            registry.dissolveRealm(realmId);
            realms.removeMetadata(realmId);
            for (int index = 0; index < eligibleCount; index++) {
                state.assignRealm(state.settlementIdAt(eligibleRows[index]), RealmRegistry.NO_REALM);
            }
            return false;
        }
        lifecycle.removeFormation(clusterRegions[cluster], clusterCultures[cluster]);
        realms.markChanged();
        simulation.markChanged();
        formedRealmCount++;
        LOGGER.info(
                "[BANNEROK_AUTONOMOUS_REALM_FORMED] realm={} culture={} region={} settlements={} population={} scale={} phase={} government={} legitimacy={} pressure={} year_milli={} cycle={}",
                realmId,
                clusterCultures[cluster],
                clusterRegions[cluster],
                eligibleCount,
                clusterPopulations[cluster],
                initialHistory.scale(),
                initialHistory.phase(),
                government,
                legitimacy,
                pressure,
                milliYear,
                cycle);
        return true;
    }

    private boolean expandRealmPeacefully(
            int realmRow,
            long realmId,
            long cycle,
            long milliYear) {
        PackedSettlementSimulationState state = simulation.state();
        int culture = npcCultureCandidates[realmRow];
        long capitalRegion = capitalRegion(realmId);
        if (culture <= 0 || capitalRegion == 0L) return false;

        int bestRow = -1;
        long bestScore = Long.MIN_VALUE;
        for (int row = 0; row < state.size(); row++) {
            lastWorkUnits++;
            SettlementStatus status = state.statusAt(row);
            if (state.realmIdAt(row) != RealmRegistry.NO_REALM
                    || !state.physicallyPresentAt(row)
                    || (status != SettlementStatus.ACTIVE && status != SettlementStatus.DECLINING)
                    || state.cultureKeyAt(row) != culture
                    || state.regionKeyAt(row) != capitalRegion
                    || state.populationAt(row) <= 0L) {
                continue;
            }
            long score = state.populationAt(row) * 10L
                    + state.stabilityAt(row) * 4L
                    + state.attractivenessAt(row) * 3L
                    + state.marketAccessAt(row) * 2L
                    + state.productiveCapitalAt(row) * 2L
                    - state.damageAt(row) * 3L;
            if (score > bestScore) {
                bestScore = score;
                bestRow = row;
            }
        }
        if (bestRow < 0) return false;

        UUID village = simulationKeys.settlement(state.settlementIdAt(bestRow));
        long subject = realms.keys().internSettlement(village);
        int influence = clamp(
                300
                        + state.tierAt(bestRow).ordinal() * 110
                        + state.stabilityAt(bestRow) / 5
                        + state.marketAccessAt(bestRow) / 10);
        if (!registry.addMember(
                realmId,
                subject,
                RealmMemberKind.NPC_SETTLEMENT,
                0L,
                influence)) {
            return false;
        }
        if (!state.assignRealm(state.settlementIdAt(bestRow), realmId)) {
            registry.removeMember(subject);
            return false;
        }
        removeConsumedFormationCandidate(state, culture, capitalRegion);
        realms.markChanged();
        simulation.markChanged();
        peacefulExpansionCount++;
        LOGGER.info(
                "[BANNEROK_AUTONOMOUS_REALM_EXPANSION] realm={} settlement={} culture={} region={} population={} influence={} mode=PEACEFUL_INTEGRATION year_milli={} cycle={}",
                realmId,
                state.settlementIdAt(bestRow),
                culture,
                capitalRegion,
                state.populationAt(bestRow),
                influence,
                milliYear,
                cycle);
        return true;
    }

    private long capitalRegion(long realmId) {
        long capitalSubject = registry.capitalMemberId(realmId);
        if (!realms.keys().valid(capitalSubject)) return 0L;
        UUID capital = realms.keys().uuid(capitalSubject);
        long simulationSettlement = simulationKeys.findSettlement(capital);
        if (simulationSettlement == 0L) return 0L;
        int row = simulation.state().find(simulationSettlement);
        return row < 0 ? 0L : simulation.state().regionKeyAt(row);
    }

    private void removeConsumedFormationCandidate(
            PackedSettlementSimulationState state,
            int culture,
            long region) {
        for (int row = 0; row < state.size(); row++) {
            if (state.realmIdAt(row) == RealmRegistry.NO_REALM
                    && state.physicallyPresentAt(row)
                    && state.statusAt(row) == SettlementStatus.ACTIVE
                    && state.cultureKeyAt(row) == culture
                    && state.regionKeyAt(row) == region) {
                return;
            }
        }
        lifecycle.removeFormation(region, culture);
    }

    private boolean secedeProvince(
            int realmRow,
            long realmId,
            RealmHistoricalPhase phase,
            long cycle,
            long milliYear) {
        PackedSettlementSimulationState state = simulation.state();
        long capitalSubject = registry.capitalMemberId(realmId);
        int capitalCulture = capitalCulture(realmId);
        long capitalRegion = capitalRegion(realmId);
        Constitution constitution = institutions.constitution(realmId);
        int administrativeReserve = constitution == null
                ? realms.history().stateCapacity(realmId)
                : clamp((constitution.bureaucracy() * 2
                                + constitution.centralization()
                                + realms.history().stateCapacity(realmId))
                        / 4);
        int culturalCohesion = ratio(
                npcDominantPopulations[realmRow], npcPopulations[realmRow]);
        if (npcPopulations[realmRow] <= 0L) culturalCohesion = 0;

        int bestRow = -1;
        long bestSubject = 0L;
        RealmSecessionDecision bestDecision = null;
        long bestScore = Long.MIN_VALUE;
        for (int row = 0; row < state.size(); row++) {
            lastWorkUnits++;
            if (state.realmIdAt(row) != realmId
                    || !state.physicallyPresentAt(row)
                    || state.statusAt(row) == SettlementStatus.RUINED
                    || state.populationAt(row) <= 0L) {
                continue;
            }
            UUID village = simulationKeys.settlement(state.settlementIdAt(row));
            long subject = realms.keys().findSettlement(village);
            if (subject == 0L || subject == capitalSubject) continue;
            RealmSecessionInputs inputs = new RealmSecessionInputs(
                    phase,
                    npcSettlementCounts[realmRow],
                    realms.history().viability(realmId),
                    administrativeReserve,
                    culturalCohesion,
                    ratio(state.populationAt(row), minimumCityPopulation),
                    state.stabilityAt(row),
                    state.securityAt(row),
                    state.damageAt(row),
                    state.marketAccessAt(row),
                    state.productiveCapitalAt(row),
                    state.cultureKeyAt(row) == capitalCulture,
                    state.regionKeyAt(row) != capitalRegion);
            RealmSecessionDecision decision = secessionPolicy.evaluate(inputs);
            if (!decision.secedes()) continue;
            long score = decision.pressure() * 1_000_000L
                    + decision.breakawayCapacity() * 1_000L
                    + Math.min(999L, state.populationAt(row));
            if (score > bestScore) {
                bestScore = score;
                bestRow = row;
                bestSubject = subject;
                bestDecision = decision;
            }
        }
        if (bestRow < 0 || bestSubject == 0L || bestDecision == null) return false;

        long settlementId = state.settlementIdAt(bestRow);
        RealmMemberKind oldKind = registry.memberKind(bestSubject);
        long oldController = registry.memberControllerId(bestSubject);
        int oldInfluence = registry.memberInfluence(bestSubject);
        if (oldKind == null || !registry.removeMember(bestSubject)) return false;
        if (!state.assignRealm(settlementId, RealmRegistry.NO_REALM)) {
            registry.addMember(realmId, bestSubject, oldKind, oldController, oldInfluence);
            return false;
        }

        long successorRealm = RealmRegistry.NO_REALM;
        if (bestDecision.formsBreakawayState()) {
            GovernmentForm government = initialGovernment(
                    state.marketAccessAt(bestRow), 1000 - state.securityAt(bestRow), 1);
            int legitimacy = clamp((state.stabilityAt(bestRow)
                            + state.securityAt(bestRow)
                            + bestDecision.breakawayCapacity())
                    / 3);
            successorRealm = registry.createRealm(
                    bestSubject,
                    oldKind,
                    oldController,
                    government,
                    legitimacy,
                    cycle);
            if (successorRealm == RealmRegistry.NO_REALM) {
                rollbackSecedingProvince(
                        state, bestRow, realmId, bestSubject, oldKind, oldController, oldInfluence, 0L);
                return false;
            }
            try {
                int institutionRow = institutions.ensureRealm(
                        successorRealm,
                        Constitution.archetype(government, legitimacy),
                        milliYear);
                if (institutionRow < 0) {
                    throw new IllegalStateException("Successor institution capacity exhausted");
                }
                realms.upsertMetadata(
                        successorRealm,
                        successorName(state.cultureKeyAt(bestRow), state.regionKeyAt(bestRow)),
                        6,
                        0L,
                        false);
                var successorHistory = historicalPolicy.initial(
                        localHistoricalInputs(state, bestRow), milliYear);
                if (realms.history().ensureRealm(successorRealm, successorHistory, milliYear) < 0) {
                    throw new IllegalStateException("Successor history capacity exhausted");
                }
                if (!state.assignRealm(settlementId, successorRealm)) {
                    throw new IllegalStateException("Could not bind successor Realm to Simulation province");
                }
                successorStateCount++;
            } catch (RuntimeException failure) {
                rollbackSecedingProvince(
                        state,
                        bestRow,
                        realmId,
                        bestSubject,
                        oldKind,
                        oldController,
                        oldInfluence,
                        successorRealm);
                LOGGER.warn(
                        "Could not complete successor Realm for province {} of Realm {}",
                        settlementId,
                        realmId,
                        failure);
                return false;
            }
        } else {
            statelessProvinceCount++;
        }

        realms.history().markSecession(realmId, milliYear);
        realms.markChanged();
        simulation.markChanged();
        secessionCount++;
        LOGGER.info(
                "[BANNEROK_AUTONOMOUS_REALM_SECESSION] parent={} province={} successor={} mode={} pressure={} capacity={} reasons={} culture={} region={} population={} year_milli={} cycle={}",
                realmId,
                settlementId,
                successorRealm,
                successorRealm == RealmRegistry.NO_REALM ? "STATELESS" : "SUCCESSOR_STATE",
                bestDecision.pressure(),
                bestDecision.breakawayCapacity(),
                bestDecision.reasonMask(),
                state.cultureKeyAt(bestRow),
                state.regionKeyAt(bestRow),
                state.populationAt(bestRow),
                milliYear,
                cycle);
        return true;
    }

    private RealmHistoricalInputs localHistoricalInputs(
            PackedSettlementSimulationState state,
            int row) {
        long population = Math.max(0L, state.populationAt(row));
        int populationReadiness = ratio(population, minimumCityPopulation);
        int foodCoverage = ratio(state.stockAt(row, 0), Math.max(1L, population * 6L));
        long foodFlow = state.netFlowAt(row, 0);
        if (foodFlow < 0L) {
            foodCoverage = clamp(foodCoverage
                    - ratio(absSaturated(foodFlow), Math.max(1L, population)) / 3);
        } else if (foodFlow > 0L) {
            foodCoverage = clamp(foodCoverage
                    + ratio(foodFlow, Math.max(1L, population)) / 5);
        }
        int prosperity = (state.productivityAt(row)
                        + state.stabilityAt(row)
                        + state.attractivenessAt(row)
                        + state.productiveCapitalAt(row))
                / 4;
        int fiscalCapacity = clamp(
                prosperity * 60 / 100 + state.marketAccessAt(row) * 40 / 100);
        int armsCoverage = state.commodityCount() > 6
                ? ratio(state.stockAt(row, 6), Math.max(1L, population * 2L))
                : 0;
        int militaryPower = clamp(
                state.securityAt(row) * 65 / 100 + armsCoverage * 35 / 100);
        int administrativeReserve = clamp(
                state.productiveCapitalAt(row) * 55 / 100
                        + state.marketAccessAt(row) * 25 / 100
                        + state.stabilityAt(row) * 20 / 100);
        int authority = (state.stabilityAt(row)
                        + state.productivityAt(row)
                        + state.attractivenessAt(row))
                / 3;
        int ambition = clamp(
                authority * 45 / 100
                        + militaryPower * 35 / 100
                        + state.marketAccessAt(row) * 20 / 100);
        return new RealmHistoricalInputs(
                1,
                population,
                populationReadiness,
                foodCoverage,
                fiscalCapacity,
                militaryPower,
                state.stabilityAt(row),
                state.productiveCapitalAt(row),
                state.marketAccessAt(row),
                authority,
                administrativeReserve,
                1000,
                state.damageAt(row),
                state.damageAt(row),
                ambition,
                true);
    }

    private void rollbackSecedingProvince(
            PackedSettlementSimulationState state,
            int row,
            long parentRealm,
            long subject,
            RealmMemberKind kind,
            long controller,
            int influence,
            long successorRealm) {
        if (successorRealm != RealmRegistry.NO_REALM) {
            institutions.removeRealm(successorRealm);
            realms.history().removeRealm(successorRealm);
            realms.diplomacy().removeRealm(successorRealm);
            realms.dependencies().removeRealm(successorRealm);
            registry.dissolveRealm(successorRealm);
            realms.removeMetadata(successorRealm);
        }
        if (registry.realmOfMember(subject) == RealmRegistry.NO_REALM) {
            registry.addMember(parentRealm, subject, kind, controller, influence);
        }
        state.assignRealm(state.settlementIdAt(row), parentRealm);
    }

    private int capitalCulture(long realmId) {
        long capitalSubject = registry.capitalMemberId(realmId);
        if (!realms.keys().valid(capitalSubject)) return 0;
        UUID capital = realms.keys().uuid(capitalSubject);
        long simulationSettlement = simulationKeys.findSettlement(capital);
        if (simulationSettlement == 0L) return 0;
        int row = simulation.state().find(simulationSettlement);
        return row < 0 ? 0 : simulation.state().cultureKeyAt(row);
    }

    private String successorName(int cultureKey, long regionKey) {
        ResourceLocation culture = simulationKeys.culture(cultureKey);
        String stem = culture.getPath().replace('_', ' ');
        String value = stem + " successor " + Long.toUnsignedString(mix(regionKey), 36);
        return value.length() <= RealmSavedData.MAX_NAME_LENGTH
                ? value
                : value.substring(0, RealmSavedData.MAX_NAME_LENGTH);
    }

    private boolean dissolveRealm(long realmId, int pressure, long cycle, long milliYear) {
        if (!registry.exists(realmId) || registry.hasPlayerMembers(realmId) || realms.isLegacy(realmId)) {
            return false;
        }
        PackedSettlementSimulationState state = simulation.state();
        for (int row = 0; row < state.size(); row++) {
            if (state.realmIdAt(row) == realmId) {
                state.assignRealm(state.settlementIdAt(row), RealmRegistry.NO_REALM);
            }
        }
        institutions.removeRealm(realmId);
        lifecycle.removeCrisis(realmId);
        realms.diplomacy().removeRealm(realmId);
        realms.dependencies().removeRealm(realmId);
        realms.history().removeRealm(realmId);
        registry.dissolveRealm(realmId);
        realms.removeMetadata(realmId);
        realms.markChanged();
        simulation.markChanged();
        dissolvedRealmCount++;
        LOGGER.info(
                "[BANNEROK_AUTONOMOUS_REALM_DISSOLVED] realm={} pressure={} year_milli={} cycle={}",
                realmId,
                pressure,
                milliYear,
                cycle);
        return true;
    }

    private boolean capitalExists(long realmId) {
        long capitalSubject = registry.capitalMemberId(realmId);
        if (!realms.keys().valid(capitalSubject)) return false;
        UUID capital = realms.keys().uuid(capitalSubject);
        long simulationSettlement = simulationKeys.findSettlement(capital);
        if (simulationSettlement == 0L) return false;
        int row = simulation.state().find(simulationSettlement);
        return row >= 0
                && simulation.state().realmIdAt(row) == realmId
                && simulation.state().statusAt(row) != SettlementStatus.RUINED
                && simulation.state().physicallyPresentAt(row);
    }

    private boolean matchesEligibleCluster(
            PackedSettlementSimulationState state,
            int row,
            int cluster) {
        return state.realmIdAt(row) == RealmRegistry.NO_REALM
                && state.statusAt(row) == SettlementStatus.ACTIVE
                && state.physicallyPresentAt(row)
                && state.populationAt(row) >= minimumCityPopulation
                && state.buildingCountAt(row) >= 4
                && state.productiveCapitalAt(row) >= 300
                && state.regionKeyAt(row) == clusterRegions[cluster]
                && state.cultureKeyAt(row) == clusterCultures[cluster];
    }

    private int clusterRow(long regionKey, int cultureKey) {
        int slot = hashPair(regionKey, cultureKey) & clusterMapMask;
        while (clusterMapEpochs[slot] == clusterMapEpoch) {
            int row = clusterMapRows[slot];
            if (clusterRegions[row] == regionKey && clusterCultures[row] == cultureKey) return row;
            slot = (slot + 1) & clusterMapMask;
        }
        if (clusterCount == clusterRegions.length) {
            throw new IllegalStateException("Formation cluster capacity exhausted");
        }
        int row = clusterCount++;
        clusterRegions[row] = regionKey;
        clusterCultures[row] = cultureKey;
        clusterSettlementCounts[row] = 0;
        clusterPopulations[row] = 0L;
        clusterMarketSums[row] = 0L;
        clusterSecuritySums[row] = 0L;
        clusterStabilitySums[row] = 0L;
        clusterCapitalSums[row] = 0L;
        clusterProsperitySums[row] = 0L;
        clusterFoodStocks[row] = 0L;
        clusterFoodFlows[row] = 0L;
        clusterArmsStocks[row] = 0L;
        clusterLeaderAuthorities[row] = 0;
        clusterBestSettlementIds[row] = 0L;
        clusterBestScores[row] = Long.MIN_VALUE;
        clusterMapEpochs[slot] = clusterMapEpoch;
        clusterMapRows[slot] = row;
        return row;
    }

    private void updateCultureCandidate(int realmRow, int cultureKey, long population) {
        if (population <= 0L) return;
        if (npcCultureBalances[realmRow] == 0L) {
            npcCultureCandidates[realmRow] = cultureKey;
            npcCultureBalances[realmRow] = population;
        } else if (npcCultureCandidates[realmRow] == cultureKey) {
            npcCultureBalances[realmRow] = saturatedAdd(npcCultureBalances[realmRow], population);
        } else if (npcCultureBalances[realmRow] > population) {
            npcCultureBalances[realmRow] -= population;
        } else {
            npcCultureCandidates[realmRow] = cultureKey;
            npcCultureBalances[realmRow] = population - npcCultureBalances[realmRow];
        }
    }

    private void putNpcRealmRow(long realmId, int row) {
        int slot = hashLong(realmId) & npcRealmMapMask;
        while (npcRealmMapEpochs[slot] == npcRealmMapEpoch) {
            if (npcRealmMapKeys[slot] == realmId) {
                throw new IllegalStateException("Duplicate NPC Realm id");
            }
            slot = (slot + 1) & npcRealmMapMask;
        }
        npcRealmMapEpochs[slot] = npcRealmMapEpoch;
        npcRealmMapKeys[slot] = realmId;
        npcRealmMapRows[slot] = row;
    }

    private int findNpcRealmRow(long realmId) {
        if (realmId <= 0L) return -1;
        int slot = hashLong(realmId) & npcRealmMapMask;
        while (npcRealmMapEpochs[slot] == npcRealmMapEpoch) {
            if (npcRealmMapKeys[slot] == realmId) return npcRealmMapRows[slot];
            slot = (slot + 1) & npcRealmMapMask;
        }
        return -1;
    }

    private void clearNpcAggregates(int count) {
        Arrays.fill(npcSettlementCounts, 0, count, 0);
        Arrays.fill(npcPopulations, 0, count, 0L);
        Arrays.fill(npcProsperitySums, 0, count, 0L);
        Arrays.fill(npcDamageSums, 0, count, 0L);
        Arrays.fill(npcShockSums, 0, count, 0L);
        Arrays.fill(npcCultureCandidates, 0, count, 0);
        Arrays.fill(npcCultureBalances, 0, count, 0L);
        Arrays.fill(npcDominantPopulations, 0, count, 0L);
    }

    private void beginClusterEpoch() {
        clusterMapEpoch++;
        if (clusterMapEpoch == 0) {
            Arrays.fill(clusterMapEpochs, 0);
            clusterMapEpoch = 1;
        }
    }

    private void beginNpcRealmEpoch() {
        npcRealmMapEpoch++;
        if (npcRealmMapEpoch == 0) {
            Arrays.fill(npcRealmMapEpochs, 0);
            npcRealmMapEpoch = 1;
        }
    }

    private GovernmentForm initialGovernment(int market, int securityPressure, int settlements) {
        if (market >= 800 && settlements <= 9) return GovernmentForm.CITY_LEAGUE;
        if (market >= 650) return GovernmentForm.MERCHANT_REPUBLIC;
        if (securityPressure >= 700) return GovernmentForm.MILITARY_AUTOCRACY;
        return GovernmentForm.CLAN_CONFEDERATION;
    }

    private String autonomousName(int cultureKey, long regionKey) {
        ResourceLocation culture = simulationKeys.culture(cultureKey);
        String stem = culture.getPath().replace('_', ' ');
        String value = stem + " league " + Long.toUnsignedString(mix(regionKey), 36);
        return value.length() <= RealmSavedData.MAX_NAME_LENGTH
                ? value
                : value.substring(0, RealmSavedData.MAX_NAME_LENGTH);
    }

    public long evaluationCount() { return evaluationCount; }
    public long formedRealmCount() { return formedRealmCount; }
    public long peacefulExpansionCount() { return peacefulExpansionCount; }
    public long secessionCount() { return secessionCount; }
    public long successorStateCount() { return successorStateCount; }
    public long statelessProvinceCount() { return statelessProvinceCount; }
    public long dissolvedRealmCount() { return dissolvedRealmCount; }
    public long rejectedFormationCount() { return rejectedFormationCount; }
    public int lastWorkUnits() { return lastWorkUnits; }

    public void logShutdownMetrics() {
        LOGGER.info(
                "[BANNEROK_AUTONOMOUS_REALM_METRICS] evaluations={} formed={} peaceful_expansions={} secessions={} successor_states={} stateless_provinces={} dissolved={} rejected_formations={} formation_candidates={} crises={} last_work={}",
                evaluationCount,
                formedRealmCount,
                peacefulExpansionCount,
                secessionCount,
                successorStateCount,
                statelessProvinceCount,
                dissolvedRealmCount,
                rejectedFormationCount,
                lifecycle.formationSize(),
                lifecycle.crisisSize(),
                lastWorkUnits);
    }

    private static int average(long sum, int count) {
        return count <= 0 ? 0 : clamp((int) Math.min(Integer.MAX_VALUE, sum / count));
    }

    private static int ratio(long part, long total) {
        if (part <= 0L || total <= 0L) return 0;
        if (part >= total) return 1000;
        long quotient = part / total;
        long remainder = part % total;
        return (int) Math.min(1000L, quotient * 1000L + remainder * 1000L / total);
    }

    private long historicalMilliYear(long gameTime) {
        long years = gameTime / historicalYearTicks;
        long remainder = gameTime % historicalYearTicks;
        if (years > Long.MAX_VALUE / 1000L) return Long.MAX_VALUE;
        return years * 1000L + remainder * 1000L / historicalYearTicks;
    }

    private static long absSaturated(long value) {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(1000, value));
    }

    private static int yearsToMilliYears(int years) {
        if (years <= 0) throw new IllegalArgumentException("Historical years must be positive");
        return years > Integer.MAX_VALUE / 1000 ? Integer.MAX_VALUE : years * 1000;
    }

    private static int saturatedInt(long value) {
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, value);
    }

    private static long saturatedAdd(long left, long right) {
        if (right <= 0L) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long saturatedAdd(long left, int right) {
        return saturatedAdd(left, (long) right);
    }

    private static long saturatedAddSigned(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0L && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }

    private static int powerOfTwoAtLeast(long requested) {
        int capacity = 1;
        while (capacity < requested) {
            if (capacity >= 1 << 29) {
                throw new IllegalArgumentException("Autonomous Realm table is too large");
            }
            capacity <<= 1;
        }
        return capacity;
    }

    private static int hashPair(long regionKey, int cultureKey) {
        return hashLong(regionKey ^ Long.rotateLeft(Integer.toUnsignedLong(cultureKey), 23));
    }

    private static int hashLong(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return (int) value;
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }
}
