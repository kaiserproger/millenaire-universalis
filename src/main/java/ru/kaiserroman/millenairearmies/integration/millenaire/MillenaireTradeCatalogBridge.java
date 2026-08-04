package ru.kaiserroman.millenairearmies.integration.millenaire;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.millenaire.commerce.TradeGood;
import org.millenaire.commerce.TradeGoodsLoader;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;

/**
 * Culture-aware material catalogue shared by physical Simulation observation and projection.
 *
 * <p>Simulation intentionally keeps eight macro-commodities, but the physical adapter must not
 * collapse a culture's hundreds of concrete trade goods to eight representative stacks. This
 * cache resolves every concrete {@link TradeGood} into its real item, deduplicates aliases and
 * distributes each macro stock across the culture's actual catalogue by reserved/target demand.
 * Tag-only goods remain price-aware through {@link SimulationTradePricePolicy}, but cannot be
 * materialised as one unambiguous item and are therefore counted separately rather than guessed.</p>
 */
public final class MillenaireTradeCatalogBridge {
    private final Map<ResourceLocation, Catalog> catalogs = new HashMap<>();

    public Catalog catalog(ResourceLocation culture) {
        if (culture == null) throw new NullPointerException("culture");
        return catalogs.computeIfAbsent(culture, MillenaireTradeCatalogBridge::build);
    }

    public int cachedCultureCount() {
        return catalogs.size();
    }

    public int cachedEntryCount() {
        int total = 0;
        for (Catalog catalog : catalogs.values()) total += catalog.size();
        return total;
    }

    public int cachedSourceGoodCount() {
        int total = 0;
        for (Catalog catalog : catalogs.values()) total += catalog.sourceGoodCount();
        return total;
    }

    public int cachedTagGoodCount() {
        int total = 0;
        for (Catalog catalog : catalogs.values()) total += catalog.tagGoodCount();
        return total;
    }

    public int cachedUnmappedGoodCount() {
        int total = 0;
        for (Catalog catalog : catalogs.values()) total += catalog.unmappedGoodCount();
        return total;
    }

    public int cachedUnresolvedGoodCount() {
        int total = 0;
        for (Catalog catalog : catalogs.values()) total += catalog.unresolvedGoodCount();
        return total;
    }

    public void clear() {
        catalogs.clear();
    }

    private static Catalog build(ResourceLocation culture) {
        List<TradeGood> goods = TradeGoodsLoader.getGoods(culture);
        LinkedHashMap<Item, MutableEntry> concrete = new LinkedHashMap<>();
        int tagGoods = 0;
        int unmappedGoods = 0;
        int unresolvedGoods = 0;
        int duplicateGoods = 0;

        if (goods != null) {
            for (TradeGood good : goods) {
                if (good == null) continue;
                int commodity = SimulationTradePricePolicy.commodityFor(good);
                if (commodity == SimulationTradePricePolicy.UNMAPPED
                        || commodity >= SimulationSavedData.COMMODITY_COUNT) {
                    unmappedGoods++;
                    continue;
                }
                if (good.isTag()) {
                    tagGoods++;
                    continue;
                }
                Item item;
                try {
                    item = good.resolveItem();
                } catch (RuntimeException ignored) {
                    item = null;
                }
                if (item == null) {
                    unresolvedGoods++;
                    continue;
                }
                int weight = weight(good);
                MutableEntry previous = concrete.get(item);
                if (previous == null) {
                    concrete.put(item, new MutableEntry(
                            item,
                            commodity,
                            weight,
                            SimulationCommodityBridge.virtualUnitsPerPhysicalItem(commodity)));
                } else {
                    duplicateGoods++;
                    previous.weight = saturatedAdd(previous.weight, weight);
                    if (previous.commodity != commodity) {
                        // An item can be exposed by aliases in different categories. Keep the
                        // category with the larger accumulated demand instead of double-counting it.
                        previous.commodity = weight > previous.weight - weight
                                ? commodity
                                : previous.commodity;
                        previous.virtualUnits = SimulationCommodityBridge
                                .virtualUnitsPerPhysicalItem(previous.commodity);
                    }
                }
            }
        }

        boolean[] represented = new boolean[SimulationSavedData.COMMODITY_COUNT];
        for (MutableEntry entry : concrete.values()) represented[entry.commodity] = true;
        for (int commodity = 0; commodity < represented.length; commodity++) {
            if (represented[commodity]) continue;
            Item fallback = SimulationCommodityBridge.item(commodity);
            concrete.putIfAbsent(fallback, new MutableEntry(
                    fallback,
                    commodity,
                    1,
                    SimulationCommodityBridge.virtualUnitsPerPhysicalItem(commodity)));
        }

        int size = concrete.size();
        Item[] items = new Item[size];
        byte[] commodities = new byte[size];
        int[] weights = new int[size];
        int[] virtualUnits = new int[size];
        int[] prefixWeights = new int[size];
        int[] totalWeights = new int[SimulationSavedData.COMMODITY_COUNT];
        int[] itemCounts = new int[SimulationSavedData.COMMODITY_COUNT];

        int row = 0;
        for (MutableEntry entry : concrete.values()) {
            items[row] = entry.item;
            commodities[row] = (byte) entry.commodity;
            weights[row] = Math.max(1, entry.weight);
            virtualUnits[row] = Math.max(1, entry.virtualUnits);
            prefixWeights[row] = totalWeights[entry.commodity];
            totalWeights[entry.commodity] = saturatedAdd(
                    totalWeights[entry.commodity], weights[row]);
            itemCounts[entry.commodity]++;
            row++;
        }

        return new Catalog(
                items,
                commodities,
                weights,
                virtualUnits,
                prefixWeights,
                totalWeights,
                itemCounts,
                goods == null ? 0 : goods.size(),
                tagGoods,
                unmappedGoods,
                unresolvedGoods,
                duplicateGoods);
    }

    private static int weight(TradeGood good) {
        long weight = 1L;
        weight += Math.max(0, good.targetQuantity());
        weight += Math.max(0, good.reservedQuantity()) * 2L;
        if (good.autoGenerate()) weight += 8L;
        if (good.canSell()) weight += 4L;
        if (good.canBuy()) weight += 2L;
        int price = Math.max(good.sellingPrice(), good.buyingPrice());
        if (price > 0) {
            // Expensive singular goods should exist, but must not consume the same stack share as
            // bread, timber or stone. Price only gently damps weight; it never removes the good.
            weight = Math.max(1L, weight * 64L / Math.min(256L, 32L + price));
        }
        return (int) Math.min(Integer.MAX_VALUE, weight);
    }

    private static int saturatedAdd(int left, int right) {
        return left > Integer.MAX_VALUE - right ? Integer.MAX_VALUE : left + right;
    }

    public static final class Catalog {
        private final Item[] items;
        private final byte[] commodities;
        private final int[] weights;
        private final int[] virtualUnits;
        private final int[] prefixWeights;
        private final int[] totalWeights;
        private final int[] itemCounts;
        private final int sourceGoodCount;
        private final int tagGoodCount;
        private final int unmappedGoodCount;
        private final int unresolvedGoodCount;
        private final int duplicateGoodCount;

        private Catalog(
                Item[] items,
                byte[] commodities,
                int[] weights,
                int[] virtualUnits,
                int[] prefixWeights,
                int[] totalWeights,
                int[] itemCounts,
                int sourceGoodCount,
                int tagGoodCount,
                int unmappedGoodCount,
                int unresolvedGoodCount,
                int duplicateGoodCount) {
            this.items = items;
            this.commodities = commodities;
            this.weights = weights;
            this.virtualUnits = virtualUnits;
            this.prefixWeights = prefixWeights;
            this.totalWeights = totalWeights;
            this.itemCounts = itemCounts;
            this.sourceGoodCount = sourceGoodCount;
            this.tagGoodCount = tagGoodCount;
            this.unmappedGoodCount = unmappedGoodCount;
            this.unresolvedGoodCount = unresolvedGoodCount;
            this.duplicateGoodCount = duplicateGoodCount;
        }

        public int size() { return items.length; }
        public Item item(int row) { return items[row]; }
        public int commodity(int row) { return Byte.toUnsignedInt(commodities[row]); }
        public int weight(int row) { return weights[row]; }
        public int virtualUnits(int row) { return virtualUnits[row]; }
        public int itemCount(int commodity) { return itemCounts[commodity]; }
        public int sourceGoodCount() { return sourceGoodCount; }
        public int tagGoodCount() { return tagGoodCount; }
        public int unmappedGoodCount() { return unmappedGoodCount; }
        public int unresolvedGoodCount() { return unresolvedGoodCount; }
        public int duplicateGoodCount() { return duplicateGoodCount; }

        /** Exact weighted partition: item targets for one commodity sum to {@code totalTarget}. */
        public int targetForItem(int row, int totalTarget) {
            if (totalTarget <= 0) return 0;
            int commodity = commodity(row);
            int totalWeight = totalWeights[commodity];
            if (totalWeight <= 0) return 0;
            long before = (long) totalTarget * prefixWeights[row] / totalWeight;
            long after = (long) totalTarget * (prefixWeights[row] + (long) weights[row])
                    / totalWeight;
            return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, after - before));
        }
    }

    private static final class MutableEntry {
        final Item item;
        int commodity;
        int weight;
        int virtualUnits;

        MutableEntry(Item item, int commodity, int weight, int virtualUnits) {
            this.item = item;
            this.commodity = commodity;
            this.weight = weight;
            this.virtualUnits = virtualUnits;
        }
    }
}
