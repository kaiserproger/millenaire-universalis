package ru.kaiserroman.millenairearmies.integration.millenaire;

import com.mojang.logging.LogUtils;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.village.Village;
import org.slf4j.Logger;
import ru.kaiserroman.millenaire.simulation.MigrationReason;
import ru.kaiserroman.millenaire.simulation.PackedSettlementSimulationState;
import ru.kaiserroman.millenaire.simulation.RegionalShockPropagationPolicy;
import ru.kaiserroman.millenaire.simulation.SettlementStatus;
import ru.kaiserroman.millenaire.simulation.ShockPropagationDecision;
import ru.kaiserroman.millenaire.simulation.ShockPropagationInputs;
import ru.kaiserroman.millenaire.simulation.ShockType;
import ru.kaiserroman.millenaire.simulation.WorldShock;
import ru.kaiserroman.millenaire.simulation.WorldSimulationEngine;
import ru.kaiserroman.millenairearmies.persistence.SimulationKeyTable;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;

/**
 * Connects pure Simulation shocks and migration to the concrete Millenaire settlement index. The
 * service never force-loads chunks or moves physical villagers: it uses indexed village geometry
 * when available and the persisted regional grid otherwise, then commits only virtual Simulation
 * state and auditable events.
 */
public final class MillenaireRegionalDynamicsService {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final MillenaireVillageIndex villages;
    private final SimulationSavedData data;
    private final SimulationKeyTable keys;
    private final PackedSettlementSimulationState state;
    private final WorldSimulationEngine engine;
    private final RegionalShockPropagationPolicy propagationPolicy =
            new RegionalShockPropagationPolicy();
    private final boolean propagationEnabled;
    private final boolean refugeeMigrationEnabled;
    private final boolean endogenousShocksEnabled;
    private final int maximumPropagationTargets;
    private final int maximumRefugeeFlows;
    private final int maximumInteractionDistanceBlocks;
    private final int regionSizeBlocks;
    private final int evaluationIntervalCycles;
    private final int evaluationRowsPerTick;
    private final int maximumEndogenousShocksPerSweep;
    private final long cycleIntervalTicks;

    private final int[] selectedRows;
    private final int[] selectedPressures;
    private final int[] selectedContacts;
    private final int[] selectedDistances;
    private final int[] selectedBorders;

    private int evaluationCursor;
    private int generatedThisSweep;
    private boolean evaluating;
    private long nextEvaluationCycle;
    private long acceptedShockCount;
    private long rejectedShockCount;
    private long propagatedShockCount;
    private long refugeeFlowCount;
    private long relocatedPopulation;
    private long endogenousShockCount;

    public MillenaireRegionalDynamicsService(
            MillenaireVillageIndex villages,
            SimulationSavedData data,
            WorldSimulationEngine engine,
            boolean propagationEnabled,
            boolean refugeeMigrationEnabled,
            boolean endogenousShocksEnabled,
            int maximumPropagationTargets,
            int maximumRefugeeFlows,
            int maximumInteractionDistanceBlocks,
            int regionSizeBlocks,
            int evaluationIntervalCycles,
            int evaluationRowsPerTick,
            int maximumEndogenousShocksPerSweep,
            long cycleIntervalTicks) {
        if (villages == null || data == null || engine == null) {
            throw new NullPointerException("regional Simulation dependency");
        }
        if (maximumPropagationTargets <= 0 || maximumRefugeeFlows <= 0
                || maximumInteractionDistanceBlocks <= 0 || regionSizeBlocks <= 0
                || evaluationIntervalCycles <= 0 || evaluationRowsPerTick <= 0
                || maximumEndogenousShocksPerSweep <= 0 || cycleIntervalTicks <= 0L) {
            throw new IllegalArgumentException("Regional Simulation bounds must be positive");
        }
        this.villages = villages;
        this.data = data;
        this.keys = data.keys();
        this.state = data.state();
        this.engine = engine;
        this.propagationEnabled = propagationEnabled;
        this.refugeeMigrationEnabled = refugeeMigrationEnabled;
        this.endogenousShocksEnabled = endogenousShocksEnabled;
        this.maximumPropagationTargets = maximumPropagationTargets;
        this.maximumRefugeeFlows = maximumRefugeeFlows;
        this.maximumInteractionDistanceBlocks = maximumInteractionDistanceBlocks;
        this.regionSizeBlocks = regionSizeBlocks;
        this.evaluationIntervalCycles = evaluationIntervalCycles;
        this.evaluationRowsPerTick = evaluationRowsPerTick;
        this.maximumEndogenousShocksPerSweep = maximumEndogenousShocksPerSweep;
        this.cycleIntervalTicks = cycleIntervalTicks;
        selectedRows = new int[maximumPropagationTargets];
        selectedPressures = new int[maximumPropagationTargets];
        selectedContacts = new int[maximumPropagationTargets];
        selectedDistances = new int[maximumPropagationTargets];
        selectedBorders = new int[maximumPropagationTargets];
    }

    /** Adds one persisted shock and immediately performs one bounded regional spillover pass. */
    public boolean applyShock(WorldShock shock, long gameTime) {
        if (shock == null) throw new NullPointerException("shock");
        if (gameTime < 0L) throw new IllegalArgumentException("Negative gameTime");
        if (!engine.addShock(shock, gameTime)) {
            rejectedShockCount++;
            return false;
        }
        acceptedShockCount++;
        int propagated = propagationEnabled ? propagate(shock, gameTime) : 0;
        long moved = refugeeMigrationEnabled ? relocateRefugees(shock, gameTime) : 0L;
        data.markChanged();
        if (propagated != 0 || moved != 0L) {
            LOGGER.info(
                    "[BANNEROK_REGIONAL_DYNAMICS] type={} source_settlement={} source_region={} magnitude={} propagated={} refugee_population={} game_time={}",
                    shock.type(),
                    shock.targetSettlementId(),
                    shock.targetRegionKey(),
                    shock.magnitude(),
                    propagated,
                    moved,
                    gameTime);
        }
        return true;
    }

    /** Evaluates bounded slices of the latest Millenaire observations for endogenous crises. */
    public int tick(long gameTime) {
        if (gameTime < 0L) throw new IllegalArgumentException("Negative gameTime");
        if (!endogenousShocksEnabled) return 0;
        long cycle = gameTime / cycleIntervalTicks;
        if (!evaluating) {
            if (cycle < nextEvaluationCycle) return 0;
            evaluating = true;
            evaluationCursor = 0;
            generatedThisSweep = 0;
            nextEvaluationCycle = saturatedAdd(cycle, evaluationIntervalCycles);
        }
        int size = state.size();
        if (size == 0) {
            evaluating = false;
            return 0;
        }
        int work = 0;
        int budget = Math.min(evaluationRowsPerTick, size - Math.min(size, evaluationCursor));
        while (work < budget && evaluationCursor < size) {
            int row = evaluationCursor++;
            work++;
            if (generatedThisSweep >= maximumEndogenousShocksPerSweep) continue;
            WorldShock shock = endogenousShock(row, cycle);
            if (shock == null || hasActiveSettlementShock(
                    shock.type(), shock.targetSettlementId(), cycle)) {
                continue;
            }
            if (applyShock(shock, gameTime)) {
                generatedThisSweep++;
                endogenousShockCount++;
                LOGGER.info(
                        "[BANNEROK_ENDOGENOUS_SHOCK] type={} settlement={} region={} magnitude={} cycles={} simulation_cycle={}",
                        shock.type(),
                        shock.targetSettlementId(),
                        state.regionKeyAt(row),
                        shock.magnitude(),
                        shock.remainingCycles(),
                        cycle);
            }
        }
        if (evaluationCursor >= size) evaluating = false;
        return work;
    }

    private WorldShock endogenousShock(int row, long cycle) {
        if (!state.physicallyPresentAt(row)
                || state.statusAt(row) == SettlementStatus.RUINED
                || state.statusAt(row) == SettlementStatus.ABANDONED) {
            return null;
        }
        long settlementId = state.settlementIdAt(row);
        long population = state.populationAt(row);
        long housing = state.housingCapacityAt(row);
        int density = housing <= 0L ? 1000 : ratioPermille(population, housing, 2000);
        int security = state.securityAt(row);
        int damage = state.damageAt(row);
        if (population >= 40L && density >= 900 && security < 550
                && (damage >= 250 || population >= 80L)) {
            int magnitude = clamp(
                    300 + (density - 850) / 2 + (550 - security) / 2 + damage / 3);
            return new WorldShock(
                    ShockType.EPIDEMIC,
                    settlementId,
                    0L,
                    0,
                    Math.max(250, magnitude),
                    3 + Math.max(250, magnitude) / 250);
        }

        long foodTarget = population > Long.MAX_VALUE / 3L
                ? Long.MAX_VALUE
                : Math.max(1L, population * 3L / 2L);
        long foodStock = state.stockAt(row, 0);
        int foodCoverage = ratioPermille(foodStock, foodTarget, 2000);
        long foodFlow = state.netFlowAt(row, 0);
        if (population > 0L && foodCoverage < 350 && foodFlow < 0L) {
            int flowPressure = ratioPermille(
                    foodFlow == Long.MIN_VALUE ? Long.MAX_VALUE : -foodFlow,
                    foodTarget,
                    1000);
            int magnitude = clamp(
                    350 + (350 - foodCoverage) + flowPressure / 2 + damage / 4);
            return new WorldShock(
                    ShockType.HARVEST_FAILURE,
                    settlementId,
                    0L,
                    0,
                    Math.max(300, magnitude),
                    4 + Math.max(300, magnitude) / 250);
        }

        int positiveFlows = 0;
        for (int commodity = 0; commodity < state.commodityCount(); commodity++) {
            if (state.netFlowAt(row, commodity) > 0L) positiveFlows++;
        }
        if (state.marketAccessAt(row) >= 850
                && state.productivityAt(row) >= 700
                && state.stabilityAt(row) >= 650
                && positiveFlows >= Math.max(2, state.commodityCount() / 3)) {
            int magnitude = clamp(
                    250
                            + (state.marketAccessAt(row) - 800)
                            + (state.productivityAt(row) - 650) / 2
                            + positiveFlows * 20);
            return new WorldShock(
                    ShockType.TRADE_BOOM,
                    settlementId,
                    0L,
                    0,
                    Math.max(250, magnitude),
                    3 + Math.max(250, magnitude) / 300);
        }
        if (state.educationAt(row) >= 850
                && state.marketAccessAt(row) >= 700
                && state.productivityAt(row) >= 650) {
            int magnitude = clamp(
                    200
                            + (state.educationAt(row) - 800)
                            + (state.marketAccessAt(row) - 650) / 2);
            return new WorldShock(
                    ShockType.TECHNOLOGY_DIFFUSION,
                    settlementId,
                    0L,
                    0,
                    Math.max(200, magnitude),
                    4);
        }
        return null;
    }

    private boolean hasActiveSettlementShock(ShockType type, long settlementId, long cycle) {
        for (int row = 0; row < data.shocks().size(); row++) {
            if (data.shocks().typeAt(row) == type
                    && data.shocks().targetSettlementIdAt(row) == settlementId
                    && cycle < data.shocks().untilCycleAt(row)) {
                return true;
            }
        }
        return false;
    }

    private int propagate(WorldShock shock, long gameTime) {
        if (shock.remainingCycles() <= 1 || state.size() <= 1) return 0;
        int sourceRow = shock.targetSettlementId() == 0L
                ? -1
                : state.find(shock.targetSettlementId());
        long sourceRegion = shock.targetRegionKey() != 0L
                ? shock.targetRegionKey()
                : sourceRow < 0 ? 0L : state.regionKeyAt(sourceRow);
        int sourceCulture = shock.targetCultureKey() != 0
                ? shock.targetCultureKey()
                : sourceRow < 0 ? 0 : state.cultureKeyAt(sourceRow);
        long sourceRealm = sourceRow < 0 ? 0L : state.realmIdAt(sourceRow);

        int selectedCount = 0;
        for (int row = 0; row < state.size(); row++) {
            if (!eligibleDestination(row)) continue;
            long settlementId = state.settlementIdAt(row);
            long region = state.regionKeyAt(row);
            int culture = state.cultureKeyAt(row);
            if (shock.matches(settlementId, region, culture)) continue;

            int distance = distancePenalty(sourceRow, sourceRegion, row);
            if (distance >= 1000) continue;
            boolean sharedRegion = sourceRegion != 0L && sourceRegion == region;
            boolean sharedCulture = sourceCulture != 0 && sourceCulture == culture;
            int contact = clamp(
                    (1000 - distance) * 55 / 100
                            + state.marketAccessAt(row) * 35 / 100
                            + (sharedCulture ? 100 : 0));
            long targetRealm = state.realmIdAt(row);
            int border = sourceRealm != 0L && sourceRealm == targetRealm
                    ? 100
                    : clamp(350 + state.securityAt(row) / 2);
            ShockPropagationInputs inputs = new ShockPropagationInputs(contact, distance, border, 0);
            ShockPropagationDecision decision = propagationPolicy.evaluate(
                    shock,
                    state.marketAccessAt(row),
                    state.securityAt(row),
                    sharedRegion,
                    sharedCulture,
                    inputs);
            if (!decision.propagates()) continue;
            selectedCount = select(
                    selectedCount,
                    row,
                    decision.pressure(),
                    contact,
                    distance,
                    border);
        }

        int propagated = 0;
        for (int index = 0; index < selectedCount; index++) {
            int row = selectedRows[index];
            if (engine.propagateShock(
                    shock,
                    state.settlementIdAt(row),
                    new ShockPropagationInputs(
                            selectedContacts[index],
                            selectedDistances[index],
                            selectedBorders[index],
                            0),
                    gameTime)) {
                propagated++;
            }
        }
        propagatedShockCount += propagated;
        return propagated;
    }

    private long relocateRefugees(WorldShock shock, long gameTime) {
        MigrationReason reason = migrationReason(shock.type());
        if (reason == null || state.size() <= 1) return 0L;
        int flows = 0;
        long movedTotal = 0L;
        for (int sourceRow = 0;
                sourceRow < state.size() && flows < maximumRefugeeFlows;
                sourceRow++) {
            if (!state.physicallyPresentAt(sourceRow)
                    || state.statusAt(sourceRow) == SettlementStatus.RUINED
                    || state.populationAt(sourceRow) <= 0L
                    || !shock.matches(
                            state.settlementIdAt(sourceRow),
                            state.regionKeyAt(sourceRow),
                            state.cultureKeyAt(sourceRow))) {
                continue;
            }
            int destinationRow = bestDestination(sourceRow, shock);
            if (destinationRow < 0) continue;
            long population = state.populationAt(sourceRow);
            long requested = Math.max(1L, population * shock.magnitude() / 10_000L);
            long moved = engine.relocatePopulation(
                    state.settlementIdAt(sourceRow),
                    state.settlementIdAt(destinationRow),
                    requested,
                    reason,
                    gameTime);
            if (moved <= 0L) continue;
            flows++;
            movedTotal = saturatedAdd(movedTotal, moved);
        }
        refugeeFlowCount += flows;
        relocatedPopulation = saturatedAdd(relocatedPopulation, movedTotal);
        return movedTotal;
    }

    private int bestDestination(int sourceRow, WorldShock shock) {
        int bestRow = -1;
        long bestScore = Long.MIN_VALUE;
        long sourceRegion = state.regionKeyAt(sourceRow);
        int sourceCulture = state.cultureKeyAt(sourceRow);
        long sourceRealm = state.realmIdAt(sourceRow);
        for (int row = 0; row < state.size(); row++) {
            if (row == sourceRow || !eligibleDestination(row)) continue;
            if (shock.matches(
                    state.settlementIdAt(row),
                    state.regionKeyAt(row),
                    state.cultureKeyAt(row))) {
                continue;
            }
            long headroom = Math.max(0L, state.housingCapacityAt(row) - state.populationAt(row));
            if (headroom == 0L) continue;
            int distance = distancePenalty(sourceRow, sourceRegion, row);
            if (distance >= 1000) continue;
            long score = state.attractivenessAt(row) * 2L
                    + state.marketAccessAt(row)
                    + state.securityAt(row)
                    + Math.min(500L, headroom * 10L)
                    + (state.cultureKeyAt(row) == sourceCulture ? 350L : 0L)
                    + (sourceRealm != 0L && state.realmIdAt(row) == sourceRealm ? 180L : 0L)
                    - distance * 2L;
            if (score > bestScore
                    || score == bestScore
                            && (bestRow < 0
                                    || state.settlementIdAt(row) < state.settlementIdAt(bestRow))) {
                bestScore = score;
                bestRow = row;
            }
        }
        return bestRow;
    }

    private boolean eligibleDestination(int row) {
        SettlementStatus status = state.statusAt(row);
        return state.physicallyPresentAt(row)
                && status != SettlementStatus.RUINED
                && status != SettlementStatus.ABANDONED
                && state.attractivenessAt(row) >= 250;
    }

    private int select(
            int selectedCount,
            int row,
            int pressure,
            int contact,
            int distance,
            int border) {
        int target;
        if (selectedCount < maximumPropagationTargets) {
            target = selectedCount++;
        } else {
            target = 0;
            for (int index = 1; index < selectedCount; index++) {
                if (selectedPressures[index] < selectedPressures[target]) target = index;
            }
            if (pressure <= selectedPressures[target]) return selectedCount;
        }
        selectedRows[target] = row;
        selectedPressures[target] = pressure;
        selectedContacts[target] = contact;
        selectedDistances[target] = distance;
        selectedBorders[target] = border;
        return selectedCount;
    }

    private int distancePenalty(int sourceRow, long sourceRegion, int targetRow) {
        PhysicalLocation source = sourceRow < 0 ? null : physicalLocation(sourceRow);
        PhysicalLocation target = physicalLocation(targetRow);
        if (source != null && target != null) {
            if (!source.level.dimension().equals(target.level.dimension())) return 1000;
            long dx = (long) source.position.getX() - target.position.getX();
            long dz = (long) source.position.getZ() - target.position.getZ();
            double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
            return distancePenalty(distance);
        }
        long targetRegion = state.regionKeyAt(targetRow);
        if (sourceRegion == 0L || targetRegion == 0L
                || regionDimension(sourceRegion) != regionDimension(targetRegion)) {
            return 1000;
        }
        long dx = (long) regionX(sourceRegion) - regionX(targetRegion);
        long dz = (long) regionZ(sourceRegion) - regionZ(targetRegion);
        double distance = Math.sqrt((double) dx * dx + (double) dz * dz) * regionSizeBlocks;
        return distancePenalty(distance);
    }

    private int distancePenalty(double distanceBlocks) {
        if (distanceBlocks >= maximumInteractionDistanceBlocks) return 1000;
        return clamp((int) Math.round(distanceBlocks * 1000.0 / maximumInteractionDistanceBlocks));
    }

    private PhysicalLocation physicalLocation(int row) {
        long settlementId = state.settlementIdAt(row);
        if (!keys.validSettlement(settlementId)) return null;
        UUID uuid = keys.settlement(settlementId);
        Village village = villages.find(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
        if (village == null || village.getId() == null || village.getCenter() == null) return null;
        ServerLevel level = villages.level(village.getId());
        return level == null ? null : new PhysicalLocation(level, village.getCenter());
    }

    private static MigrationReason migrationReason(ShockType type) {
        return switch (type) {
            case WAR_DEVASTATION -> MigrationReason.WAR;
            case HARVEST_FAILURE -> MigrationReason.FAMINE;
            case EPIDEMIC -> MigrationReason.EPIDEMIC;
            case MIGRATION_WAVE -> MigrationReason.RESETTLEMENT;
            case TRADE_BOOM, TECHNOLOGY_DIFFUSION -> null;
        };
    }

    static int regionDimension(long regionKey) {
        return (int) (regionKey >>> 40);
    }

    static int regionX(long regionKey) {
        return signExtend20((int) (regionKey >>> 20) & 0xfffff);
    }

    static int regionZ(long regionKey) {
        return signExtend20((int) regionKey & 0xfffff);
    }

    private static int signExtend20(int value) {
        return (value & 0x80000) == 0 ? value : value | ~0xfffff;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(1000, value));
    }

    private static int ratioPermille(long value, long base, int maximum) {
        if (value <= 0L) return 0;
        if (base <= 0L || value > Long.MAX_VALUE / 1000L) return maximum;
        return (int) Math.min(maximum, value * 1000L / base);
    }

    private static long saturatedAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    public long acceptedShockCount() { return acceptedShockCount; }
    public long rejectedShockCount() { return rejectedShockCount; }
    public long propagatedShockCount() { return propagatedShockCount; }
    public long refugeeFlowCount() { return refugeeFlowCount; }
    public long relocatedPopulationCount() { return relocatedPopulation; }
    public long endogenousShockCount() { return endogenousShockCount; }
    public boolean isEvaluating() { return evaluating; }

    public void logShutdownMetrics() {
        LOGGER.info(
                "[BANNEROK_REGIONAL_DYNAMICS_METRICS] accepted_shocks={} rejected_shocks={} endogenous_shocks={} propagated_shocks={} refugee_flows={} relocated_population={} evaluating={}",
                acceptedShockCount,
                rejectedShockCount,
                endogenousShockCount,
                propagatedShockCount,
                refugeeFlowCount,
                relocatedPopulation,
                evaluating);
    }

    private record PhysicalLocation(ServerLevel level, BlockPos position) {
    }
}
