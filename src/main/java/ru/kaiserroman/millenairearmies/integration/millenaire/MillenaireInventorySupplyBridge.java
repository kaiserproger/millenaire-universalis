package ru.kaiserroman.millenairearmies.integration.millenaire;

import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingInventory;
import org.millenaire.village.Village;
import ru.kaiserroman.millenairearmies.persistence.StableDimensionTable;
import ru.kaiserroman.millenairearmies.persistence.StableItemTable;
import ru.kaiserroman.millenairearmies.server.economy.SettlementEconomyEngine;
import ru.kaiserroman.millenairearmies.server.logistics.SupplyInventoryAccess;

/**
 * Public-API-only bridge from physical Millenaire building inventories to strategic supplies.
 *
 * <p>It deliberately does not poll villagers, entities, combat, targets, or paths. Aggregate scans
 * are invoked only by the bounded publisher. The physical side stays read-only because block-entity
 * saves cannot be atomically committed with SavedData. Army commodities are instead served by the
 * persisted settlement ledger after its bounded initial scan; later physical revisions enter as
 * deltas and therefore cannot erase prior strategic debits. This is not a claim that a physical
 * courier entity or chest transfer occurred.</p>
 */
public final class MillenaireInventorySupplyBridge implements SupplyInventoryAccess {
    private final MillenaireVillageIndex villageIndex;
    private final MillenaireVillageIndex.Cursor villageCursor;
    private final FactionProjectionService factions;
    private final StableDimensionTable dimensions;
    private final StableItemTable items;
    private final Item[] resolvedItems;
    private final ResourceLocation[] resolvedDimensions;
    private final byte[] itemResolution;
    private final byte[] dimensionResolution;
    private final SettlementEconomyEngine economy;

    private long reconciliationCacheToken = Long.MIN_VALUE + 1L;

    public MillenaireInventorySupplyBridge(
            MillenaireVillageIndex villageIndex,
            FactionProjectionService factions,
            StableDimensionTable dimensions,
            StableItemTable items,
            int maximumStableKeys) {
        this(villageIndex, factions, dimensions, items, maximumStableKeys, null);
    }

    public MillenaireInventorySupplyBridge(
            MillenaireVillageIndex villageIndex,
            FactionProjectionService factions,
            StableDimensionTable dimensions,
            StableItemTable items,
            int maximumStableKeys,
            SettlementEconomyEngine economy) {
        this.villageIndex = Objects.requireNonNull(villageIndex, "villageIndex");
        this.factions = Objects.requireNonNull(factions, "factions");
        this.dimensions = Objects.requireNonNull(dimensions, "dimensions");
        this.items = Objects.requireNonNull(items, "items");
        this.economy = economy;
        if (maximumStableKeys <= 0) {
            throw new IllegalArgumentException("Millenaire supply bridge key bound must be positive");
        }
        villageCursor = villageIndex.newCursor();
        resolvedItems = new Item[maximumStableKeys];
        resolvedDimensions = new ResourceLocation[maximumStableKeys];
        itemResolution = new byte[maximumStableKeys];
        dimensionResolution = new byte[maximumStableKeys];
    }

    @Override
    public void beginReconciliation(long revision) {
        if (revision <= 0L) {
            throw new IllegalArgumentException("Supply reconciliation revision must be positive");
        }
        // Millenaire uses non-negative game ticks. A negative token cannot collide with its own
        // once-per-tick invalidation and lets every building cache be rebuilt at most once per
        // aggregate bridge revision, even when many demanded item keys are drained across ticks.
        reconciliationCacheToken = Long.MIN_VALUE + revision;
    }

    @Override
    public int absoluteStock(int factionId, int dimensionId, int itemKey) {
        if (economy != null) {
            return economy.state().commodityForItemKey(itemKey) >= 0
                    ? economy.absoluteAvailableStock(factionId, dimensionId, itemKey)
                    : UNAVAILABLE;
        }
        Item item = resolveItem(itemKey);
        ResourceLocation dimension = resolveDimension(dimensionId);
        if (item == null || dimension == null || factions.findFactionRow(factionId) < 0) {
            return UNAVAILABLE;
        }

        long total = 0L;
        for (villageCursor.reset(); villageCursor.advance(); ) {
            Village village = villageCursor.village();
            ServerLevel level = villageCursor.level();
            if (!dimension.equals(level.dimension().location()) || !belongsToFaction(village, factionId)) {
                continue;
            }
            List<BuildingInstance> buildings = village.getBuildings();
            for (int buildingIndex = 0, buildingCount = buildings.size();
                    buildingIndex < buildingCount;
                    buildingIndex++) {
                BuildingInstance building = buildings.get(buildingIndex);
                BuildingInventory inventory = building.getInventory();
                if (inventory == null) {
                    continue;
                }
                // Publishing a false zero for an unloaded village would invalidate a sound
                // reservation. Retain the previous snapshot until every physical source is visible.
                if (!allInventorySourcesLoaded(level, building)) {
                    return UNAVAILABLE;
                }
                inventory.invalidateCacheOncePerTick(reconciliationCacheToken);
                total += inventory.getCount(level, item);
                if (total >= Integer.MAX_VALUE) {
                    return Integer.MAX_VALUE;
                }
            }
        }
        return (int) total;
    }

    private boolean belongsToFaction(Village village, int factionId) {
        ResourceLocation cultureId = village.getCultureId();
        if (cultureId == null) {
            return false;
        }
        int row = factions.findCultureRow(cultureId);
        return row >= 0 && factions.factionId(row) == factionId;
    }

    private Item resolveItem(int itemKey) {
        if (itemKey < 0 || itemKey >= resolvedItems.length || itemKey >= items.size()) {
            return null;
        }
        if (itemResolution[itemKey] != 0) {
            return resolvedItems[itemKey];
        }
        ResourceLocation name = items.name(itemKey);
        Item item = BuiltInRegistries.ITEM.get(name);
        if (item != null && name.equals(BuiltInRegistries.ITEM.getKey(item))) {
            resolvedItems[itemKey] = item;
        }
        itemResolution[itemKey] = 1;
        return resolvedItems[itemKey];
    }

    private ResourceLocation resolveDimension(int dimensionId) {
        if (dimensionId < 0 || dimensionId >= resolvedDimensions.length || dimensionId >= dimensions.size()) {
            return null;
        }
        if (dimensionResolution[dimensionId] == 0) {
            resolvedDimensions[dimensionId] = dimensions.name(dimensionId);
            dimensionResolution[dimensionId] = 1;
        }
        return resolvedDimensions[dimensionId];
    }

    private static boolean allInventorySourcesLoaded(ServerLevel level, BuildingInstance building) {
        return allLoaded(level, building.getChestPositions())
                && allLoaded(level, building.getFurnacePositions())
                && allLoaded(level, building.getFirePitPositions());
    }

    private static boolean allLoaded(ServerLevel level, List<BlockPos> positions) {
        for (int index = 0, size = positions.size(); index < size; index++) {
            if (!level.isLoaded(positions.get(index))) {
                return false;
            }
        }
        return true;
    }

}
