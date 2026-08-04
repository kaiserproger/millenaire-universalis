package ru.kaiserroman.millenairearmies.integration.millenaire;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.millenaire.commerce.TradeGood;
import org.millenaire.culture.VillageType;
import ru.kaiserroman.millenaire.simulation.PackedSettlementSimulationState;

/** Converts Simulation commodity scarcity indices into bounded per-village TradeGood prices. */
public final class SimulationTradePricePolicy {
    public static final int UNMAPPED = -1;

    private final int minimumMultiplierPermille;
    private final int maximumMultiplierPermille;
    private final int maximumPrice;

    public SimulationTradePricePolicy(
            int minimumMultiplierPermille,
            int maximumMultiplierPermille,
            int maximumPrice) {
        if (minimumMultiplierPermille <= 0
                || maximumMultiplierPermille < minimumMultiplierPermille
                || maximumPrice <= 0) {
            throw new IllegalArgumentException("Invalid dynamic trade price bounds");
        }
        this.minimumMultiplierPermille = minimumMultiplierPermille;
        this.maximumMultiplierPermille = maximumMultiplierPermille;
        this.maximumPrice = maximumPrice;
    }

    public Adjustment adjustCatalog(
            PackedSettlementSimulationState state,
            int settlementRow,
            VillageType villageType,
            List<TradeGood> source) {
        if (state == null || source == null) {
            throw new NullPointerException("dynamic trade price dependency");
        }
        if (settlementRow < 0 || settlementRow >= state.size()) {
            throw new IllegalArgumentException("Unknown Simulation settlement row");
        }
        if (source.isEmpty()) return new Adjustment(List.of(), 0, 0, 0);

        List<TradeGood> adjusted = new ArrayList<>(source.size());
        int adjustedDirections = 0;
        int fixedOverrides = 0;
        int unmapped = 0;
        for (TradeGood good : source) {
            if (good == null) continue;
            int commodity = commodityFor(good);
            if (commodity == UNMAPPED || commodity >= state.commodityCount()) {
                unmapped++;
                adjusted.add(good);
                continue;
            }
            int baseIndex = MillenaireWorldSimulationBridge.commodityBasePrice(commodity);
            int currentIndex = state.priceIndexAt(settlementRow, commodity);
            if (currentIndex <= 0 || baseIndex <= 0) {
                adjusted.add(good);
                continue;
            }
            boolean fixedSelling = villageType != null
                    && villageType.sellingPriceOverrides().containsKey(good.id());
            boolean fixedBuying = villageType != null
                    && villageType.buyingPriceOverrides().containsKey(good.id());
            if (fixedSelling) fixedOverrides++;
            if (fixedBuying) fixedOverrides++;
            int selling = fixedSelling
                    ? good.sellingPrice()
                    : adjustPrice(good.sellingPrice(), currentIndex, baseIndex);
            int buying = fixedBuying
                    ? good.buyingPrice()
                    : adjustPrice(good.buyingPrice(), currentIndex, baseIndex);
            if (!fixedSelling && selling != good.sellingPrice()) adjustedDirections++;
            if (!fixedBuying && buying != good.buyingPrice()) adjustedDirections++;
            if (selling == good.sellingPrice() && buying == good.buyingPrice()) {
                adjusted.add(good);
            } else {
                adjusted.add(new TradeGood(
                        good.id(),
                        good.item(),
                        selling,
                        buying,
                        good.reservedQuantity(),
                        good.targetQuantity(),
                        good.autoGenerate(),
                        good.minReputation(),
                        good.category(),
                        good.travelBookDisplay(),
                        good.foreignMerchantPrice()));
            }
        }
        return new Adjustment(
                List.copyOf(adjusted), adjustedDirections, fixedOverrides, unmapped);
    }

    public int adjustPrice(int basePrice, int currentIndex, int baseIndex) {
        if (basePrice <= 0) return basePrice;
        if (currentIndex <= 0 || baseIndex <= 0) return basePrice;
        long multiplier = ((long) currentIndex * 1000L + baseIndex / 2L) / baseIndex;
        multiplier = Math.max(
                minimumMultiplierPermille,
                Math.min(maximumMultiplierPermille, multiplier));
        long price = ((long) basePrice * multiplier + 500L) / 1000L;
        return (int) Math.max(1L, Math.min(maximumPrice, price));
    }

    public static int commodityFor(TradeGood good) {
        if (good == null) return UNMAPPED;
        String text = (good.id() + ' ' + good.item() + ' ' + good.category())
                .toLowerCase(Locale.ROOT);
        if (containsAny(text,
                "weapon", "armour", "armor", "sword", "spear", "bow", "arrow",
                "shield", "helmet", "chestplate", "leggings", "boots", "mace")) {
            return MillenaireWorldSimulationBridge.ARMS;
        }
        if (containsAny(text,
                "tool", "pickaxe", "shovel", "hoe", "hammer", "chisel", "saw",
                "shears", "flint_and_steel")) {
            return MillenaireWorldSimulationBridge.TOOLS;
        }
        if (containsAny(text,
                "luxury", "jewel", "jewelry", "gold", "emerald", "diamond", "silk",
                "spice", "wine", "cider", "perfume", "painting", "ornament", "porcelain")) {
            return MillenaireWorldSimulationBridge.LUXURY;
        }
        if (containsAny(text,
                "iron", "steel", "copper", "tin", "bronze", "metal", "ingot", "ore")) {
            return MillenaireWorldSimulationBridge.IRON;
        }
        if (containsAny(text,
                "textile", "cloth", "wool", "leather", "string", "linen", "cotton",
                "carpet", "dye", "hide", "fur")) {
            return MillenaireWorldSimulationBridge.TEXTILES;
        }
        if (containsAny(text,
                "timber", "wood", "log", "plank", "sapling", "charcoal", "bamboo")) {
            return MillenaireWorldSimulationBridge.TIMBER;
        }
        if (containsAny(text,
                "stone", "cobble", "brick", "sand", "glass", "clay", "terracotta",
                "slate", "marble", "granite", "diorite", "andesite")) {
            return MillenaireWorldSimulationBridge.STONE;
        }
        if (containsAny(text,
                "food", "bread", "wheat", "rice", "maize", "corn", "meat", "fish",
                "apple", "fruit", "vegetable", "potato", "carrot", "beet", "milk",
                "cheese", "egg", "cake", "cookie", "stew", "soup", "sugar", "honey")) {
            return MillenaireWorldSimulationBridge.FOOD;
        }
        return UNMAPPED;
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) return true;
        }
        return false;
    }

    public record Adjustment(
            List<TradeGood> catalog,
            int adjustedDirections,
            int fixedOverrides,
            int unmappedGoods) {
        public Adjustment {
            if (catalog == null || adjustedDirections < 0 || fixedOverrides < 0
                    || unmappedGoods < 0) {
                throw new IllegalArgumentException("Invalid dynamic trade adjustment");
            }
        }

        public boolean changed() {
            return adjustedDirections > 0;
        }
    }
}
