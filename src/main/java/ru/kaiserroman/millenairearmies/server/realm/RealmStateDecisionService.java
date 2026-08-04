package ru.kaiserroman.millenairearmies.server.realm;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingPlan;
import org.millenaire.building.BuildingPlanSet;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillageType;
import org.millenaire.village.ControlledQueuedProject;
import org.millenaire.village.Village;
import org.millenaire.village.VillageGrowthManager;
import org.millenaire.village.VillageSavedData;
import org.millenaire.world.PlacedLocation;
import org.slf4j.Logger;
import ru.kaiserroman.millenaire.realm.Constitution;
import ru.kaiserroman.millenaire.realm.DiplomaticStatus;
import ru.kaiserroman.millenaire.realm.RealmHistoricalPhase;
import ru.kaiserroman.millenaire.realm.RealmScale;
import ru.kaiserroman.millenaire.realm.RealmStateDecision;
import ru.kaiserroman.millenaire.realm.RealmStateDecisionInputs;
import ru.kaiserroman.millenaire.realm.RealmStateDecisionPolicy;
import ru.kaiserroman.millenaire.realm.RealmStatePriority;
import ru.kaiserroman.millenaire.simulation.PackedSettlementSimulationState;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireRealmBuildingPolicy;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireVillageIndex;
import ru.kaiserroman.millenairearmies.persistence.RealmKeyTable;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;

/**
 * Historical state cabinet for NPC Realms.
 *
 * <p>Every evaluation aggregates the Realm's real Simulation settlements, chooses one persisted
 * multi-year programme, spends canonical treasury on a concrete culture-specific Millenaire
 * project, and may seek a truce when survival takes precedence over war. The programme is then
 * consumed by physical projection, autonomous expansion and diplomacy; it is not a UI-only score.</p>
 */
public final class RealmStateDecisionService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FOOD = 0;

    private final MillenaireVillageIndex villages;
    private final RealmSavedData realms;
    private final SimulationSavedData simulation;
    private final PackedSettlementSimulationState state;
    private final CanonicalRealmDiplomacyService diplomacy;
    private final RealmStateDecisionPolicy decisionPolicy = new RealmStateDecisionPolicy();
    private final MillenaireRealmBuildingPolicy buildingPolicy = new MillenaireRealmBuildingPolicy();
    private final Long2IntOpenHashMap realmRows;
    private final long[] realmIds;
    private final int[] settlementCounts;
    private final long[] populations;
    private final long[] foodStocks;
    private final long[] securitySums;
    private final long[] marketSums;
    private final long[] productivitySums;
    private final long[] damageSums;
    private final boolean[] atWar;
    private final long[] warOpponents;
    private final int evaluationTicks;
    private final int realmsPerTick;
    private final long decisionIntervalMilliYears;
    private final long baseInvestmentCost;
    private final int maximumProjectCandidates;
    private final long historicalYearTicks;
    private final long simulationCycleTicks;

    private int realmCount;
    private int cursor;
    private boolean evaluating;
    private long currentMilliYear;
    private long nextEvaluationTick;
    private long snapshotCount;
    private long evaluatedRealmCount;
    private long decisionCount;
    private long priorityChangeCount;
    private long projectQueuedCount;
    private long projectUnavailableCount;
    private long investmentSpent;
    private long truceRequestCount;
    private int lastTickWorkUnits;

    public RealmStateDecisionService(
            MillenaireVillageIndex villages,
            RealmSavedData realms,
            SimulationSavedData simulation,
            CanonicalRealmDiplomacyService diplomacy,
            int maximumRealms,
            int evaluationTicks,
            int realmsPerTick,
            int decisionIntervalYears,
            long baseInvestmentCost,
            int maximumProjectCandidates,
            long historicalYearTicks,
            long simulationCycleTicks) {
        if (villages == null || realms == null || simulation == null) {
            throw new NullPointerException("Realm state decision dependency");
        }
        if (maximumRealms <= 0 || evaluationTicks <= 0 || realmsPerTick <= 0
                || decisionIntervalYears <= 0 || baseInvestmentCost <= 0L
                || maximumProjectCandidates <= 0 || historicalYearTicks <= 0L
                || simulationCycleTicks <= 0L) {
            throw new IllegalArgumentException("Invalid Realm state decision bounds");
        }
        this.villages = villages;
        this.realms = realms;
        this.simulation = simulation;
        this.state = simulation.state();
        this.diplomacy = diplomacy;
        this.evaluationTicks = evaluationTicks;
        this.realmsPerTick = realmsPerTick;
        this.decisionIntervalMilliYears = (long) decisionIntervalYears * 1000L;
        this.baseInvestmentCost = baseInvestmentCost;
        this.maximumProjectCandidates = maximumProjectCandidates;
        this.historicalYearTicks = historicalYearTicks;
        this.simulationCycleTicks = simulationCycleTicks;
        this.realmRows = new Long2IntOpenHashMap(maximumRealms * 2);
        this.realmRows.defaultReturnValue(-1);
        this.realmIds = new long[maximumRealms];
        this.settlementCounts = new int[maximumRealms];
        this.populations = new long[maximumRealms];
        this.foodStocks = new long[maximumRealms];
        this.securitySums = new long[maximumRealms];
        this.marketSums = new long[maximumRealms];
        this.productivitySums = new long[maximumRealms];
        this.damageSums = new long[maximumRealms];
        this.atWar = new boolean[maximumRealms];
        this.warOpponents = new long[maximumRealms];
    }

    public void tick(long gameTime) {
        if (gameTime < 0L) throw new IllegalArgumentException("Negative gameTime");
        lastTickWorkUnits = 0;
        if (!evaluating) {
            if (gameTime < nextEvaluationTick) return;
            beginSnapshot(gameTime);
        }
        for (int budget = realmsPerTick; budget > 0 && cursor < realmCount; budget--) {
            evaluateRealm(cursor++, gameTime);
            lastTickWorkUnits++;
        }
        if (cursor >= realmCount) {
            evaluating = false;
            nextEvaluationTick = saturatedAdd(gameTime, evaluationTicks);
        }
    }

    private void beginSnapshot(long gameTime) {
        clearSnapshot();
        realmRows.clear();
        realms.registry().visitRealms((realmId, capital, founded, government, legitimacy) -> {
            // Player and mixed Realms remain player-governed. Their priority stays NONE, so this
            // service cannot spend their treasury, queue buildings or constrain their diplomacy.
            if (realms.registry().hasPlayerMembers(realmId) || realmCount >= realmIds.length) return;
            int row = realmCount++;
            realmIds[row] = realmId;
            realmRows.put(realmId, row);
        });

        for (int settlementRow = 0; settlementRow < state.size(); settlementRow++) {
            long realmId = state.realmIdAt(settlementRow);
            int realmRow = realmRows.get(realmId);
            if (realmRow < 0 || !state.physicallyPresentAt(settlementRow)) continue;
            settlementCounts[realmRow]++;
            populations[realmRow] = saturatedAdd(
                    populations[realmRow], state.populationAt(settlementRow));
            foodStocks[realmRow] = saturatedAdd(
                    foodStocks[realmRow], state.stockAt(settlementRow, FOOD));
            securitySums[realmRow] = saturatedAdd(
                    securitySums[realmRow], state.securityAt(settlementRow));
            marketSums[realmRow] = saturatedAdd(
                    marketSums[realmRow], state.marketAccessAt(settlementRow));
            productivitySums[realmRow] = saturatedAdd(
                    productivitySums[realmRow], state.productivityAt(settlementRow));
            damageSums[realmRow] = saturatedAdd(
                    damageSums[realmRow], state.damageAt(settlementRow));
        }

        realms.diplomacy().visit((firstRealm, secondRealm, status, firstGoal, secondGoal,
                firstTrust, secondTrust, firstGrievances, secondGrievances,
                firstFear, secondFear, firstClaims, secondClaims,
                firstExhaustion, secondExhaustion, firstWarScore, secondWarScore,
                trade, border, ideology, commonThreat, truceUntil, lastEvaluation) -> {
            if (status != DiplomaticStatus.WAR) return;
            int first = realmRows.get(firstRealm);
            int second = realmRows.get(secondRealm);
            if (first >= 0) {
                atWar[first] = true;
                if (warOpponents[first] == 0L) warOpponents[first] = secondRealm;
            }
            if (second >= 0) {
                atWar[second] = true;
                if (warOpponents[second] == 0L) warOpponents[second] = firstRealm;
            }
        });

        currentMilliYear = historicalMilliYear(gameTime);
        cursor = 0;
        evaluating = true;
        snapshotCount++;
    }

    private void evaluateRealm(int row, long gameTime) {
        long realmId = realmIds[row];
        int settlements = settlementCounts[row];
        long population = populations[row];
        if (!realms.registry().exists(realmId) || settlements <= 0 || population <= 0L) return;
        evaluatedRealmCount++;

        RealmHistoricalPhase phase = realms.history().phase(realmId);
        RealmScale scale = realms.history().scale(realmId);
        if (phase == null) phase = RealmHistoricalPhase.STABLE;
        if (scale == null) scale = settlements == 1 ? RealmScale.CITY_STATE : RealmScale.REGIONAL_STATE;
        int viability = defaultIndex(realms.history().viability(realmId), 600);
        int expansion = realms.history().expansionReadiness(realmId);
        int security = average(securitySums[row], settlements);
        int market = average(marketSums[row], settlements);
        int productivity = average(productivitySums[row], settlements);
        int damage = average(damageSums[row], settlements);
        int foodCoverage = ratio(
                foodStocks[row],
                saturatedMultiply(Math.max(1L, population), 5L),
                1000);
        int treasuryCoverage = ratio(
                realms.treasury(realmId),
                saturatedMultiply(Math.max(1L, population), 20L),
                1000);
        Constitution constitution = realms.institutions().constitution(realmId);
        int administrativeReserve = administrativeReserve(constitution, settlements);

        RealmStateDecision decision = decisionPolicy.evaluate(new RealmStateDecisionInputs(
                phase,
                scale,
                viability,
                expansion,
                foodCoverage,
                security,
                market,
                productivity,
                damage,
                administrativeReserve,
                treasuryCoverage,
                atWar[row],
                settlements,
                population));

        RealmStatePriority previous = realms.statePriority(realmId);
        long previousYear = realms.lastStateDecisionMilliYear(realmId);
        boolean urgentChange = previous != decision.priority()
                && (decision.pressure() >= 700
                        || decision.priority() == RealmStatePriority.AUSTERITY
                        || decision.priority() == RealmStatePriority.RECOVERY
                        || decision.priority() == RealmStatePriority.FORTIFICATION);
        boolean due = previousYear < 0L
                || currentMilliYear < previousYear
                || currentMilliYear - previousYear >= decisionIntervalMilliYears;
        if (!due && !urgentChange) return;

        realms.recordStateDecision(
                realmId,
                decision.priority(),
                decision.pressure(),
                decision.investmentPermille(),
                currentMilliYear);
        realms.markChanged();
        decisionCount++;
        if (previous != decision.priority()) priorityChangeCount++;

        long spent = 0L;
        boolean projectQueued = false;
        if (decision.constructionPermitted()) {
            ProjectResult project = queueCapitalProject(realmId, decision, gameTime);
            projectQueued = project.queued();
            spent = project.spent();
            if (projectQueued) projectQueuedCount++;
            else projectUnavailableCount++;
            investmentSpent = saturatedAdd(investmentSpent, spent);
        }

        if (decision.seekPeace() && diplomacy != null && warOpponents[row] != 0L) {
            long cycle = gameTime / simulationCycleTicks;
            if (diplomacy.makeTruce(realmId, warOpponents[row], cycle)) truceRequestCount++;
        }

        LOGGER.info(
                "[BANNEROK_REALM_STATE_DECISION] realm={} year_milli={} priority={} previous={} pressure={} investment={} project_queued={} spent={} seek_peace={} pursue_expansion={} phase={} scale={} viability={} food={} security={} market={} productivity={} damage={} administration={} treasury={} settlements={} population={} reasons={}",
                realmId,
                currentMilliYear,
                decision.priority(),
                previous,
                decision.pressure(),
                decision.investmentPermille(),
                projectQueued,
                spent,
                decision.seekPeace(),
                decision.pursueExpansion(),
                phase,
                scale,
                viability,
                foodCoverage,
                security,
                market,
                productivity,
                damage,
                administrativeReserve,
                treasuryCoverage,
                settlements,
                population,
                decision.reasonMask());
    }

    private ProjectResult queueCapitalProject(
            long realmId,
            RealmStateDecision decision,
            long gameTime) {
        long capitalSubject = realms.registry().capitalMemberId(realmId);
        if (!realms.keys().valid(capitalSubject)
                || realms.keys().kind(capitalSubject) != RealmKeyTable.SETTLEMENT) {
            return ProjectResult.NONE;
        }
        UUID uuid = realms.keys().uuid(capitalSubject);
        Village village = villages.find(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
        if (village == null || village.getId() == null || village.isPlayerControlled()
                || village.getOwnerUUID() != null || !village.isActive()
                || village.getPendingProject() != null || !village.getControlledQueue().isEmpty()) {
            return ProjectResult.NONE;
        }
        ServerLevel level = villages.level(village.getId());
        if (level == null || !level.isLoaded(village.getCenter())) return ProjectResult.NONE;
        VillageType villageType = ModCultures.getVillageType(village.getVillageTypeId());
        if (villageType == null) return ProjectResult.NONE;

        Candidate best = null;
        int visited = 0;
        for (VillageType.LayoutSlot slot : villageType.layout()) {
            if (visited++ >= maximumProjectCandidates || slot == null || slot.plan() == null) break;
            BuildingPlanSet planSet = ModCultures.getBuildingPlanSet(slot.plan());
            if (planSet == null || planSet.isTownHall() || reachedMaximum(village, planSet)) continue;
            int score = buildingPolicy.score(decision.priority(), planSet, slot);
            if (score <= 0 || best != null && score <= best.score()) continue;
            String variant = planSet.variants().keySet().stream()
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            if (variant == null) continue;
            BuildingPlanSet.LevelDef levelDef = planSet.getLevel(variant, 0);
            if (levelDef == null) continue;
            BuildingPlan plan = ModCultures.getBuildingPlan(levelDef.planId());
            if (plan == null) continue;
            best = new Candidate(planSet, plan, slot, variant, score);
        }
        if (best == null) return ProjectResult.NONE;

        long cost = buildingPolicy.investmentCost(
                decision.priority(),
                best.planSet(),
                decision.investmentPermille(),
                baseInvestmentCost);
        if (cost <= 0L || realms.treasury(realmId) < cost) return ProjectResult.NONE;
        PlacedLocation location = VillageGrowthManager.findLocationForNewBuilding(
                level,
                village,
                best.planSet(),
                best.plan(),
                best.slot());
        if (location == null) return ProjectResult.NONE;
        ControlledQueuedProject project = new ControlledQueuedProject(
                best.planSet().id(),
                best.variant(),
                0,
                location);
        if (!village.enqueueControlledProject(project)) return ProjectResult.NONE;
        realms.adjustTreasury(realmId, -cost);
        realms.markChanged();
        village.setNoProjectsLeftUntil(0L);
        VillageGrowthManager.evaluateGrowth(level, village);
        village.recordEvent(
                level,
                "millenaire_armies.state_project."
                        + decision.priority().name().toLowerCase()
                        + ':' + best.planSet().id());
        village.markDirty();
        VillageSavedData.get(level).setDirty();
        return new ProjectResult(true, cost);
    }

    private static boolean reachedMaximum(Village village, BuildingPlanSet planSet) {
        int maximum = planSet.maxCount();
        if (maximum <= 0) return false;
        int count = 0;
        for (BuildingInstance building : village.getBuildings()) {
            if (building != null && planSet.id().equals(building.getPlanSetId()) && ++count >= maximum) {
                return true;
            }
        }
        return false;
    }

    private void clearSnapshot() {
        for (int row = 0; row < realmCount; row++) {
            realmIds[row] = 0L;
            settlementCounts[row] = 0;
            populations[row] = 0L;
            foodStocks[row] = 0L;
            securitySums[row] = 0L;
            marketSums[row] = 0L;
            productivitySums[row] = 0L;
            damageSums[row] = 0L;
            atWar[row] = false;
            warOpponents[row] = 0L;
        }
        realmCount = 0;
    }

    private static int administrativeReserve(Constitution constitution, int settlements) {
        if (constitution == null) return Math.max(0, 600 - Math.max(0, settlements - 1) * 50);
        int institutional = (constitution.centralization()
                + constitution.bureaucracy()
                + constitution.legitimacy()) / 3;
        return clamp(institutional - Math.max(0, settlements - 1) * 45);
    }

    private long historicalMilliYear(long gameTime) {
        long whole = gameTime / historicalYearTicks;
        long remainder = gameTime % historicalYearTicks;
        return saturatedAdd(
                saturatedMultiply(whole, 1000L),
                remainder * 1000L / historicalYearTicks);
    }

    private static int average(long sum, int count) {
        return count <= 0 ? 0 : clampLong(sum / count);
    }

    private static int ratio(long numerator, long denominator, int scale) {
        if (numerator <= 0L || denominator <= 0L || scale <= 0) return 0;
        long whole = numerator / denominator;
        long remainder = numerator % denominator;
        long value = saturatedAdd(
                saturatedMultiply(whole, scale),
                remainder * scale / denominator);
        return clampLong(value);
    }

    private static int defaultIndex(int value, int fallback) {
        return value <= 0 ? fallback : clamp(value);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(1000, value));
    }

    private static int clampLong(long value) {
        return (int) Math.max(0L, Math.min(1000L, value));
    }

    private static long saturatedAdd(long left, long right) {
        if (right <= 0L) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long saturatedMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) return 0L;
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    public long snapshotCount() { return snapshotCount; }
    public long evaluatedRealmCount() { return evaluatedRealmCount; }
    public long decisionCount() { return decisionCount; }
    public long priorityChangeCount() { return priorityChangeCount; }
    public long projectQueuedCount() { return projectQueuedCount; }
    public long projectUnavailableCount() { return projectUnavailableCount; }
    public long investmentSpent() { return investmentSpent; }
    public long truceRequestCount() { return truceRequestCount; }
    public int lastTickWorkUnits() { return lastTickWorkUnits; }

    public void logShutdownMetrics() {
        LOGGER.info(
                "[BANNEROK_REALM_STATE_DECISION_METRICS] snapshots={} evaluated={} decisions={} priority_changes={} projects_queued={} projects_unavailable={} investment_spent={} truce_requests={} pending={} last_work={}",
                snapshotCount,
                evaluatedRealmCount,
                decisionCount,
                priorityChangeCount,
                projectQueuedCount,
                projectUnavailableCount,
                investmentSpent,
                truceRequestCount,
                evaluating ? realmCount - cursor : 0,
                lastTickWorkUnits);
    }

    private record Candidate(
            BuildingPlanSet planSet,
            BuildingPlan plan,
            VillageType.LayoutSlot slot,
            String variant,
            int score) {}

    private record ProjectResult(boolean queued, long spent) {
        private static final ProjectResult NONE = new ProjectResult(false, 0L);
    }
}
