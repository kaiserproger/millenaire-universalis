package ru.kaiserroman.millenairearmies.lifecycle;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import org.millenaire.entity.MillVillager;
import org.slf4j.Logger;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.integration.millenaire.FactionProjectionService;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireEntityBridge;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireInventorySupplyBridge;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireRecruitmentService;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireSettlementEconomyBridge;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireVillageIndex;
import ru.kaiserroman.millenairearmies.network.ServerArmyNetworkService;
import ru.kaiserroman.millenairearmies.network.ServerIntentRouter;
import ru.kaiserroman.millenairearmies.persistence.ArmySavedData;
import ru.kaiserroman.millenairearmies.persistence.PlayerRealmSavedData;
import ru.kaiserroman.millenairearmies.server.diplomacy.DiplomacyIntegration;
import ru.kaiserroman.millenairearmies.server.economy.SettlementEconomyEngine;
import ru.kaiserroman.millenairearmies.server.execution.ArmyOrderExecutionBridge;
import ru.kaiserroman.millenairearmies.server.execution.LoadedUnitPositionProjection;
import ru.kaiserroman.millenairearmies.server.execution.OrderExecutionPolicy;
import ru.kaiserroman.millenairearmies.server.logistics.StrategicLogisticsEngine;
import ru.kaiserroman.millenairearmies.server.logistics.StrategicSupplyPublisher;
import ru.kaiserroman.millenairearmies.server.realm.RealmService;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandService;
import ru.kaiserroman.millenairearmies.server.telemetry.StrategicPhaseTelemetry;
import ru.kaiserroman.millenairearmies.server.unit.UnitDescriptorCatalog;
import ru.kaiserroman.millenairearmies.server.unit.UnitRoleService;

/**
 * Coordinates the clean public-API bridge between the addon and Millenaire beta.2.
 *
 * <p>The coordinator owns no entity AI itself. It wires bounded Millenaire projections, physical
 * order execution, settlement economy, diplomacy and networking on the server thread.</p>
 */
public final class ArmyLifecycleService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int RECONCILE_INTERVAL_TICKS = 200;
    private static final int POSITION_CAPTURE_INTERVAL_TICKS = 20;
    private static final int POSITION_CAPTURE_ROWS = 64;

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
    private final LoadedUnitPositionProjection unitPositionProjection = new LoadedUnitPositionProjection();
    private ArmyOrderExecutionBridge orderExecution;

    private State state = State.CREATED;
    private MinecraftServer server;
    private ServerArmyNetworkService networkService;
    private StrategicLogisticsEngine logisticsEngine;
    private MillenaireInventorySupplyBridge inventorySupplyBridge;
    private StrategicSupplyPublisher supplyPublisher;
    private SettlementEconomyEngine settlementEconomy;
    private MillenaireSettlementEconomyBridge settlementEconomyBridge;
    private RealmService realmService;
    private UnitRoleService unitRoleService;
    private int ticksUntilReconcile;
    private int ticksUntilPositionCapture;
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
        ticksUntilPositionCapture = POSITION_CAPTURE_INTERVAL_TICKS;
        unitPositionProjection.reset();
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
                    "Settlement economy enabled: bounded Millenaire inventory capture, protected reserves and persisted same-faction shipments are active");
        } else {
            LOGGER.warn(
                    "Millenaire inventory projection is disabled; settlement economy and reserve-aware recruitment are inactive");
        }
        realmService = new RealmService(
                startingServer,
                PlayerRealmSavedData.get(startingServer),
                villageIndex,
                settlementEconomy);
        commandService.installFactionValidator(factionProjection);
        commandService.installArmyOrderValidator(realmService);
        recruitmentService.start(
                startingServer,
                savedData.ecs(),
                savedData.memberships(),
                savedData::markArmyChanged);
        recruitmentService.installFactionPolicy(factionProjection);
        if (settlementEconomy != null) {
            recruitmentService.installSupplyPolicy(settlementEconomy);
        }
        if (OrderExecutionPolicy.shouldStart(ArmiesConfig.ORDER_EXECUTION_ENABLED)) {
            orderExecution = new ArmyOrderExecutionBridge();
            orderExecution.start(
                    startingServer,
                    savedData.ecs(),
                    savedData.memberships(),
                    savedData.dimensions(),
                    entityBridge,
                    villageIndex,
                    commandService,
                    savedData::markArmyChanged,
                    realmService);
        }
        diplomacy.start(startingServer, savedData);
        unitRoleService = new UnitRoleService(
                savedData.memberships(),
                entityBridge,
                UnitDescriptorCatalog.INSTANCE,
                savedData.memberships().size());
        networkService = new ServerArmyNetworkService(
                savedData,
                commandService,
                factionProjection,
                villageIndex,
                recruitmentService,
                orderExecution,
                settlementEconomy,
                realmService);
        ServerIntentRouter.install(networkService);
        phaseStart = System.nanoTime();
        int entityChanges = entityBridge.reconcile(villageIndex);
        phaseTelemetry.record(
                StrategicPhaseTelemetry.ENTITY_RECONCILE,
                System.nanoTime() - phaseStart,
                entityBridge.size());
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
        realmService.tick(gameTime);
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
        if (orderExecution != null) {
            phaseStart = System.nanoTime();
            orderExecution.tick(tickingServer);
            phaseTelemetry.record(
                    StrategicPhaseTelemetry.ORDER_EXECUTION,
                    System.nanoTime() - phaseStart,
                    entityBridge.size());
        }
        if (--ticksUntilPositionCapture <= 0) {
            ArmySavedData savedData = ArmySavedData.get(tickingServer);
            if (unitPositionProjection.capture(
                    savedData.ecs(),
                    savedData.memberships(),
                    entityBridge,
                    POSITION_CAPTURE_ROWS)) {
                savedData.markArmyChanged();
            }
            ticksUntilPositionCapture = POSITION_CAPTURE_INTERVAL_TICKS;
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
        phaseStart = System.nanoTime();
        int entityChanges = entityBridge.reconcile(villageIndex);
        phaseTelemetry.record(
                StrategicPhaseTelemetry.ENTITY_RECONCILE,
                System.nanoTime() - phaseStart,
                entityBridge.size());
        if (supplyPublisher != null) {
            supplyPublisher.requestReconcileAll();
        }
        if (settlementEconomyBridge != null) {
            settlementEconomyBridge.requestReconcile();
        }
        ticksUntilReconcile = RECONCILE_INTERVAL_TICKS;
        reconcileRequested = false;

        if (villageChanges != 0
                || factionChanges != 0
                || entityChanges != 0
                || oldVillageCount != villageIndex.size()
                || oldLoadedCount != entityBridge.size()
                || oldUnresolvedCount != entityBridge.unresolvedCount()) {
            LOGGER.debug(
                    "Millenaire strategy reconciled: villages={} (changes={}), factions={} (changes={}), loaded villagers={} (unresolved={}, changes={})",
                    villageIndex.size(),
                    villageChanges,
                    factionProjection.size(),
                    factionChanges,
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
            if (orderExecution != null) {
                orderExecution.entityLeft(villager);
            }
            entityBridge.onLeave(villager);
        }
    }

    public void entityDamaged(MillVillager victim, LivingEntity source, float healthDamage) {
        if (orderExecution != null) {
            orderExecution.entityDamaged(victim, source, healthDamage);
        }
    }

    public void entityDied(MillVillager villager) {
        if (orderExecution != null) {
            orderExecution.entityDied(villager);
        }
    }

    public boolean stop() {
        if (state != State.RUNNING) {
            return false;
        }
        state = State.STOPPED;
        ServerIntentRouter.uninstall(networkService);
        networkService = null;
        if (orderExecution != null) {
            orderExecution.stop(server);
            orderExecution = null;
        }
        recruitmentService.stop(server);
        diplomacy.stop(server);
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
        realmService = null;
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
        ticksUntilPositionCapture = 0;
        completedEconomyRevision = 0L;
        unitPositionProjection.reset();
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

    public RealmService realmService() {
        return realmService;
    }

    public UnitRoleService unitRoleService() {
        return unitRoleService;
    }

    public ArmyOrderExecutionBridge orderExecution() {
        return orderExecution;
    }

    public StrategicPhaseTelemetry phaseTelemetry() {
        return phaseTelemetry;
    }
}
