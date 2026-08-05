package ru.kaiserroman.millenairearmies.lifecycle;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.millenaire.entity.MillVillager;
import org.slf4j.Logger;
import ru.kaiserroman.millenaire.realm.RealmMilitaryPolicy;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.integration.millenaire.FactionProjectionService;
import ru.kaiserroman.millenairearmies.integration.millenaire.FeudalLeaderProjectionService;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireDynamicTradeService;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireEntityBridge;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireInventorySupplyBridge;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenairePhysicalProjectionService;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireRecruitmentService;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireSettlementEconomyBridge;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireVillageIndex;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireWorldMutationService;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireWorldSimulationBridge;
import ru.kaiserroman.millenairearmies.integration.millenaire.SimulationBattleImpactAdapter;
import ru.kaiserroman.millenairearmies.integration.millenaire.SimulationTradePricePolicy;
import ru.kaiserroman.millenairearmies.network.ServerArmyNetworkService;
import ru.kaiserroman.millenairearmies.network.ServerIntentRouter;
import ru.kaiserroman.millenairearmies.persistence.ArmySavedData;
import ru.kaiserroman.millenairearmies.persistence.PlayerRealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.RealmGovernanceSavedData;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;
import ru.kaiserroman.millenairearmies.server.diplomacy.DiplomacyIntegration;
import ru.kaiserroman.millenairearmies.server.economy.ArmyUpkeepService;
import ru.kaiserroman.millenairearmies.server.economy.SettlementEconomyEngine;
import ru.kaiserroman.millenairearmies.server.execution.ArmyOrderExecutionBridge;
import ru.kaiserroman.millenairearmies.server.execution.OrderExecutionPolicy;
import ru.kaiserroman.millenairearmies.server.execution.PhysicalBattleEventLog;
import ru.kaiserroman.millenairearmies.server.garrison.GarrisonService;
import ru.kaiserroman.millenairearmies.server.integration.ArmyRealmIdentityResolver;
import ru.kaiserroman.millenairearmies.server.integration.CanonicalArmyRealmIdentityResolver;
import ru.kaiserroman.millenairearmies.server.integration.RealmMilitaryAdapter;
import ru.kaiserroman.millenairearmies.server.logistics.StrategicLogisticsEngine;
import ru.kaiserroman.millenairearmies.server.logistics.StrategicSupplyPublisher;
import ru.kaiserroman.millenairearmies.server.realm.AutonomousRealmLifecycleService;
import ru.kaiserroman.millenairearmies.server.realm.CanonicalRealmDiplomacyService;
import ru.kaiserroman.millenairearmies.server.realm.LegacyRealmMirrorService;
import ru.kaiserroman.millenairearmies.server.realm.RealmAdministrationService;
import ru.kaiserroman.millenairearmies.server.realm.FeudalLevyService;
import ru.kaiserroman.millenairearmies.server.realm.RealmEvolutionService;
import ru.kaiserroman.millenairearmies.server.realm.RealmHistoricalService;
import ru.kaiserroman.millenairearmies.server.realm.RealmStateDecisionService;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandService;
import ru.kaiserroman.millenairearmies.server.settlement.PlayerSettlementService;
import ru.kaiserroman.millenairearmies.server.supply.ArmySupplyChestService;
import ru.kaiserroman.millenairearmies.server.telemetry.StrategicPhaseTelemetry;
import ru.kaiserroman.millenairearmies.server.unit.UnitDescriptorCatalog;
import ru.kaiserroman.millenairearmies.server.unit.UnitRoleService;

/**
 * Coordinates the clean public-API bridge between the addon and Millenaire beta.2.
 *
 * <p>No combat, target selection, pathfinding, mixin, or world mutation lives here. The periodic
 * work is a low-frequency index reconciliation plus a bounded strategic logistics stripe.</p>
 */
public final class ArmyLifecycleService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int RECONCILE_INTERVAL_TICKS = 200;

    public enum State {
        CREATED,
        RUNNING,
        STOPPED
    }

    private final MillenaireVillageIndex villageIndex = new MillenaireVillageIndex();
    private final MillenaireEntityBridge entityBridge = new MillenaireEntityBridge();
    private final ArmyCommandService commandService = new ArmyCommandService();
    private final FactionProjectionService factionProjection = new FactionProjectionService(villageIndex);
    private final MillenaireRecruitmentService recruitmentService =
            new MillenaireRecruitmentService(villageIndex, entityBridge, commandService);
    private final DiplomacyIntegration diplomacy = new DiplomacyIntegration();
    private final StrategicPhaseTelemetry phaseTelemetry = new StrategicPhaseTelemetry();
    private ArmyOrderExecutionBridge orderExecution;

    private State state = State.CREATED;
    private MinecraftServer server;
    private ServerArmyNetworkService networkService;
    private StrategicLogisticsEngine logisticsEngine;
    private MillenaireInventorySupplyBridge inventorySupplyBridge;
    private StrategicSupplyPublisher supplyPublisher;
    private SettlementEconomyEngine settlementEconomy;
    private GarrisonService garrisonService;
    private RealmSavedData realmData;
    private SimulationSavedData simulationData;
    private MillenaireDynamicTradeService dynamicTradeService;
    private MillenairePhysicalProjectionService physicalProjectionService;
    private RealmAdministrationService realmAdministrationService;
    private PlayerSettlementService playerSettlementService;
    private AutonomousRealmLifecycleService autonomousRealmLifecycleService;
    private RealmEvolutionService realmEvolutionService;
    private RealmHistoricalService realmHistoricalService;
    private RealmStateDecisionService realmStateDecisionService;
    private CanonicalRealmDiplomacyService canonicalRealmDiplomacyService;
    private CanonicalArmyRealmIdentityResolver canonicalRealmIdentityResolver;
    private PlayerRealmSavedData legacyPlayerRealms;
    private RealmGovernanceSavedData legacyRealmGovernance;
    private LegacyRealmMirrorService legacyRealmMirror;
    private RealmMilitaryAdapter realmMilitaryAdapter;
    private SimulationBattleImpactAdapter simulationBattleImpactAdapter;
    private MillenaireSettlementEconomyBridge settlementEconomyBridge;
    private MillenaireWorldMutationService worldMutationService;
    private MillenaireWorldSimulationBridge worldSimulationBridge;
    private UnitRoleService unitRoleService;
    private FeudalLeaderProjectionService feudalLeaderProjectionService;
    private FeudalLevyService feudalLevyService;
    private ArmyUpkeepService armyUpkeepService;
    private ArmySupplyChestService armySupplyChestService;
    private int ticksUntilReconcile;
    private boolean reconcileRequested;
    private long completedEconomyRevision;

    public boolean start(MinecraftServer startingServer) {
        if (state == State.RUNNING) {
            return false;
        }
        ArmySavedData savedData = ArmySavedData.get(startingServer);
        phaseTelemetry.reset();
        LOGGER.info(
                "[BANNEROK_ARMIES_WORKER_STATUS] requested={} active={} status={} reason=no_profiled_pure_runtime_kernel",
                ArmiesConfig.REQUESTED_STRATEGIC_WORKER_COUNT,
                ArmiesConfig.ACTIVE_STRATEGIC_WORKER_COUNT,
                ArmiesConfig.REQUESTED_STRATEGIC_WORKER_COUNT == 0 ? "BASELINE" : "NOT_APPLICABLE");
        commandService.start(
                startingServer,
                savedData.ecs(),
                savedData.controllers(),
                savedData.dimensions(),
                savedData::markArmyChanged);
        logisticsEngine = new StrategicLogisticsEngine(
                ArmiesConfig.MAX_LOGISTICS_REQUESTS,
                ArmiesConfig.MAX_SUPPLY_KEYS,
                ArmiesConfig.LOGISTICS_EVENT_CAPACITY,
                ArmiesConfig.LOGISTICS_REQUEST_STRIPES,
                ArmiesConfig.LOGISTICS_EVENTS_PER_TICK);
        logisticsEngine.start(savedData.logistics(), savedData.commands(), savedData::setDirty);
        server = startingServer;
        state = State.RUNNING;
        ticksUntilReconcile = RECONCILE_INTERVAL_TICKS;
        long phaseStart = System.nanoTime();
        int villageChanges = villageIndex.reconcile(startingServer);
        phaseTelemetry.record(
                StrategicPhaseTelemetry.MILLENAIRE_CAPTURE,
                System.nanoTime() - phaseStart,
                villageIndex.size());
        phaseStart = System.nanoTime();
        int factionChanges = factionProjection.start(startingServer, savedData.factions(), savedData::setDirty);
        phaseTelemetry.record(
                StrategicPhaseTelemetry.FACTION_PROJECTION,
                System.nanoTime() - phaseStart,
                factionProjection.size());
        realmData = RealmSavedData.get(startingServer);
        legacyPlayerRealms = PlayerRealmSavedData.get(startingServer);
        legacyRealmGovernance = RealmGovernanceSavedData.get(startingServer);
        legacyRealmMirror = new LegacyRealmMirrorService(realmData);
        legacyRealmMirror.reconcile(legacyPlayerRealms, legacyRealmGovernance);
        feudalLeaderProjectionService = new FeudalLeaderProjectionService();
        feudalLeaderProjectionService.start(
                startingServer,
                entityBridge,
                realmData,
                legacyRealmGovernance,
                savedData);
        LOGGER.info(
                "Canonical Realm registry ready: realms={}, members={}, subjects={}, legacy_profiles={}",
                realmData.registry().realmCount(),
                realmData.registry().memberCount(),
                realmData.keys().size(),
                realmData.metadataSize());
        if (ArmiesConfig.WORLD_SIMULATION_ENABLED) {
            simulationData = SimulationSavedData.get(startingServer);
            worldSimulationBridge = new MillenaireWorldSimulationBridge(
                    villageIndex,
                    simulationData,
                    realmData,
                    ArmiesConfig.WORLD_SIMULATION_SCAN_ROWS_PER_TICK,
                    ArmiesConfig.WORLD_SIMULATION_REGION_SIZE_BLOCKS);
            if (ArmiesConfig.WORLD_MUTATION_ENABLED) {
                worldMutationService = new MillenaireWorldMutationService(
                        villageIndex,
                        simulationData,
                        realmData,
                        () -> reconcileRequested = true,
                        ArmiesConfig.WORLD_MUTATION_FOUNDING_ENABLED,
                        ArmiesConfig.WORLD_MUTATION_ABANDONMENT_ENABLED,
                        ArmiesConfig.WORLD_MUTATION_REFUGEES_PER_EVENT,
                        ArmiesConfig.WORLD_MUTATION_EVENTS_PER_TICK,
                        ArmiesConfig.WORLD_MUTATION_SITE_ATTEMPTS,
                        ArmiesConfig.WORLD_MUTATION_MAX_ATTEMPTS,
                        ArmiesConfig.WORLD_MUTATION_RETRY_TICKS,
                        ArmiesConfig.WORLD_MUTATION_DEFER_TICKS,
                        ArmiesConfig.WORLD_MUTATION_MAX_DEFER_RUNS,
                        ArmiesConfig.WORLD_MUTATION_MIN_FOUNDING_DISTANCE,
                        ArmiesConfig.WORLD_MUTATION_MAX_FOUNDING_DISTANCE,
                        ArmiesConfig.WORLD_MUTATION_MIN_VILLAGE_DISTANCE,
                        ArmiesConfig.WORLD_MUTATION_PLAYER_SAFETY_RADIUS,
                        ArmiesConfig.WORLD_MUTATION_FOUNDING_COMPLETION,
                        ArmiesConfig.WORLD_SIMULATION_REGION_SIZE_BLOCKS);
            }
            if (ArmiesConfig.DYNAMIC_TRADE_PRICES_ENABLED) {
                dynamicTradeService = new MillenaireDynamicTradeService(
                        simulationData,
                        new SimulationTradePricePolicy(
                                ArmiesConfig.DYNAMIC_TRADE_MIN_MULTIPLIER_PERMILLE,
                                ArmiesConfig.DYNAMIC_TRADE_MAX_MULTIPLIER_PERMILLE,
                                ArmiesConfig.DYNAMIC_TRADE_MAX_PRICE));
            }
            if (ArmiesConfig.WORLD_PHYSICAL_PROJECTION_ENABLED) {
                physicalProjectionService = new MillenairePhysicalProjectionService(
                        villageIndex,
                        simulationData,
                        realmData,
                        ArmiesConfig.WORLD_PHYSICAL_PROJECTION_INTERVAL_TICKS,
                        ArmiesConfig.WORLD_PHYSICAL_PROJECTION_VILLAGES_PER_TICK,
                        ArmiesConfig.WORLD_PHYSICAL_PROJECTION_STOCK_CAP,
                        ArmiesConfig.WORLD_PHYSICAL_PROJECTION_ITEMS_PER_SWEEP,
                        ArmiesConfig.WORLD_PHYSICAL_PROJECTION_CATALOG_ITEMS,
                        ArmiesConfig.WORLD_PHYSICAL_PROJECTION_RELATIONS_PER_VILLAGE,
                        ArmiesConfig.WORLD_PHYSICAL_PROJECTION_DECLINE_PAUSE_YEARS,
                        ArmiesConfig.WORLD_PHYSICAL_PROJECTION_RUIN_PAUSE_YEARS,
                        ArmiesConfig.HISTORICAL_YEAR_TICKS,
                        ArmiesConfig.WORLD_SIMULATION_INTERVAL_TICKS,
                        ArmiesConfig.WORLD_PHYSICAL_PROJECTION_PLAYER_VILLAGES);
            }
            if (ArmiesConfig.AUTONOMOUS_REALMS_ENABLED) {
                autonomousRealmLifecycleService = new AutonomousRealmLifecycleService(
                        realmData,
                        simulationData,
                        RealmSavedData.MAX_REALMS,
                        ArmiesConfig.MAX_SETTLEMENTS,
                        ArmiesConfig.AUTONOMOUS_REALM_INTERVAL_CYCLES,
                        ArmiesConfig.AUTONOMOUS_REALM_TRANSITIONS_PER_TICK,
                        ArmiesConfig.REALM_CITY_STATE_MINIMUM_POPULATION,
                        ArmiesConfig.REALM_CITY_STATE_FORMATION_YEARS,
                        ArmiesConfig.REALM_REGIONAL_FORMATION_YEARS,
                        ArmiesConfig.REALM_COLLAPSE_DISSOLUTION_YEARS,
                        ArmiesConfig.REALM_CAPITAL_LOSS_DISSOLUTION_YEARS,
                        ArmiesConfig.REALM_SECESSION_ENABLED,
                        ArmiesConfig.REALM_SECESSION_MINIMUM_PHASE_YEARS,
                        ArmiesConfig.REALM_SECESSION_COOLDOWN_YEARS,
                        ArmiesConfig.WORLD_SIMULATION_INTERVAL_TICKS,
                        ArmiesConfig.HISTORICAL_YEAR_TICKS);
            }
            if (ArmiesConfig.REALM_EVOLUTION_ENABLED) {
                realmEvolutionService = new RealmEvolutionService(
                        realmData,
                        simulationData,
                        RealmSavedData.MAX_REALMS,
                        ArmiesConfig.MAX_SETTLEMENTS,
                        ArmiesConfig.REALM_EVOLUTION_INTERVAL_CYCLES,
                        ArmiesConfig.REALM_EVOLUTION_REALMS_PER_TICK,
                        ArmiesConfig.REALM_EVOLUTION_REFORM_STEP,
                        ArmiesConfig.WORLD_SIMULATION_INTERVAL_TICKS,
                        ArmiesConfig.HISTORICAL_YEAR_TICKS);
            }
            if (ArmiesConfig.REALM_HISTORICAL_ENABLED) {
                realmHistoricalService = new RealmHistoricalService(
                        realmData,
                        simulationData,
                        RealmSavedData.MAX_REALMS,
                        ArmiesConfig.MAX_SETTLEMENTS,
                        ArmiesConfig.REALM_HISTORICAL_REALMS_PER_TICK,
                        ArmiesConfig.REALM_CITY_STATE_MINIMUM_POPULATION,
                        ArmiesConfig.REALM_HISTORICAL_EVALUATION_TICKS,
                        ArmiesConfig.HISTORICAL_YEAR_TICKS,
                        ArmiesConfig.WORLD_SIMULATION_INTERVAL_TICKS);
            }
            if (ArmiesConfig.CANONICAL_REALM_DIPLOMACY_ENABLED) {
                canonicalRealmDiplomacyService = new CanonicalRealmDiplomacyService(
                        realmData,
                        simulationData,
                        RealmSavedData.MAX_REALMS,
                        ArmiesConfig.MAX_SETTLEMENTS,
                        RealmSavedData.MAX_RELATIONS,
                        ArmiesConfig.CANONICAL_REALM_DIPLOMACY_INTERVAL_CYCLES,
                        ArmiesConfig.CANONICAL_REALM_DIPLOMACY_RELATIONS_PER_TICK,
                        ArmiesConfig.CANONICAL_REALM_TRUCE_CYCLES,
                        ArmiesConfig.WORLD_SIMULATION_INTERVAL_TICKS);
            }
            if (ArmiesConfig.REALM_STATE_DECISIONS_ENABLED) {
                realmStateDecisionService = new RealmStateDecisionService(
                        villageIndex,
                        realmData,
                        simulationData,
                        canonicalRealmDiplomacyService,
                        RealmSavedData.MAX_REALMS,
                        ArmiesConfig.REALM_STATE_DECISION_EVALUATION_TICKS,
                        ArmiesConfig.REALM_STATE_DECISION_REALMS_PER_TICK,
                        ArmiesConfig.REALM_STATE_DECISION_INTERVAL_YEARS,
                        ArmiesConfig.REALM_STATE_DECISION_BASE_INVESTMENT,
                        ArmiesConfig.REALM_STATE_DECISION_PROJECT_CANDIDATES,
                        ArmiesConfig.HISTORICAL_YEAR_TICKS,
                        ArmiesConfig.WORLD_SIMULATION_INTERVAL_TICKS);
            }
            LOGGER.info(
                    "Millenaire Simulation enabled: demography, productivity, markets, dynamic trade={}, world mutation={}, autonomous Realms={}, Realm evolution={}, historical eras={} and canonical diplomacy={}",
                    dynamicTradeService == null ? "disabled" : "enabled",
                    worldMutationService == null ? "disabled (opt-in)" : "enabled",
                    autonomousRealmLifecycleService == null ? "disabled" : "enabled",
                    realmEvolutionService == null ? "disabled" : "enabled",
                    realmHistoricalService == null ? "disabled" : "enabled",
                    canonicalRealmDiplomacyService == null ? "disabled" : "enabled");
        } else {
            LOGGER.warn("Millenaire Simulation is disabled by config; physical village indexing remains available to Armies only");
        }
        realmAdministrationService = new RealmAdministrationService(
                realmData,
                simulationData,
                legacyPlayerRealms,
                legacyRealmGovernance);
        playerSettlementService = new PlayerSettlementService(
                villageIndex,
                realmData,
                simulationData,
                realmAdministrationService);
        playerSettlementService.start(startingServer);
        if (ArmiesConfig.LOGISTICS_INVENTORY_PROJECTION_ENABLED) {
            settlementEconomy = new SettlementEconomyEngine(
                    savedData.settlementEconomy(),
                    savedData::setDirty,
                    ArmiesConfig.SETTLEMENT_ECONOMY_INTERVAL_TICKS,
                    ArmiesConfig.SETTLEMENT_ECONOMY_ROWS_PER_TICK,
                    ArmiesConfig.SETTLEMENT_SHIPMENTS_PER_TICK,
                    ArmiesConfig.SETTLEMENT_ROUTES_PER_TICK,
                    ArmiesConfig.MAX_SETTLEMENTS,
                    ArmiesConfig.MAX_SETTLEMENT_SHIPMENTS,
                    ArmiesConfig.SETTLEMENT_MAX_ROUTE_BLOCKS);
            settlementEconomyBridge = new MillenaireSettlementEconomyBridge(
                    villageIndex,
                    factionProjection,
                    savedData.dimensions(),
                    savedData.items(),
                    settlementEconomy,
                    ArmiesConfig.SETTLEMENT_SCAN_ROWS_PER_TICK);
            // Commodity dictionary interning and persisted slot binding happen in the bridge
            // constructor, before the first village row can otherwise mark SavedData dirty.
            savedData.setDirty();
            logisticsEngine.installSupplyMutationSink(settlementEconomy);
            inventorySupplyBridge = new MillenaireInventorySupplyBridge(
                    villageIndex,
                    factionProjection,
                    savedData.dimensions(),
                    savedData.items(),
                    ArmiesConfig.MAX_SUPPLY_KEYS,
                    settlementEconomy);
            supplyPublisher = new StrategicSupplyPublisher(
                    logisticsEngine,
                    savedData.logistics(),
                    inventorySupplyBridge,
                    ArmiesConfig.MAX_SUPPLY_KEYS,
                    ArmiesConfig.LOGISTICS_PUBLISHER_REQUEST_ROWS_PER_TICK,
                    ArmiesConfig.LOGISTICS_PUBLISHER_KEYS_PER_TICK,
                    ArmiesConfig.LOGISTICS_PUBLISHER_SWEEP_TICKS);
            LOGGER.info(
                    "Settlement economy enabled: bounded initial Millenaire inventory scan, persisted shipment WAL, reserves and same-faction coarse trade routes are active");
        } else {
            LOGGER.warn("Millenaire inventory projection was explicitly disabled; settlement economy, reserve-aware recruitment and physical inventory scans are inactive");
        }
        commandService.installFactionValidator(factionProjection);
        recruitmentService.start(
                startingServer,
                savedData.ecs(),
                savedData.memberships(),
                savedData.commands(),
                savedData.logistics(),
                savedData.garrisons(),
                savedData::markArmyChanged);
        recruitmentService.installFactionPolicy(factionProjection);
        if (settlementEconomy != null) {
            recruitmentService.installSupplyPolicy(settlementEconomy);
        }
        garrisonService = new GarrisonService();
        garrisonService.start(
                startingServer,
                savedData,
                villageIndex,
                factionProjection,
                commandService,
                settlementEconomy);
        armySupplyChestService = new ArmySupplyChestService();
        armySupplyChestService.start(startingServer, savedData, entityBridge);
        if (OrderExecutionPolicy.shouldStart(ArmiesConfig.ORDER_EXECUTION_ENABLED)) {
            orderExecution = new ArmyOrderExecutionBridge();
            orderExecution.start(
                    startingServer,
                    savedData.ecs(),
                    savedData.memberships(),
                    savedData.dimensions(),
                    entityBridge,
                    factionProjection,
                    commandService,
                    savedData.garrisons(),
                    savedData::markArmyChanged,
                    armySupplyChestService);
            if (worldSimulationBridge != null) {
                simulationBattleImpactAdapter = new SimulationBattleImpactAdapter(
                        orderExecution.battleEvents(),
                        savedData.dimensions(),
                        worldSimulationBridge);
            }
        }
        diplomacy.start(startingServer, savedData);
        unitRoleService = new UnitRoleService(
                savedData.memberships(),
                entityBridge,
                UnitDescriptorCatalog.INSTANCE,
                savedData.unitRoles(),
                savedData.memberships().size());
        recruitmentService.installUnitRoleState(savedData.unitRoles());
        recruitmentService.installReleaseListener((unitHandle, uuidMost, uuidLeast) -> {
            if (orderExecution != null) orderExecution.releaseUnit(unitHandle, uuidMost, uuidLeast);
            if (savedData.unitRoles().remove(unitHandle)) savedData.markArmyChanged();
        });
        armyUpkeepService = new ArmyUpkeepService();
        armyUpkeepService.start(startingServer, savedData, recruitmentService);
        feudalLevyService = new FeudalLevyService();
        feudalLevyService.start(
                startingServer,
                villageIndex,
                factionProjection,
                recruitmentService,
                commandService,
                realmData,
                legacyRealmGovernance,
                simulationData,
                () -> reconcileRequested = true);
        networkService = new ServerArmyNetworkService(
                savedData,
                commandService,
                unitRoleService,
                factionProjection,
                villageIndex,
                recruitmentService,
                orderExecution,
                settlementEconomy,
                garrisonService,
                realmData,
                simulationData,
                realmAdministrationService);
        ServerIntentRouter.install(networkService);
        phaseStart = System.nanoTime();
        int entityChanges = entityBridge.discoverLoaded(startingServer, villageIndex);
        entityChanges += entityBridge.reconcile(villageIndex);
        phaseTelemetry.record(
                StrategicPhaseTelemetry.ENTITY_RECONCILE,
                System.nanoTime() - phaseStart,
                entityBridge.size());
        if (orderExecution != null && canonicalRealmDiplomacyService != null) {
            canonicalRealmIdentityResolver = new CanonicalArmyRealmIdentityResolver(
                    savedData.ecs(),
                    savedData.controllers(),
                    savedData.memberships(),
                    savedData.dimensions(),
                    entityBridge,
                    villageIndex,
                    realmData,
                    ArmiesConfig.CANONICAL_REALM_OBJECTIVE_RADIUS);
            canonicalRealmIdentityResolver.reconcile();
            installRealmMilitaryPolicy(
                    canonicalRealmDiplomacyService,
                    canonicalRealmIdentityResolver);
            LOGGER.info(
                    "Canonical Realm military policy installed: unresolved_armies={}",
                    canonicalRealmIdentityResolver.unresolvedArmyCount());
        }
        reconcileRequested = false;
        LOGGER.info(
                "Millenaire strategy indexed {} villages/{} factions and bound {}/{} loaded villagers (initial changes: villages={}, factions={}, entities={})",
                villageIndex.size(),
                factionProjection.size(),
                entityBridge.size() - entityBridge.unresolvedCount(),
                entityBridge.size(),
                villageChanges,
                factionChanges,
                entityChanges);
        return true;
    }

    public void tick(MinecraftServer tickingServer) {
        if (state != State.RUNNING || server != tickingServer) {
            return;
        }
        // This intentionally has no player-count gate: strategic village supply remains alive.
        long gameTime = tickingServer.overworld().getGameTime();
        if (worldSimulationBridge != null) {
            worldSimulationBridge.tick(gameTime);
        }
        if (worldMutationService != null) {
            worldMutationService.tick(gameTime);
        }
        if (physicalProjectionService != null) {
            physicalProjectionService.tick(gameTime);
        }
        if (feudalLeaderProjectionService != null) {
            feudalLeaderProjectionService.tick(gameTime);
        }
        if (settlementEconomyBridge != null) {
            settlementEconomyBridge.tick(gameTime);
            long completed = settlementEconomyBridge.completedRevisionCount();
            if (completed != completedEconomyRevision) {
                completedEconomyRevision = completed;
                supplyPublisher.requestReconcileAll();
            }
            settlementEconomy.tick(gameTime);
        }
        if (supplyPublisher != null) {
            long phaseStart = System.nanoTime();
            supplyPublisher.tick(gameTime);
            phaseTelemetry.record(
                    StrategicPhaseTelemetry.SUPPLY_PUBLISH,
                    System.nanoTime() - phaseStart,
                    supplyPublisher.queuedKeyCount());
        }
        long phaseStart = System.nanoTime();
        logisticsEngine.tick(gameTime);
        phaseTelemetry.record(
                StrategicPhaseTelemetry.LOGISTICS,
                System.nanoTime() - phaseStart,
                logisticsEngine.lastTickWorkUnits());
        phaseStart = System.nanoTime();
        diplomacy.tick(tickingServer);
        phaseTelemetry.record(StrategicPhaseTelemetry.DIPLOMACY, System.nanoTime() - phaseStart, 0);
        recruitmentService.tick(tickingServer);
        if (armyUpkeepService != null) {
            armyUpkeepService.tick(tickingServer);
        }
        if (garrisonService != null) {
            garrisonService.tick(tickingServer);
        }
        if (armySupplyChestService != null) {
            armySupplyChestService.tick(tickingServer);
        }
        if (orderExecution != null) {
            phaseStart = System.nanoTime();
            orderExecution.tick(tickingServer);
            phaseTelemetry.record(
                    StrategicPhaseTelemetry.ORDER_EXECUTION,
                    System.nanoTime() - phaseStart,
                    entityBridge.size());
        }
        if (realmMilitaryAdapter != null) {
            realmMilitaryAdapter.tick(ArmiesConfig.REALM_BATTLE_EVENTS_PER_TICK);
        }
        if (simulationBattleImpactAdapter != null) {
            simulationBattleImpactAdapter.tick(ArmiesConfig.WORLD_SIMULATION_BATTLE_EVENTS_PER_TICK);
        }
        if (realmEvolutionService != null) {
            realmEvolutionService.tick(gameTime);
        }
        if (realmHistoricalService != null) {
            realmHistoricalService.tick(gameTime);
        }
        if (realmStateDecisionService != null) {
            realmStateDecisionService.tick(gameTime);
        }
        if (playerSettlementService != null) {
            playerSettlementService.tick(gameTime);
        }
        if (autonomousRealmLifecycleService != null) {
            autonomousRealmLifecycleService.tick(gameTime);
        }
        if (canonicalRealmDiplomacyService != null) {
            canonicalRealmDiplomacyService.tick(gameTime);
        }
        if (!reconcileRequested && --ticksUntilReconcile > 0) {
            return;
        }

        int oldVillageCount = villageIndex.size();
        int oldLoadedCount = entityBridge.size();
        int oldUnresolvedCount = entityBridge.unresolvedCount();
        phaseStart = System.nanoTime();
        int villageChanges = villageIndex.reconcile(tickingServer);
        phaseTelemetry.record(
                StrategicPhaseTelemetry.MILLENAIRE_CAPTURE,
                System.nanoTime() - phaseStart,
                villageIndex.size());
        phaseStart = System.nanoTime();
        int factionChanges = factionProjection.reconcile();
        phaseTelemetry.record(
                StrategicPhaseTelemetry.FACTION_PROJECTION,
                System.nanoTime() - phaseStart,
                factionProjection.size());
        int realmChanges = legacyRealmMirror == null
                ? 0
                : legacyRealmMirror.reconcile(legacyPlayerRealms, legacyRealmGovernance);
        phaseStart = System.nanoTime();
        int entityChanges = entityBridge.discoverLoaded(tickingServer, villageIndex);
        entityChanges += entityBridge.reconcile(villageIndex);
        phaseTelemetry.record(
                StrategicPhaseTelemetry.ENTITY_RECONCILE,
                System.nanoTime() - phaseStart,
                entityBridge.size());
        int armyRealmChanges = canonicalRealmIdentityResolver == null
                ? 0
                : canonicalRealmIdentityResolver.reconcile();
        if (supplyPublisher != null) {
            supplyPublisher.requestReconcileAll();
        }
        if (settlementEconomyBridge != null) {
            settlementEconomyBridge.requestReconcile();
        }
        if (worldSimulationBridge != null) {
            worldSimulationBridge.requestReconcile();
        }
        if (physicalProjectionService != null) {
            physicalProjectionService.requestReconcile();
        }
        if (garrisonService != null) {
            garrisonService.reconcileAll();
        }
        ticksUntilReconcile = RECONCILE_INTERVAL_TICKS;
        reconcileRequested = false;

        if (villageChanges != 0
                || factionChanges != 0
                || realmChanges != 0
                || armyRealmChanges != 0
                || entityChanges != 0
                || oldVillageCount != villageIndex.size()
                || oldLoadedCount != entityBridge.size()
                || oldUnresolvedCount != entityBridge.unresolvedCount()) {
            LOGGER.debug(
                    "Millenaire strategy reconciled: villages={} (changes={}), factions={} (changes={}), realms={} (changes={}), army_realm_changes={}, loaded villagers={} (unresolved={}, changes={})",
                    villageIndex.size(),
                    villageChanges,
                    factionProjection.size(),
                    factionChanges,
                    realmData == null ? 0 : realmData.registry().realmCount(),
                    realmChanges,
                    armyRealmChanges,
                    entityBridge.size(),
                    entityBridge.unresolvedCount(),
                    entityChanges);
        }
    }

    public void entityJoined(Entity entity, Level level) {
        if (state == State.STOPPED
                || !(entity instanceof MillVillager villager)
                || !(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!entityBridge.onJoin(villager, serverLevel, villageIndex)) {
            // Reconcile on the next post-tick. Do not load saved data from EntityJoinLevelEvent.
            reconcileRequested = true;
        }
        if (orderExecution != null) {
            orderExecution.entityJoined(villager);
        }
    }

    public void entityLeft(Entity entity, Level level) {
        if (level instanceof ServerLevel && entity instanceof MillVillager villager) {
            if (entityBridge.onLeave(villager) && orderExecution != null) {
                orderExecution.entityLeft(villager);
            }
        }
    }

    public void entityDied(Entity entity, Entity source) {
        if (state != State.RUNNING || !(entity instanceof MillVillager villager)) {
            return;
        }
        if (orderExecution != null) {
            orderExecution.entityDied(villager, source);
        }
        recruitmentService.casualty(villager);
    }

    public boolean stop() {
        if (state != State.RUNNING) {
            return false;
        }
        state = State.STOPPED;
        ServerIntentRouter.uninstall(networkService);
        networkService = null;
        if (realmMilitaryAdapter != null) {
            realmMilitaryAdapter.tick(ArmiesConfig.BATTLE_EVENT_CAPACITY);
            realmMilitaryAdapter.clear();
            realmMilitaryAdapter = null;
        }
        if (simulationBattleImpactAdapter != null) {
            simulationBattleImpactAdapter.tick(ArmiesConfig.BATTLE_EVENT_CAPACITY);
            simulationBattleImpactAdapter.logShutdownMetrics();
            simulationBattleImpactAdapter = null;
        }
        if (orderExecution != null) {
            orderExecution.stop(server);
            orderExecution = null;
        }
        if (armySupplyChestService != null) {
            armySupplyChestService.stop(server);
            armySupplyChestService = null;
        }
        if (armyUpkeepService != null) {
            armyUpkeepService.stop(server);
            armyUpkeepService = null;
        }
        if (feudalLevyService != null) {
            feudalLevyService.stop(server);
            feudalLevyService = null;
        }
        recruitmentService.stop(server);
        if (garrisonService != null) {
            garrisonService.stop(server);
            garrisonService = null;
        }
        diplomacy.stop(server);
        if (canonicalRealmDiplomacyService != null) {
            canonicalRealmDiplomacyService.logShutdownMetrics();
            canonicalRealmDiplomacyService = null;
        }
        canonicalRealmIdentityResolver = null;
        if (autonomousRealmLifecycleService != null) {
            autonomousRealmLifecycleService.logShutdownMetrics();
            autonomousRealmLifecycleService = null;
        }
        if (realmEvolutionService != null) {
            realmEvolutionService.logShutdownMetrics();
            realmEvolutionService = null;
        }
        if (realmHistoricalService != null) {
            realmHistoricalService.logShutdownMetrics();
            realmHistoricalService = null;
        }
        if (realmStateDecisionService != null) {
            realmStateDecisionService.logShutdownMetrics();
            realmStateDecisionService = null;
        }
        if (worldMutationService != null) {
            worldMutationService.logShutdownMetrics();
            worldMutationService = null;
        }
        if (physicalProjectionService != null) {
            physicalProjectionService.logShutdownMetrics();
            physicalProjectionService = null;
        }
        if (feudalLeaderProjectionService != null) {
            feudalLeaderProjectionService.stop(server);
            feudalLeaderProjectionService = null;
        }
        if (dynamicTradeService != null) {
            dynamicTradeService.logShutdownMetrics();
            dynamicTradeService = null;
        }
        if (worldSimulationBridge != null) {
            worldSimulationBridge.logShutdownMetrics();
            worldSimulationBridge = null;
        }
        if (realmData != null) {
            LOGGER.info(
                    "[BANNEROK_CANONICAL_REALM_METRICS] realms={} members={} subjects={} metadata={} mirror_reconciles={} primitive_bytes={}",
                    realmData.registry().realmCount(),
                    realmData.registry().memberCount(),
                    realmData.keys().size(),
                    realmData.metadataSize(),
                    legacyRealmMirror == null ? 0L : legacyRealmMirror.reconcileCount(),
                    realmData.registry().estimatedPrimitiveBytes()
                            + realmData.institutions().estimatedPrimitiveBytes()
                            + realmData.lifecycle().estimatedPrimitiveBytes()
                            + realmData.diplomacy().estimatedPrimitiveBytes()
                            + realmData.keys().estimatedPrimitiveBytes());
        }
        if (playerSettlementService != null) {
            LOGGER.info(
                    "[BANNEROK_PLAYER_SETTLEMENT_METRICS] settlements={} migrated={} founded={} manual_queued={} automatic_queued={} rejected={}",
                    playerSettlementService.profiles() == null ? 0 : playerSettlementService.profiles().size(),
                    playerSettlementService.migratedProfileCount(),
                    playerSettlementService.foundedCount(),
                    playerSettlementService.manualProjectCount(),
                    playerSettlementService.automaticProjectCount(),
                    playerSettlementService.rejectedCount());
            playerSettlementService.stop();
        }
        playerSettlementService = null;
        realmAdministrationService = null;
        legacyRealmMirror = null;
        legacyPlayerRealms = null;
        legacyRealmGovernance = null;
        simulationData = null;
        realmData = null;
        if (settlementEconomy != null) {
            LOGGER.info(
                    "[BANNEROK_SETTLEMENT_ECONOMY_METRICS] settlements={} shipments={} created={} delivered={} rolled_back={} rejected_routes={} physical_reconciliation_shortfall={} primitive_bytes={}",
                    settlementEconomy.state().settlementCount(),
                    settlementEconomy.state().shipmentCount(),
                    settlementEconomy.createdShipmentCount(),
                    settlementEconomy.deliveredShipmentCount(),
                    settlementEconomy.rolledBackShipmentCount(),
                    settlementEconomy.rejectedRouteCount(),
                    settlementEconomy.physicalReconciliationShortfall(),
                    settlementEconomy.state().estimatedPrimitiveBytes());
        }
        supplyPublisher = null;
        inventorySupplyBridge = null;
        settlementEconomyBridge = null;
        settlementEconomy = null;
        LOGGER.info(
                "[BANNEROK_ARMIES_PHASE_METRICS] worker_requested={} worker_active={} logistics_calls={} logistics_ns={} logistics_max_ns={} capture_calls={} capture_ns={} projection_calls={} projection_ns={} projection_max_ns={} entity_reconcile_ns={}",
                ArmiesConfig.REQUESTED_STRATEGIC_WORKER_COUNT,
                ArmiesConfig.ACTIVE_STRATEGIC_WORKER_COUNT,
                phaseTelemetry.calls(StrategicPhaseTelemetry.LOGISTICS),
                phaseTelemetry.totalNanos(StrategicPhaseTelemetry.LOGISTICS),
                phaseTelemetry.maxNanos(StrategicPhaseTelemetry.LOGISTICS),
                phaseTelemetry.calls(StrategicPhaseTelemetry.MILLENAIRE_CAPTURE),
                phaseTelemetry.totalNanos(StrategicPhaseTelemetry.MILLENAIRE_CAPTURE),
                phaseTelemetry.calls(StrategicPhaseTelemetry.FACTION_PROJECTION),
                phaseTelemetry.totalNanos(StrategicPhaseTelemetry.FACTION_PROJECTION),
                phaseTelemetry.maxNanos(StrategicPhaseTelemetry.FACTION_PROJECTION),
                phaseTelemetry.totalNanos(StrategicPhaseTelemetry.ENTITY_RECONCILE));
        logisticsEngine.stop();
        logisticsEngine = null;
        unitRoleService = null;
        factionProjection.stop();
        commandService.stop(server);
        server = null;
        reconcileRequested = false;
        ticksUntilReconcile = 0;
        completedEconomyRevision = 0L;
        entityBridge.clear();
        villageIndex.clear();
        return true;
    }

    public State state() {
        return state;
    }

    public MillenaireVillageIndex villageIndex() {
        return villageIndex;
    }

    public MillenaireEntityBridge entityBridge() {
        return entityBridge;
    }

    public ArmyCommandService commandService() {
        return commandService;
    }

    public FactionProjectionService factionProjection() {
        return factionProjection;
    }

    public MillenaireRecruitmentService recruitmentService() {
        return recruitmentService;
    }

    public DiplomacyIntegration diplomacy() {
        return diplomacy;
    }

    public StrategicLogisticsEngine logisticsEngine() {
        return logisticsEngine;
    }

    public StrategicSupplyPublisher supplyPublisher() {
        return supplyPublisher;
    }

    public SettlementEconomyEngine settlementEconomy() {
        return settlementEconomy;
    }

    public MillenaireWorldSimulationBridge worldSimulationBridge() {
        return worldSimulationBridge;
    }

    public MillenaireWorldMutationService worldMutationService() {
        return worldMutationService;
    }

    public boolean tryOpenDynamicTrade(ServerPlayer player, MillVillager villager) {
        return state == State.RUNNING
                && dynamicTradeService != null
                && dynamicTradeService.tryOpen(player, villager);
    }

    public MillenaireDynamicTradeService dynamicTradeService() {
        return dynamicTradeService;
    }

    public RealmSavedData realmData() {
        return realmData;
    }

    public PlayerSettlementService playerSettlementService() {
        return playerSettlementService;
    }

    public RealmEvolutionService realmEvolutionService() {
        return realmEvolutionService;
    }

    public RealmHistoricalService realmHistoricalService() {
        return realmHistoricalService;
    }

    public CanonicalRealmDiplomacyService canonicalRealmDiplomacyService() {
        return canonicalRealmDiplomacyService;
    }

    public CanonicalArmyRealmIdentityResolver canonicalRealmIdentityResolver() {
        return canonicalRealmIdentityResolver;
    }

    public GarrisonService garrisonService() {
        return garrisonService;
    }

    public UnitRoleService unitRoleService() {
        return unitRoleService;
    }

    public ArmyUpkeepService armyUpkeepService() {
        return armyUpkeepService;
    }

    public FeudalLevyService feudalLevyService() {
        return feudalLevyService;
    }

    public ArmyOrderExecutionBridge orderExecution() {
        return orderExecution;
    }

    public PhysicalBattleEventLog battleEvents() {
        return orderExecution == null ? null : orderExecution.battleEvents();
    }

    public void installRealmMilitaryPolicy(
            RealmMilitaryPolicy policy, ArmyRealmIdentityResolver identities) {
        if (state != State.RUNNING || server == null || orderExecution == null) {
            throw new IllegalStateException("Realm military policy requires active physical execution");
        }
        if (!server.isSameThread()) {
            throw new IllegalStateException("Realm military policy must install on the server thread");
        }
        RealmMilitaryAdapter replacement = new RealmMilitaryAdapter(
                policy,
                identities,
                orderExecution.battleEvents(),
                (sourceArmy, targetArmy, sourceFaction, targetFaction) ->
                        factionProjection.isHostile(sourceFaction, targetFaction));
        orderExecution.installHostilityPolicy(replacement);
        realmMilitaryAdapter = replacement;
    }

    public StrategicPhaseTelemetry phaseTelemetry() {
        return phaseTelemetry;
    }
}
