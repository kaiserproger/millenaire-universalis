package ru.kaiserroman.millenairearmies.integration.millenaire;

import java.util.List;
import org.millenaire.commerce.TradeGood;
import ru.kaiserroman.millenaire.simulation.PackedSettlementSimulationState;
import ru.kaiserroman.millenaire.simulation.SettlementStatus;
import ru.kaiserroman.millenaire.simulation.SettlementTier;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;

/** Deterministic mapping, multiplier bounds, spread preservation and fallback checks. */
public final class SimulationTradePricePolicySelfTest {
    private SimulationTradePricePolicySelfTest() {}

    public static void main(String[] args) {
        PackedSettlementSimulationState state = new PackedSettlementSimulationState(
                4, SimulationSavedData.COMMODITY_COUNT);
        long[] stocks = new long[SimulationSavedData.COMMODITY_COUNT];
        int[] prices = new int[SimulationSavedData.COMMODITY_COUNT];
        long[] flows = new long[SimulationSavedData.COMMODITY_COUNT];
        for (int commodity = 0; commodity < prices.length; commodity++) {
            prices[commodity] = MillenaireWorldSimulationBridge.commodityBasePrice(commodity);
        }
        prices[MillenaireWorldSimulationBridge.FOOD] = 200;
        prices[MillenaireWorldSimulationBridge.IRON] = 125;
        prices[MillenaireWorldSimulationBridge.TOOLS] = 960;
        state.restoreRow(
                1L, 1, 0L, 1L, 100L, 120L, 10, 8,
                700, 700, 0, 500, 700, 600, 500,
                100L, 700, 700, 700, 700,
                SettlementStatus.ACTIVE, SettlementTier.TOWN,
                0, 0, 0, 10L, true, stocks, prices, flows);

        TradeGood bread = good("bread", "minecraft:bread", 100, 50, "food");
        TradeGood iron = good("iron_ingot", "minecraft:iron_ingot", 100, 40, "metal");
        TradeGood pickaxe = good("work_pickaxe", "minecraft:iron_pickaxe", 300, 150, "tool");
        TradeGood mystery = good("paperwork", "minecraft:paper", 80, 20, "misc");
        SimulationTradePricePolicy policy = new SimulationTradePricePolicy(500, 3_000, 1_000);
        SimulationTradePricePolicy.Adjustment adjustment = policy.adjustCatalog(
                state, 0, null, List.of(bread, iron, pickaxe, mystery));

        check(adjustment.catalog().size() == 4, "catalog row count retained");
        check(adjustment.adjustedDirections() == 6, "mapped goods adjust both directions");
        check(adjustment.fixedOverrides() == 0, "no fixed overrides without VillageType");
        check(adjustment.unmappedGoods() == 1, "unknown trade good counted");
        check(adjustment.catalog().get(0).sellingPrice() == 200
                        && adjustment.catalog().get(0).buyingPrice() == 100,
                "food scarcity doubles both prices");
        check(adjustment.catalog().get(1).sellingPrice() == 50
                        && adjustment.catalog().get(1).buyingPrice() == 20,
                "iron abundance reaches minimum multiplier");
        check(adjustment.catalog().get(2).sellingPrice() == 900
                        && adjustment.catalog().get(2).buyingPrice() == 450,
                "tool scarcity reaches maximum multiplier");
        check(adjustment.catalog().get(3) == mystery,
                "unmapped good retains original row");
        check(SimulationTradePricePolicy.commodityFor(bread)
                        == MillenaireWorldSimulationBridge.FOOD,
                "food mapping");
        check(SimulationTradePricePolicy.commodityFor(iron)
                        == MillenaireWorldSimulationBridge.IRON,
                "iron mapping");
        check(SimulationTradePricePolicy.commodityFor(pickaxe)
                        == MillenaireWorldSimulationBridge.TOOLS,
                "tool mapping wins before iron item text");
        check(SimulationTradePricePolicy.commodityFor(mystery)
                        == SimulationTradePricePolicy.UNMAPPED,
                "unknown mapping fallback");
        check(policy.adjustPrice(800, 100_000, 100) == 1_000,
                "absolute price cap");
        check(policy.adjustPrice(0, 200, 100) == 0,
                "zero price retained");
        expectIllegal(() -> new SimulationTradePricePolicy(0, 1_000, 100),
                "invalid multiplier lower bound");
        expectIllegal(() -> policy.adjustCatalog(state, 1, null, List.of(bread)),
                "unknown settlement row");
        System.out.println("Simulation dynamic trade price policy self-test passed");
    }

    private static TradeGood good(
            String id, String item, int selling, int buying, String category) {
        return new TradeGood(
                id, item, selling, buying, 0, 0, false, 0, category, false, 0);
    }

    private static void expectIllegal(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
