package ru.kaiserroman.millenairearmies.integration.millenaire;

import com.mojang.logging.LogUtils;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingInventory;
import org.millenaire.village.Village;
import org.millenaire.village.VillagerRecord;
import org.slf4j.Logger;
import ru.kaiserroman.millenaire.simulation.CommodityProfile;
import ru.kaiserroman.millenaire.simulation.SettlementObservation;
import ru.kaiserroman.millenaire.simulation.SimulationEvent;
import ru.kaiserroman.millenaire.simulation.SimulationEventType;
import ru.kaiserroman.millenaire.simulation.SimulationPolicy;
import ru.kaiserroman.millenaire.simulation.WorldShock;
import ru.kaiserroman.millenaire.simulation.WorldSimulationEngine;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.SimulationKeyTable;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;

/**
 * Read-only Millenaire projection for the new world simulation. It never force-loads chunks and
 * never creates, deletes or edits a village. Physical mutations require a later explicit adapter
 * that acknowledges persisted candidate events after terrain/protection validation.
 */
public final class MillenaireWorldSimulationBridge {
    public static final int FOOD = 0;
    public static final int TIMBER = 1;
    public static final int STONE = 2;
    public static final int IRON = 3;
    public static final int TEXTILES = 4;
    public static final int TOOLS = 5;
    public static final int ARMS = 6;
    public static final int LUXURY = 7;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final CommodityProfile[] COMMODITIES = {
        new CommodityProfile(100, 5_000, 8_000, 1_000, 1_800, 250),
        new CommodityProfile(80, 2_000, 3_000, 250, 900, 220),
        new CommodityProfile(90, 1_500, 2_500, 180, 800, 220),
        new CommodityProfile(250, 600, 900, 80, 1_200, 180),
        new CommodityProfile(180, 1_000, 1_500, 300, 1_000, 220),
        new CommodityProfile(320, 500, 650, 100, 1_300, 180),
        new CommodityProfile(520, 300, 350, 45, 1_500, 160),
        new CommodityProfile(800, 250, 180, 60, 2_000, 140)
    };

    private final MillenaireVillageIndex villageIndex;
    private final MillenaireVillageIndex.Cursor cursor;
    private final SimulationSavedData data;
    private final SimulationKeyTable keys;
    private final RealmSavedData realms;
    private final WorldSimulationEngine engine;
    private final MillenaireRegionalDynamicsService regionalDynamics;
    private final MillenaireTradeCatalogBridge tradeCatalog = new MillenaireTradeCatalogBridge();
    private final long[] physicalStockTotals = new long[SimulationSavedData.COMMODITY_COUNT];
    private final int villagesPerTick;
    private final int regionSizeBlocks;

    private boolean requested = true;
    private boolean scanning;
    private long completedRevisions;
    private int lastTickWorkUnits;
    private int scannedVillages;
    private long lastStateRevision;
    private int lastShockCount;
    private long physicalStockObservationCount;
    private long unavailablePhysicalStockCount;
    private long physicalCatalogItemVisitCount;
    private long truncatedPhysicalCatalogCount;

    public MillenaireWorldSimulationBridge(
            MillenaireVillageIndex villageIndex,
            SimulationSavedData data,
            int villagesPerTick,
            int regionSizeBlocks) {
        this(villageIndex, data, null, villagesPerTick, regionSizeBlocks);
    }

    public MillenaireWorldSimulationBridge(
            MillenaireVillageIndex villageIndex,
            SimulationSavedData data,
            RealmSavedData realms,
            int villagesPerTick,
            int regionSizeBlocks) {
        if (villageIndex == null || data == null) {
            throw new NullPointerException("world simulation dependency");
        }
        if (villagesPerTick <= 0 || regionSizeBlocks <= 0) {
            throw new IllegalArgumentException("World simulation scan bounds must be positive");
        }
        this.villageIndex = villageIndex;
        this.cursor = villageIndex.newCursor();
        this.data = data;
        this.keys = data.keys();
        this.realms = realms;
        this.villagesPerTick = villagesPerTick;
        this.regionSizeBlocks = regionSizeBlocks;
        SimulationPolicy policy = new SimulationPolicy(
                ArmiesConfig.MAX_SETTLEMENTS,
                SimulationSavedData.COMMODITY_COUNT,
                ArmiesConfig.WORLD_SIMULATION_INTERVAL_TICKS,
                ArmiesConfig.HISTORICAL_YEAR_TICKS,
                ArmiesConfig.WORLD_SIMULATION_ROWS_PER_TICK,
                ArmiesConfig.WORLD_SIMULATION_DECLINE_GRACE_YEARS,
                ArmiesConfig.WORLD_SIMULATION_ABANDONMENT_GRACE_YEARS,
                ArmiesConfig.WORLD_SIMULATION_MISSING_YEARS_BEFORE_RUIN,
                ArmiesConfig.WORLD_SIMULATION_FOUNDING_COOLDOWN_YEARS,
                ArmiesConfig.WORLD_SIMULATION_FOUNDING_POPULATION,
                ArmiesConfig.WORLD_SIMULATION_MINIMUM_VIABLE_POPULATION,
                ArmiesConfig.WORLD_SIMULATION_MAX_CATCH_UP_CYCLES);
        engine = new WorldSimulationEngine(
                data.state(), policy, COMMODITIES, this::recordEvent, data.shocks());
        regionalDynamics = new MillenaireRegionalDynamicsService(
                villageIndex,
                data,
                engine,
                ArmiesConfig.WORLD_SIMULATION_SHOCK_PROPAGATION_ENABLED,
                ArmiesConfig.WORLD_SIMULATION_REFUGEE_MIGRATION_ENABLED,
                ArmiesConfig.WORLD_SIMULATION_ENDOGENOUS_SHOCKS_ENABLED,
                ArmiesConfig.WORLD_SIMULATION_PROPAGATION_TARGETS,
                ArmiesConfig.WORLD_SIMULATION_REFUGEE_FLOWS,
                ArmiesConfig.WORLD_SIMULATION_INTERACTION_DISTANCE_BLOCKS,
                regionSizeBlocks,
                ArmiesConfig.WORLD_SIMULATION_REGIONAL_EVALUATION_INTERVAL_CYCLES,
                ArmiesConfig.WORLD_SIMULATION_REGIONAL_EVALUATION_ROWS_PER_TICK,
                ArmiesConfig.WORLD_SIMULATION_ENDOGENOUS_SHOCKS_PER_SWEEP,
                ArmiesConfig.WORLD_SIMULATION_INTERVAL_TICKS);
        lastStateRevision = data.state().revision();
        lastShockCount = data.shocks().size();
    }

    public void requestReconcile() {
        requested = true;
        // The indexed village set changed. Discard a partial cursor and start a fresh complete epoch.
        scanning = false;
    }

    public void tick(long gameTime) {
        lastTickWorkUnits = 0;
        if (requested && !scanning) {
            requested = false;
            scanning = true;
            cursor.reset();
            engine.beginReconciliation();
        }
        if (scanning) {
            for (int budget = villagesPerTick; budget > 0; budget--) {
                if (!cursor.advance()) {
                    scanning = false;
                    engine.finishReconciliation();
                    completedRevisions++;
                    data.markChanged();
                    break;
                }
                scanVillage(cursor.village(), cursor.level(), gameTime);
                lastTickWorkUnits++;
            }
        }

        engine.tick(gameTime);
        lastTickWorkUnits += engine.lastTickWorkUnits();
        if (!scanning) {
            lastTickWorkUnits += regionalDynamics.tick(gameTime);
        }
        if (data.state().revision() != lastStateRevision) {
            lastStateRevision = data.state().revision();
            data.markChanged();
        }
        if (data.shocks().size() != lastShockCount) {
            lastShockCount = data.shocks().size();
            data.markChanged();
        }
    }

    private void scanVillage(Village village, ServerLevel level, long gameTime) {
        if (village == null || level == null || village.getId() == null
                || village.getId().uuid() == null || village.getCultureId() == null
                || village.getCenter() == null) {
            return;
        }
        int oldSettlementKeys = keys.settlementCount();
        int oldCultureKeys = keys.cultureCount();
        int oldDimensionKeys = keys.dimensionCount();
        long settlementId = keys.internSettlement(village.getId().uuid());
        int cultureKey = keys.internCulture(village.getCultureId());
        int dimensionKey = keys.internDimension(level.dimension().location());

        int population = 0;
        int militaryStrength = 0;
        for (VillagerRecord record : village.getVillagerRecords().values()) {
            if (record != null && !record.isKilled()) {
                population = saturatedAdd(population, 1);
                militaryStrength = saturatedAdd(
                        militaryStrength, Math.max(0, record.getMilitaryStrength()));
            }
        }
        List<BuildingInstance> buildingList = village.getBuildings();
        int buildings = buildingList == null ? 0 : buildingList.size();
        int productiveBuildings = 0;
        if (buildingList != null) {
            for (BuildingInstance building : buildingList) {
                if (building != null && building.isOperational()) productiveBuildings++;
            }
        }
        boolean playerControlled = village.isPlayerControlled();
        int militaryIndex = population == 0
                ? 0
                : Math.min(500, (int) Math.min(Integer.MAX_VALUE,
                        (long) militaryStrength * 8L / population));
        int marketAccess = clampIndex(
                220 + productiveBuildings * 35 + (playerControlled ? 100 : 0)
                        - (village.isLoneBuilding() ? 180 : 0));
        int security = clampIndex(250 + militaryIndex + (playerControlled ? 100 : 0));
        int education = clampIndex(240 + buildings * 24 + (playerControlled ? 80 : 0));
        int geography = clampIndex(820 - Math.min(280, buildings * 9));
        int hash = mix(village.getId().uuid().getMostSignificantBits(),
                village.getId().uuid().getLeastSignificantBits());
        int fertility = 470 + Math.floorMod(hash >>> 8, 181);
        int specialization = Math.floorMod(hash, 1001);
        long housingCapacity = Math.max((long) population + 8L, (long) buildings * 12L);
        long regionKey = packRegion(dimensionKey, village.getCenter(), regionSizeBlocks);

        long realmId = realms == null
                ? 0L
                : realms.realmForSettlement(village.getId().uuid());
        boolean newSettlement = data.state().find(settlementId) < 0;
        int damage = buildings == 0
                ? 1000
                : clampIndex(1000 - productiveBuildings * 1000 / buildings);
        int row = engine.observe(new SettlementObservation(
                settlementId,
                cultureKey,
                realmId,
                regionKey,
                population,
                housingCapacity,
                buildings,
                productiveBuildings,
                marketAccess,
                security,
                damage,
                education,
                geography,
                fertility,
                specialization), gameTime);
        if (row < 0) {
            LOGGER.warn("World simulation settlement limit reached; village {} is not projected",
                    village.getId().uuid());
            return;
        }
        observePhysicalStocks(
                village,
                level,
                row,
                gameTime,
                newSettlement ? 1000 : ArmiesConfig.WORLD_SIMULATION_PHYSICAL_STOCK_WEIGHT_PERMILLE);
        scannedVillages++;
        if (oldSettlementKeys != keys.settlementCount()
                || oldCultureKeys != keys.cultureCount()
                || oldDimensionKeys != keys.dimensionCount()) {
            data.markChanged();
        }
    }

    private void observePhysicalStocks(
            Village village,
            ServerLevel level,
            int row,
            long gameTime,
            int weightPermille) {
        Arrays.fill(physicalStockTotals, 0L);
        MillenaireTradeCatalogBridge.Catalog catalog = tradeCatalog.catalog(village.getCultureId());
        int catalogLimit = Math.min(
                catalog.size(),
                ArmiesConfig.WORLD_PHYSICAL_PROJECTION_CATALOG_ITEMS);
        if (catalogLimit < catalog.size()) truncatedPhysicalCatalogCount++;
        boolean sawInventory = false;
        for (BuildingInstance building : village.getBuildings()) {
            if (building == null) continue;
            BuildingInventory inventory = building.getInventory();
            if (inventory == null) continue;
            if (!allInventorySourcesLoaded(level, building)) {
                unavailablePhysicalStockCount++;
                return;
            }
            sawInventory = true;
            inventory.invalidateCacheOncePerTick(gameTime);
            for (int catalogRow = 0; catalogRow < catalogLimit; catalogRow++) {
                int count = Math.max(0, inventory.getCount(level, catalog.item(catalogRow)));
                if (count == 0) continue;
                int commodity = catalog.commodity(catalogRow);
                physicalStockTotals[commodity] = saturatedAdd(
                        physicalStockTotals[commodity],
                        saturatedMultiply(count, catalog.virtualUnits(catalogRow)));
                physicalCatalogItemVisitCount++;
            }
        }
        if (!sawInventory) {
            unavailablePhysicalStockCount++;
            return;
        }
        for (int commodity = 0; commodity < physicalStockTotals.length; commodity++) {
            if (engine.observePhysicalStock(
                    row,
                    commodity,
                    physicalStockTotals[commodity],
                    weightPermille)) {
                physicalStockObservationCount++;
            }
        }
    }

    private static boolean allInventorySourcesLoaded(
            ServerLevel level,
            BuildingInstance building) {
        return allLoaded(level, building.getChestPositions())
                && allLoaded(level, building.getFurnacePositions())
                && allLoaded(level, building.getFirePitPositions());
    }

    private static boolean allLoaded(ServerLevel level, List<BlockPos> positions) {
        if (positions == null) return false;
        for (BlockPos position : positions) {
            if (position == null || !level.isLoaded(position)) return false;
        }
        return true;
    }

    private void recordEvent(SimulationEvent event) {
        long sequence = data.events().append(event);
        data.markChanged();
        if (sequence == 0L) {
            long dropped = data.events().droppedEventCount();
            if (dropped == 1L || (dropped & (dropped - 1L)) == 0L) {
                LOGGER.warn("World simulation event journal is full; dropped_events={}", dropped);
            }
            return;
        }
        if (event.type() == SimulationEventType.FOUNDING_CANDIDATE
                || event.type() == SimulationEventType.ABANDONMENT_CANDIDATE
                || event.type() == SimulationEventType.RUINED) {
            LOGGER.info(
                    "[BANNEROK_WORLD_SIMULATION_EVENT] sequence={} type={} settlement={} culture={} realm={} region={} score={} reasons={} cycle={}",
                    sequence,
                    event.type(),
                    event.settlementId(),
                    event.cultureKey(),
                    event.realmId(),
                    event.regionKey(),
                    event.score(),
                    event.reasonMask(),
                    event.cycle());
        }
    }

    public static int commodityBasePrice(int commodity) {
        if (commodity < 0 || commodity >= COMMODITIES.length) {
            throw new IllegalArgumentException("Unknown Simulation commodity " + commodity);
        }
        return COMMODITIES[commodity].basePrice();
    }

    public boolean applyShock(WorldShock shock, long gameTime) {
        return regionalDynamics.applyShock(shock, gameTime);
    }

    public WorldSimulationEngine engine() { return engine; }
    public MillenaireRegionalDynamicsService regionalDynamics() { return regionalDynamics; }
    public SimulationSavedData savedData() { return data; }
    public boolean isScanning() { return scanning; }
    public int scannedVillageCount() { return scannedVillages; }
    public long completedRevisionCount() { return completedRevisions; }
    public int lastTickWorkUnits() { return lastTickWorkUnits; }

    public void logShutdownMetrics() {
        LOGGER.info(
                "[BANNEROK_WORLD_SIMULATION_METRICS] settlements={} cultures={} dimensions={} events={} dropped_events={} shocks={} simulated_cycles={} scan_revisions={} physical_stock_observations={} unavailable_physical_stocks={} catalog_item_visits={} catalog_cultures={} catalog_entries={} source_goods={} tag_goods={} unmapped_goods={} unresolved_goods={} truncated_catalogs={} primitive_bytes={}",
                data.state().size(),
                keys.cultureCount(),
                keys.dimensionCount(),
                data.events().size(),
                data.events().droppedEventCount(),
                data.shocks().size(),
                engine.simulatedCycleCount(),
                completedRevisions,
                physicalStockObservationCount,
                unavailablePhysicalStockCount,
                physicalCatalogItemVisitCount,
                tradeCatalog.cachedCultureCount(),
                tradeCatalog.cachedEntryCount(),
                tradeCatalog.cachedSourceGoodCount(),
                tradeCatalog.cachedTagGoodCount(),
                tradeCatalog.cachedUnmappedGoodCount(),
                tradeCatalog.cachedUnresolvedGoodCount(),
                truncatedPhysicalCatalogCount,
                data.state().estimatedPrimitiveBytes()
                        + data.events().estimatedPrimitiveBytes()
                        + data.shocks().estimatedPrimitiveBytes()
                        + keys.estimatedPrimitiveBytes());
        regionalDynamics.logShutdownMetrics();
    }

    static long packRegion(int dimensionKey, BlockPos center, int regionSizeBlocks) {
        if (dimensionKey <= 0 || dimensionKey > 0xfffff || center == null || regionSizeBlocks <= 0) {
            throw new IllegalArgumentException("Invalid simulation region input");
        }
        int regionX = Math.floorDiv(center.getX(), regionSizeBlocks);
        int regionZ = Math.floorDiv(center.getZ(), regionSizeBlocks);
        if (regionX < -524_288 || regionX > 524_287 || regionZ < -524_288 || regionZ > 524_287) {
            throw new IllegalArgumentException("Simulation region coordinate exceeds packed range");
        }
        return ((long) dimensionKey << 40)
                | ((long) regionX & 0xfffffL) << 20
                | ((long) regionZ & 0xfffffL);
    }

    private static int saturatedAdd(int left, int right) {
        if (right > 0 && left > Integer.MAX_VALUE - right) return Integer.MAX_VALUE;
        return left + right;
    }

    private static long saturatedAdd(long left, long right) {
        if (right <= 0L) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long saturatedMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) return 0L;
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    private static int clampIndex(int value) {
        return Math.max(0, Math.min(1000, value));
    }

    private static int mix(long most, long least) {
        long value = most ^ Long.rotateLeft(least, 29);
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        return (int) (value ^ value >>> 32);
    }
}
