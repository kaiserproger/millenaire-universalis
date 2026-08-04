package ru.kaiserroman.millenairearmies.integration.millenaire;

import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingInventory;
import org.millenaire.building.BuildingPlanSet;
import org.millenaire.combat.raid.RaidManager;
import org.millenaire.culture.ModCultures;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;
import org.slf4j.Logger;
import ru.kaiserroman.millenaire.realm.DiplomaticStatus;
import ru.kaiserroman.millenaire.realm.RealmHistoricalPhase;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenaire.realm.RealmStatePriority;
import ru.kaiserroman.millenaire.simulation.PackedSettlementSimulationState;
import ru.kaiserroman.millenaire.simulation.SettlementStatus;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;

/**
 * Bounded reverse projection from persisted Realm/Simulation state into the concrete Millenaire
 * world. It materialises strategic stocks in real building inventories, applies historical growth
 * pauses to real building instances, synchronises native village relations, and lets Realm wars
 * create native Millenaire raid plans. It never force-loads chunks and skips unlocked/player-owned
 * storage unless explicitly configured.
 */
public final class MillenairePhysicalProjectionService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String HISTORICAL_PAUSE_TAG = "millenaire_armies:historical_pause";
    private static final String STATE_POLICY_PAUSE_TAG = "millenaire_armies:state_policy_pause";
    private static final Set<String> HISTORICAL_PAUSE_TAG_SET = Set.of(HISTORICAL_PAUSE_TAG);
    private static final Set<String> STATE_POLICY_PAUSE_TAG_SET = Set.of(STATE_POLICY_PAUSE_TAG);

    private final MillenaireVillageIndex villages;
    private final MillenaireVillageIndex.Cursor cursor;
    private final MillenaireVillageIndex.Cursor relationCursor;
    private final SimulationSavedData simulation;
    private final RealmSavedData realms;
    private final PackedSettlementSimulationState state;
    private final MillenairePhysicalProjectionPolicy policy = new MillenairePhysicalProjectionPolicy();
    private final MillenaireRealmBuildingPolicy buildingPolicy = new MillenaireRealmBuildingPolicy();
    private final MillenaireTradeCatalogBridge tradeCatalog = new MillenaireTradeCatalogBridge();
    private final int intervalTicks;
    private final int villagesPerTick;
    private final int maximumPhysicalStockPerCommodity;
    private final int maximumItemsPerCommodityPerSweep;
    private final int maximumCatalogItemsPerVillage;
    private final int maximumRelationPairsPerVillage;
    private final int declinePauseYears;
    private final int ruinPauseYears;
    private final long historicalYearTicks;
    private final long simulationCycleTicks;
    private final boolean includePlayerVillages;

    private boolean scanning;
    private long nextSweepTick;
    private long sweepCount;
    private long projectedVillageCount;
    private long inventoryMutationCount;
    private long itemsAdded;
    private long itemsRemoved;
    private long conditionMutationCount;
    private long relationMutationCount;
    private long nativeRaidPlanCount;
    private long nativeRaidAbortCount;
    private long unavailableInventoryCount;
    private long catalogItemVisitCount;
    private long truncatedCatalogCount;
    private int lastTickWorkUnits;
    private final int[] commodityTargets = new int[SimulationSavedData.COMMODITY_COUNT];
    private final int[] commodityBudgets = new int[SimulationSavedData.COMMODITY_COUNT];

    public MillenairePhysicalProjectionService(
            MillenaireVillageIndex villages,
            SimulationSavedData simulation,
            RealmSavedData realms,
            int intervalTicks,
            int villagesPerTick,
            int maximumPhysicalStockPerCommodity,
            int maximumItemsPerCommodityPerSweep,
            int maximumCatalogItemsPerVillage,
            int maximumRelationPairsPerVillage,
            int declinePauseYears,
            int ruinPauseYears,
            long historicalYearTicks,
            long simulationCycleTicks,
            boolean includePlayerVillages) {
        if (villages == null || simulation == null || realms == null) {
            throw new NullPointerException("physical projection dependency");
        }
        if (intervalTicks <= 0 || villagesPerTick <= 0
                || maximumPhysicalStockPerCommodity <= 0
                || maximumItemsPerCommodityPerSweep <= 0
                || maximumCatalogItemsPerVillage <= 0
                || maximumRelationPairsPerVillage <= 0 || declinePauseYears <= 0
                || ruinPauseYears < declinePauseYears || historicalYearTicks <= 0L
                || simulationCycleTicks <= 0L) {
            throw new IllegalArgumentException("Invalid physical projection bounds");
        }
        this.villages = villages;
        this.cursor = villages.newCursor();
        this.relationCursor = villages.newCursor();
        this.simulation = simulation;
        this.realms = realms;
        this.state = simulation.state();
        this.intervalTicks = intervalTicks;
        this.villagesPerTick = villagesPerTick;
        this.maximumPhysicalStockPerCommodity = maximumPhysicalStockPerCommodity;
        this.maximumItemsPerCommodityPerSweep = maximumItemsPerCommodityPerSweep;
        this.maximumCatalogItemsPerVillage = maximumCatalogItemsPerVillage;
        this.maximumRelationPairsPerVillage = maximumRelationPairsPerVillage;
        this.declinePauseYears = declinePauseYears;
        this.ruinPauseYears = ruinPauseYears;
        this.historicalYearTicks = historicalYearTicks;
        this.simulationCycleTicks = simulationCycleTicks;
        this.includePlayerVillages = includePlayerVillages;
    }

    public void requestReconcile() {
        scanning = false;
        nextSweepTick = 0L;
    }

    public void tick(long gameTime) {
        if (gameTime < 0L) throw new IllegalArgumentException("Negative gameTime");
        lastTickWorkUnits = 0;
        if (!scanning) {
            if (gameTime < nextSweepTick) return;
            cursor.reset();
            scanning = true;
            nextSweepTick = saturatedAdd(gameTime, intervalTicks);
        }
        for (int budget = villagesPerTick; budget > 0; budget--) {
            if (!cursor.advance()) {
                scanning = false;
                sweepCount++;
                return;
            }
            projectVillage(cursor.village(), cursor.level(), gameTime);
            lastTickWorkUnits++;
        }
    }

    private void projectVillage(Village village, ServerLevel level, long gameTime) {
        if (village == null || level == null || village.getId() == null
                || village.getId().uuid() == null) {
            return;
        }
        UUID uuid = village.getId().uuid();
        long settlementId = simulation.keys().findSettlement(uuid);
        int row = settlementId == 0L ? -1 : state.find(settlementId);
        if (row < 0 || !state.physicallyPresentAt(row)) return;

        long realmId = state.realmIdAt(row);
        if (realmId == RealmRegistry.NO_REALM) realmId = realms.realmForSettlement(uuid);
        RealmHistoricalPhase phase = realmId == RealmRegistry.NO_REALM
                ? null
                : realms.history().phase(realmId);
        SettlementStatus status = state.statusAt(row);

        boolean mayMutateVillage = includePlayerVillages
                || (!village.isPlayerControlled() && village.getOwnerUUID() == null);
        if (mayMutateVillage) {
            if (village.areChestsLocked()) {
                projectInventories(village, level, row, status, phase);
            }
            projectCondition(village, level, realmId, status, phase, gameTime);
        }
        projectRelations(village, level, realmId, phase, gameTime);
        projectedVillageCount++;
    }

    private void projectInventories(
            Village village,
            ServerLevel level,
            int row,
            SettlementStatus status,
            RealmHistoricalPhase phase) {
        BuildingInstance townHall = village.getTownhall();
        BuildingInventory sink = usableInventory(level, townHall);
        if (sink == null) {
            unavailableInventoryCount++;
            return;
        }

        List<BuildingInstance> buildings = village.getBuildings();
        if (!prepareInventories(level, buildings)) {
            unavailableInventoryCount++;
            return;
        }
        MillenaireTradeCatalogBridge.Catalog catalog = tradeCatalog.catalog(village.getCultureId());
        int catalogLimit = Math.min(catalog.size(), maximumCatalogItemsPerVillage);
        if (catalogLimit < catalog.size()) truncatedCatalogCount++;
        for (int commodity = 0; commodity < state.commodityCount(); commodity++) {
            commodityTargets[commodity] = policy.targetPhysicalStock(
                    commodity,
                    state.stockAt(row, commodity),
                    status,
                    phase,
                    maximumPhysicalStockPerCommodity);
            commodityBudgets[commodity] = maximumItemsPerCommodityPerSweep;
        }

        boolean mutated = false;
        for (int catalogRow = 0; catalogRow < catalogLimit; catalogRow++) {
            int commodity = catalog.commodity(catalogRow);
            int budget = commodityBudgets[commodity];
            if (budget <= 0) continue;
            Item item = catalog.item(catalogRow);
            int physical = physicalCountPrepared(level, buildings, item);
            int target = catalog.targetForItem(catalogRow, commodityTargets[commodity]);
            int delta = policy.boundedDelta(physical, target, budget);
            catalogItemVisitCount++;
            if (delta > 0) {
                int added = sink.add(level, item, delta);
                if (added > 0) {
                    itemsAdded += added;
                    inventoryMutationCount++;
                    commodityBudgets[commodity] -= added;
                    mutated = true;
                }
            } else if (delta < 0) {
                int removed = removeAcross(level, buildings, item, -delta);
                if (removed > 0) {
                    itemsRemoved += removed;
                    inventoryMutationCount++;
                    commodityBudgets[commodity] -= removed;
                    mutated = true;
                }
            }
        }
        if (mutated) {
            village.markDirty();
            VillageSavedData.get(level).setDirty();
        }
    }

    private void projectCondition(
            Village village,
            ServerLevel level,
            long realmId,
            SettlementStatus status,
            RealmHistoricalPhase phase,
            long gameTime) {
        int historicalPauseYears = policy.constructionPauseYears(
                status, phase, declinePauseYears, ruinPauseYears);
        RealmStatePriority priority = realmId == RealmRegistry.NO_REALM
                ? RealmStatePriority.NONE
                : realms.statePriority(realmId);
        int statePauseYears = priority.permitsConstruction() || priority == RealmStatePriority.NONE
                ? 0
                : 1;
        int pauseYears = Math.max(historicalPauseYears, statePauseYears);
        boolean historicalAllows = policy.upgradesAllowed(status, phase);
        boolean hadManagedPause = false;
        boolean changed = false;
        for (BuildingInstance building : village.getBuildings()) {
            if (building == null) continue;
            boolean hadHistorical = building.hasRuntimeTag(HISTORICAL_PAUSE_TAG);
            boolean hadState = building.hasRuntimeTag(STATE_POLICY_PAUSE_TAG);
            hadManagedPause |= hadHistorical || hadState;
            BuildingPlanSet planSet = building.getPlanSetId() == null
                    ? null
                    : ModCultures.getBuildingPlanSet(building.getPlanSetId());
            boolean stateAllows = buildingPolicy.permitsUpgrade(priority, planSet);

            if (historicalAllows && hadHistorical) {
                building.removeRuntimeTags(HISTORICAL_PAUSE_TAG_SET);
                changed = true;
            } else if (!historicalAllows && !hadHistorical) {
                building.addRuntimeTags(HISTORICAL_PAUSE_TAG_SET);
                changed = true;
            }
            if (stateAllows && hadState) {
                building.removeRuntimeTags(STATE_POLICY_PAUSE_TAG_SET);
                changed = true;
            } else if (!stateAllows && !hadState) {
                building.addRuntimeTags(STATE_POLICY_PAUSE_TAG_SET);
                changed = true;
            }

            boolean targetAllowed = historicalAllows && stateAllows;
            if (building.isUpgradesAllowed() != targetAllowed) {
                building.setUpgradesAllowed(targetAllowed);
                changed = true;
            }
        }

        if (pauseYears > 0) {
            long until = saturatedAdd(gameTime, saturatedMultiply(historicalYearTicks, pauseYears));
            long refreshThreshold = saturatedAdd(gameTime, intervalTicks);
            if (village.getNoProjectsLeftUntil() <= refreshThreshold) {
                village.setNoProjectsLeftUntil(until);
                changed = true;
            }
            if (village.getRaidTarget() != null) {
                RaidManager.abortRaidForAttacker(village, level);
                nativeRaidAbortCount++;
                changed = true;
            }
        } else if (hadManagedPause && village.getNoProjectsLeftUntil() > gameTime) {
            village.setNoProjectsLeftUntil(0L);
            changed = true;
        }

        if (changed) {
            conditionMutationCount++;
            village.markDirty();
            VillageSavedData.get(level).setDirty();
            village.recordEvent(
                    level,
                    historicalPauseYears > 0
                            ? "millenaire_armies.history." + status.name().toLowerCase()
                            : "millenaire_armies.state_priority."
                                    + priority.name().toLowerCase());
        }
    }

    private void projectRelations(
            Village village,
            ServerLevel level,
            long realmId,
            RealmHistoricalPhase phase,
            long gameTime) {
        if (realmId == RealmRegistry.NO_REALM || !realms.registry().exists(realmId)) return;
        int pairs = 0;
        for (relationCursor.reset(); relationCursor.advance()
                && pairs < maximumRelationPairsPerVillage; ) {
            Village other = relationCursor.village();
            ServerLevel otherLevel = relationCursor.level();
            if (other == null || other == village || other.getId() == null
                    || other.getId().uuid() == null) {
                continue;
            }
            long otherRealm = realms.realmForSettlement(other.getId().uuid());
            if (otherRealm == RealmRegistry.NO_REALM || !realms.registry().exists(otherRealm)) continue;
            pairs++;
            boolean sameRealm = realmId == otherRealm;
            long cycle = gameTime / simulationCycleTicks;
            DiplomaticStatus diplomatic = sameRealm
                    ? DiplomaticStatus.ALLIANCE
                    : realms.diplomacy().status(realmId, otherRealm, cycle);
            int target = policy.relationValue(diplomatic, sameRealm);
            if (village.getRelation(other.getId()) != target) {
                village.setRelation(other.getId(), target);
                other.setRelation(village.getId(), target);
                village.markDirty();
                other.markDirty();
                VillageSavedData.get(level).setDirty();
                if (otherLevel != level) VillageSavedData.get(otherLevel).setDirty();
                relationMutationCount++;
            }

            if (diplomatic == DiplomaticStatus.WAR
                    && otherLevel == level
                    && !village.isPlayerControlled()
                    && !other.isPlayerControlled()
                    && canonicalPairOwner(village.getId(), other.getId())) {
                boolean hasRaid = village.getRaidTarget() != null;
                if (policy.shouldPlanNativeRaid(
                        diplomatic, phase, hasRaid, village.isUnderAttack())) {
                    RaidManager.planRaid(village, other, level);
                    nativeRaidPlanCount++;
                    village.markDirty();
                    other.markDirty();
                }
            } else if (diplomatic != DiplomaticStatus.WAR
                    && village.getRaidTarget() != null
                    && village.getRaidTarget().equals(other.getId())) {
                RaidManager.abortRaidForAttacker(village, level);
                nativeRaidAbortCount++;
            }
        }
    }

    private static boolean prepareInventories(
            ServerLevel level,
            List<BuildingInstance> buildings) {
        for (BuildingInstance building : buildings) {
            if (building == null || !building.isOperational()) continue;
            BuildingInventory inventory = building.getInventory();
            if (inventory == null) continue;
            if (!allLoaded(level, building.getChestPositions())
                    || !allLoaded(level, building.getFurnacePositions())
                    || !allLoaded(level, building.getFirePitPositions())) {
                return false;
            }
            inventory.invalidateCache();
        }
        return true;
    }

    private static int physicalCountPrepared(
            ServerLevel level,
            List<BuildingInstance> buildings,
            Item item) {
        long total = 0L;
        for (BuildingInstance building : buildings) {
            if (building == null || !building.isOperational()) continue;
            BuildingInventory inventory = building.getInventory();
            if (inventory == null) continue;
            total += inventory.getCount(level, item);
            if (total >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) total;
    }

    private static int removeAcross(
            ServerLevel level,
            List<BuildingInstance> buildings,
            Item item,
            int requested) {
        int remaining = requested;
        for (BuildingInstance building : buildings) {
            if (remaining <= 0) break;
            BuildingInventory inventory = usableInventory(level, building);
            if (inventory == null) continue;
            int removed = inventory.remove(level, item, remaining);
            remaining -= Math.max(0, removed);
        }
        return requested - remaining;
    }

    private static BuildingInventory usableInventory(
            ServerLevel level,
            BuildingInstance building) {
        if (building == null || !building.isOperational()) return null;
        BuildingInventory inventory = building.getInventory();
        if (inventory == null
                || !allLoaded(level, building.getChestPositions())
                || !allLoaded(level, building.getFurnacePositions())
                || !allLoaded(level, building.getFirePitPositions())) {
            return null;
        }
        return inventory;
    }

    private static boolean allLoaded(ServerLevel level, List<BlockPos> positions) {
        if (positions == null) return false;
        for (BlockPos position : positions) {
            if (position == null || !level.isLoaded(position)) return false;
        }
        return true;
    }

    private static boolean canonicalPairOwner(VillageId first, VillageId second) {
        UUID left = first.uuid();
        UUID right = second.uuid();
        int most = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return most < 0 || most == 0
                && Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits()) < 0;
    }

    public long sweepCount() { return sweepCount; }
    public long projectedVillageCount() { return projectedVillageCount; }
    public long inventoryMutationCount() { return inventoryMutationCount; }
    public long itemsAdded() { return itemsAdded; }
    public long itemsRemoved() { return itemsRemoved; }
    public long conditionMutationCount() { return conditionMutationCount; }
    public long relationMutationCount() { return relationMutationCount; }
    public long nativeRaidPlanCount() { return nativeRaidPlanCount; }
    public long nativeRaidAbortCount() { return nativeRaidAbortCount; }
    public long unavailableInventoryCount() { return unavailableInventoryCount; }
    public long catalogItemVisitCount() { return catalogItemVisitCount; }
    public long truncatedCatalogCount() { return truncatedCatalogCount; }
    public int lastTickWorkUnits() { return lastTickWorkUnits; }

    public void logShutdownMetrics() {
        LOGGER.info(
                "[BANNEROK_PHYSICAL_PROJECTION_METRICS] sweeps={} villages={} inventory_mutations={} items_added={} items_removed={} condition_mutations={} relation_mutations={} raids_planned={} raids_aborted={} unavailable_inventories={} catalog_item_visits={} catalog_cultures={} catalog_entries={} source_goods={} tag_goods={} unmapped_goods={} unresolved_goods={} truncated_catalogs={} last_work={}",
                sweepCount,
                projectedVillageCount,
                inventoryMutationCount,
                itemsAdded,
                itemsRemoved,
                conditionMutationCount,
                relationMutationCount,
                nativeRaidPlanCount,
                nativeRaidAbortCount,
                unavailableInventoryCount,
                catalogItemVisitCount,
                tradeCatalog.cachedCultureCount(),
                tradeCatalog.cachedEntryCount(),
                tradeCatalog.cachedSourceGoodCount(),
                tradeCatalog.cachedTagGoodCount(),
                tradeCatalog.cachedUnmappedGoodCount(),
                tradeCatalog.cachedUnresolvedGoodCount(),
                truncatedCatalogCount,
                lastTickWorkUnits);
    }

    private static long saturatedMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) return 0L;
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    private static long saturatedAdd(long left, long right) {
        if (right <= 0L) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
