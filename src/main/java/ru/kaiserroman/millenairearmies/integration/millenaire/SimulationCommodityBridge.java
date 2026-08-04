package ru.kaiserroman.millenairearmies.integration.millenaire;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;

/**
 * Concrete item representation for the eight coarse Simulation commodities.
 *
 * <p>The strategic ledger deliberately remains culture-neutral. This bridge selects one stable,
 * vanilla representative item per commodity so real Millenaire inventories can both seed and
 * receive simulated stock without requiring private Millenaire internals or culture-specific
 * content lookups. Non-stackable tools and high-value luxuries use a larger strategic conversion
 * factor and a smaller physical cap.</p>
 */
public final class SimulationCommodityBridge {
    /** Strategic stock represented by one physical item. */
    private static final int[] VIRTUAL_UNITS_PER_ITEM = {
        1, 1, 1, 1, 1, 10, 1, 10
    };

    private static final int[] DEFAULT_PHYSICAL_CAPS = {
        4_096, 4_096, 4_096, 4_096, 2_048, 64, 4_096, 256
    };

    private SimulationCommodityBridge() {}

    public static Item item(int commodity) {
        checkCommodity(commodity);
        return ItemHolder.ITEMS[commodity];
    }

    public static int virtualUnitsPerPhysicalItem(int commodity) {
        checkCommodity(commodity);
        return VIRTUAL_UNITS_PER_ITEM[commodity];
    }

    public static int defaultPhysicalCap(int commodity) {
        checkCommodity(commodity);
        return DEFAULT_PHYSICAL_CAPS[commodity];
    }

    public static long virtualEquivalent(int commodity, long physicalItems) {
        checkCommodity(commodity);
        if (physicalItems < 0L) throw new IllegalArgumentException("Negative physical stock");
        int factor = VIRTUAL_UNITS_PER_ITEM[commodity];
        return physicalItems > Long.MAX_VALUE / factor ? Long.MAX_VALUE : physicalItems * factor;
    }

    public static int physicalTarget(int commodity, long virtualStock, int configuredCap) {
        checkCommodity(commodity);
        if (virtualStock < 0L || configuredCap <= 0) {
            throw new IllegalArgumentException("Invalid physical target input");
        }
        long target = virtualStock / VIRTUAL_UNITS_PER_ITEM[commodity];
        int cap = Math.min(configuredCap, DEFAULT_PHYSICAL_CAPS[commodity]);
        return (int) Math.min(target, cap);
    }

    private static final class ItemHolder {
        private static final Item[] ITEMS = {
            Items.BREAD,
            Items.OAK_LOG,
            Items.COBBLESTONE,
            Items.IRON_INGOT,
            Items.LEATHER,
            Items.IRON_PICKAXE,
            Items.ARROW,
            Items.EMERALD
        };
    }

    private static void checkCommodity(int commodity) {
        if (commodity < 0 || commodity >= SimulationSavedData.COMMODITY_COUNT) {
            throw new IllegalArgumentException("Unknown Simulation commodity " + commodity);
        }
    }
}
