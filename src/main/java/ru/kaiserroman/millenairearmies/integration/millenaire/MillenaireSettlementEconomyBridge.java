package ru.kaiserroman.millenairearmies.integration.millenaire;

import java.util.List;
import java.util.Objects;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingInventory;
import org.millenaire.village.Village;
import org.millenaire.village.VillagerRecord;
import ru.kaiserroman.millenairearmies.persistence.PackedSettlementEconomyState;
import ru.kaiserroman.millenairearmies.persistence.StableDimensionTable;
import ru.kaiserroman.millenairearmies.persistence.StableItemTable;
import ru.kaiserroman.millenairearmies.server.economy.SettlementEconomyEngine;

/**
 * Revision-driven initial/delta projection from real Millenaire building inventories.
 *
 * <p>A reconciliation visits a bounded number of villages per server tick and scans all four army
 * commodities together. Building inventory caches are invalidated once per revision, not once per
 * key or game tick. Unloaded sources leave the last sound ledger observation intact.</p>
 */
public final class MillenaireSettlementEconomyBridge {
    private final MillenaireVillageIndex villageIndex;
    private final MillenaireVillageIndex.Cursor cursor;
    private final FactionProjectionService factions;
    private final StableDimensionTable dimensions;
    private final SettlementEconomyEngine economy;
    private final Item[] commodities = new Item[PackedSettlementEconomyState.COMMODITY_COUNT];
    private final long[] scanTotals = new long[PackedSettlementEconomyState.COMMODITY_COUNT];
    private final int villagesPerTick;
    private long[] seenRevisions = new long[0];

    private boolean requested = true;
    private boolean scanning;
    private long revision;
    private long cacheToken = Long.MIN_VALUE + 1L;
    private int scannedVillages;
    private int unavailableVillages;
    private int lastTickWorkUnits;
    private long completedRevisions;

    public MillenaireSettlementEconomyBridge(
            MillenaireVillageIndex villageIndex,
            FactionProjectionService factions,
            StableDimensionTable dimensions,
            StableItemTable items,
            SettlementEconomyEngine economy,
            int villagesPerTick) {
        this.villageIndex = Objects.requireNonNull(villageIndex, "villageIndex");
        this.factions = Objects.requireNonNull(factions, "factions");
        this.dimensions = Objects.requireNonNull(dimensions, "dimensions");
        Objects.requireNonNull(items, "items");
        this.economy = Objects.requireNonNull(economy, "economy");
        if (villagesPerTick <= 0) throw new IllegalArgumentException("villagesPerTick must be positive");
        this.villagesPerTick = villagesPerTick;
        cursor = villageIndex.newCursor();

        int food = items.intern(ResourceLocation.parse("minecraft:bread"));
        int iron = items.intern(ResourceLocation.parse("minecraft:iron_ingot"));
        int leather = items.intern(ResourceLocation.parse("minecraft:leather"));
        int arrows = items.intern(ResourceLocation.parse("minecraft:arrow"));
        economy.state().configureCommodityKeys(food, iron, leather, arrows);
        for (int commodity = 0; commodity < commodities.length; commodity++) {
            ResourceLocation name = items.name(economy.state().commodityItemKey(commodity));
            Item item = BuiltInRegistries.ITEM.get(name);
            if (item == null || !name.equals(BuiltInRegistries.ITEM.getKey(item))) {
                throw new IllegalStateException("Army commodity is absent from the item registry: " + name);
            }
            commodities[commodity] = item;
        }
    }

    public void requestReconcile() {
        requested = true;
        // The village index has already changed when lifecycle calls this hook. Discard any
        // partial cursor now and restart from a complete new revision on the next tick.
        scanning = false;
    }

    public void tick(long gameTime) {
        lastTickWorkUnits = 0;
        if (requested && !scanning) {
            requested = false;
            scanning = true;
            revision++;
            if (revision <= 0L) throw new IllegalStateException("Settlement inventory revision exhausted");
            cacheToken = Long.MIN_VALUE + revision;
            economy.beginSettlementReconciliation();
            cursor.reset();
        }
        if (!scanning) return;

        for (int budget = villagesPerTick; budget > 0; budget--) {
            if (!cursor.advance()) {
                scanning = false;
                economy.finishSettlementReconciliation(seenRevisions, revision);
                economy.projectionReady();
                completedRevisions++;
                return;
            }
            scanVillage(cursor.village(), cursor.level(), gameTime);
            lastTickWorkUnits++;
        }
    }

    private void scanVillage(Village village, ServerLevel level, long gameTime) {
        ResourceLocation culture = village.getCultureId();
        int factionRow = culture == null ? -1 : factions.findCultureRow(culture);
        if (factionRow < 0 || village.getId() == null || village.getId().uuid() == null) return;

        int dimension = dimensions.intern(level.dimension().location());
        long most = village.getId().uuid().getMostSignificantBits();
        long least = village.getId().uuid().getLeastSignificantBits();
        int row = economy.registerSettlement(
                most,
                least,
                factions.factionId(factionRow),
                dimension,
                village.getCenter().asLong(),
                gameTime);
        if (row == SettlementEconomyEngine.SETTLEMENT_LIMIT_REACHED) return;
        ensureSeenCapacity(row + 1);
        seenRevisions[row] = revision;

        int population = 0;
        for (VillagerRecord record : village.getVillagerRecords().values()) {
            if (!record.isKilled()) population++;
        }
        int buildings = Math.max(1, village.getBuildings().size());
        int specialization = mix(most, least);
        configureRates(row, population, buildings, specialization);

        Arrays.fill(scanTotals, 0L);
        boolean available = true;
        List<BuildingInstance> buildingList = village.getBuildings();
        for (int buildingIndex = 0; buildingIndex < buildingList.size(); buildingIndex++) {
            BuildingInstance building = buildingList.get(buildingIndex);
            BuildingInventory inventory = building.getInventory();
            if (inventory == null) continue;
            if (!allInventorySourcesLoaded(level, building)) {
                available = false;
                break;
            }
            inventory.invalidateCacheOncePerTick(cacheToken);
            for (int commodity = 0; commodity < commodities.length; commodity++) {
                scanTotals[commodity] = Math.min(
                        Integer.MAX_VALUE,
                        scanTotals[commodity] + inventory.getCount(level, commodities[commodity]));
            }
        }
        if (!available) {
            unavailableVillages++;
            return;
        }
        for (int commodity = 0; commodity < commodities.length; commodity++) {
            economy.observePhysicalStock(row, commodity, (int) scanTotals[commodity]);
        }
        scannedVillages++;
    }

    private void configureRates(int row, int population, int buildings, int specialization) {
        int foodProduction = Math.max(1, buildings / 2);
        int foodConsumption = Math.max(1, population / 8);
        economy.configureRates(row, SettlementEconomyEngine.FOOD,
                16 + population * 2, foodProduction, foodConsumption);

        int ironProduction = (specialization & 1) == 0 ? Math.max(1, buildings / 4) : 0;
        int leatherProduction = (specialization & 2) == 0 ? Math.max(1, buildings / 4) : 0;
        int arrowProduction = (specialization & 4) == 0 ? Math.max(2, buildings) : 0;
        economy.configureRates(row, SettlementEconomyEngine.IRON,
                8 + population / 4, ironProduction, Math.max(0, population / 24));
        economy.configureRates(row, SettlementEconomyEngine.LEATHER,
                8 + population / 3, leatherProduction, Math.max(0, population / 20));
        economy.configureRates(row, SettlementEconomyEngine.ARROWS,
                32 + population * 2, arrowProduction, Math.max(0, population / 10));
    }

    public boolean initialScanComplete() { return economy.isProjectionReady(); }
    public boolean isScanning() { return scanning; }
    public int scannedVillageCount() { return scannedVillages; }
    public int unavailableVillageCount() { return unavailableVillages; }
    public int lastTickWorkUnits() { return lastTickWorkUnits; }
    public long completedRevisionCount() { return completedRevisions; }

    private void ensureSeenCapacity(int required) {
        if (required <= seenRevisions.length) return;
        int capacity = Math.max(16, seenRevisions.length);
        while (capacity < required) capacity += Math.max(1, capacity >>> 1);
        seenRevisions = Arrays.copyOf(seenRevisions, capacity);
    }

    private static boolean allInventorySourcesLoaded(ServerLevel level, BuildingInstance building) {
        return allLoaded(level, building.getChestPositions())
                && allLoaded(level, building.getFurnacePositions())
                && allLoaded(level, building.getFirePitPositions());
    }

    private static boolean allLoaded(ServerLevel level, List<BlockPos> positions) {
        for (int index = 0; index < positions.size(); index++) {
            if (!level.isLoaded(positions.get(index))) return false;
        }
        return true;
    }

    private static int mix(long most, long least) {
        long value = most ^ Long.rotateLeft(least, 29);
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        return (int) (value ^ value >>> 32);
    }
}
