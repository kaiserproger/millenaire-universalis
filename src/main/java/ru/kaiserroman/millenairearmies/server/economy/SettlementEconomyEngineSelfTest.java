package ru.kaiserroman.millenairearmies.server.economy;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import ru.kaiserroman.millenairearmies.persistence.PackedSettlementEconomyState;
import ru.kaiserroman.millenairearmies.persistence.PackedCommandState;
import ru.kaiserroman.millenairearmies.persistence.PackedLogisticsState;
import ru.kaiserroman.millenairearmies.server.logistics.StrategicLogisticsEngine;

/** Run with assertions enabled; covers the settlement economy conservation and budget contract. */
public final class SettlementEconomyEngineSelfTest {
    private SettlementEconomyEngineSelfTest() {}

    public static void main(String[] args) {
        conservationAndDuplicateCompletion();
        crashRestartMidShipment();
        unloadedSettlementCatchup();
        tradeEquilibrium();
        terminalWalRowsAreReused();
        shortagesBlockRecruitmentAndArmySupply();
        garrisonUpkeepIsSettlementLocalAndAtomic();
        strategicLogisticsDispatchDebitsSettlement();
        hundredSettlementMemoryAndAllocationBudget();
        System.out.println("Settlement economy self-test passed");
    }

    private static void conservationAndDuplicateCompletion() {
        Scenario scenario = new Scenario(10, 8, 8, 4, 64);
        int origin = scenario.settlement(1L, 11L, 0, 0);
        int destination = scenario.settlement(2L, 22L, 16, 0);
        scenario.rates(origin, SettlementEconomyEngine.FOOD, 20, 0, 0);
        scenario.rates(destination, SettlementEconomyEngine.FOOD, 20, 0, 0);
        scenario.observeAll(origin, 100);
        scenario.observeAll(destination, 0);
        scenario.engine.projectionReady();

        scenario.engine.tick(10L);
        check(scenario.state.shipmentCount() == 1, "deficit created one shipment");
        check(scenario.state.stockAt(origin, SettlementEconomyEngine.FOOD) == 80,
                "origin is debited exactly once");
        check(total(scenario.state, SettlementEconomyEngine.FOOD) == 100,
                "in-transit cargo remains conserved");
        scenario.engine.tick(100L);
        check(total(scenario.state, SettlementEconomyEngine.FOOD) == 100,
                "delivery conserves settlement plus cargo stock");
        long hash = scenario.state.deterministicHash();
        scenario.engine.tick(100L);
        check(scenario.state.deterministicHash() == hash, "duplicate tick cannot redeliver");
        check(scenario.engine.deliveredShipmentCount() == 1L, "one delivery committed");
    }

    private static void crashRestartMidShipment() {
        Scenario before = new Scenario(10, 8, 8, 4, 64);
        int origin = before.settlement(3L, 33L, 0, 0);
        int destination = before.settlement(4L, 44L, 32, 0);
        before.rates(origin, SettlementEconomyEngine.IRON, 10, 0, 0);
        before.rates(destination, SettlementEconomyEngine.IRON, 30, 0, 0);
        before.observeAll(origin, 50);
        before.observeAll(destination, 0);
        before.engine.projectionReady();
        before.engine.tick(10L);
        check(before.state.shipmentStatusAt(0) == PackedSettlementEconomyState.SHIPMENT_IN_TRANSIT,
                "WAL persisted in-transit phase");

        CompoundTag encoded = before.state.save(new CompoundTag());
        PackedSettlementEconomyState restored = PackedSettlementEconomyState.load(encoded, 16, 64);
        long[] dirties = {0L};
        SettlementEconomyEngine after = engine(restored, dirties, 10, 8, 8, 4, 64);
        after.projectionReady();
        after.tick(200L);
        check(restored.shipmentStatusAt(0) == PackedSettlementEconomyState.SHIPMENT_DELIVERED,
                "restart completed existing WAL row");
        check(total(restored, SettlementEconomyEngine.IRON) == 50,
                "restart did not duplicate or lose cargo");
        long hash = restored.deterministicHash();
        after.tick(201L);
        check(restored.deterministicHash() == hash, "terminal WAL row is idempotent");
    }

    private static void unloadedSettlementCatchup() {
        Scenario scenario = new Scenario(20, 4, 4, 1, 16);
        int row = scenario.settlement(5L, 55L, 0, 0);
        int unloadedReceiver = scenario.settlement(50L, 550L, 16, 0);
        scenario.rates(row, SettlementEconomyEngine.FOOD, 0, 3, 1);
        scenario.rates(unloadedReceiver, SettlementEconomyEngine.FOOD, 10, 0, 0);
        scenario.observeAll(row, 10);
        scenario.observeAll(unloadedReceiver, 0);
        check(!scenario.engine.observePhysicalStock(row, SettlementEconomyEngine.FOOD, -1),
                "unloaded inventory snapshot rejected");
        check(!scenario.engine.observePhysicalStock(unloadedReceiver, SettlementEconomyEngine.FOOD, -1),
                "unloaded receiver retains its ledger row");
        scenario.engine.projectionReady();
        scenario.engine.tick(220L);
        check(scenario.state.stockAt(row, SettlementEconomyEngine.FOOD) == 22,
                "unloaded trade debit uses retained ledger stock");
        scenario.engine.tick(1_000L);
        check(scenario.state.stockAt(unloadedReceiver, SettlementEconomyEngine.FOOD) == 10,
                "coarse shipment reaches an unloaded settlement without chunk loading");
        check(scenario.state.stockAt(row, SettlementEconomyEngine.FOOD) == 100,
                "eleven dormant cycles caught up arithmetically");
        check(scenario.engine.producedCycles() == 100L, "catchup records cycles, not per-tick simulation");
    }

    private static void tradeEquilibrium() {
        Scenario scenario = new Scenario(10, 8, 8, 4, 64);
        int producer = scenario.settlement(6L, 66L, 0, 0);
        int consumer = scenario.settlement(7L, 77L, 64, 0);
        scenario.rates(producer, SettlementEconomyEngine.LEATHER, 20, 0, 0);
        scenario.rates(consumer, SettlementEconomyEngine.LEATHER, 50, 0, 0);
        scenario.observeAll(producer, 120);
        scenario.observeAll(consumer, 0);
        scenario.engine.projectionReady();
        scenario.engine.tick(10L);
        check(scenario.state.shipmentCount() == 1, "coarse route planned");
        scenario.engine.tick(500L);
        check(scenario.state.stockAt(consumer, SettlementEconomyEngine.LEATHER) == 50,
                "consumer reserve filled, no oscillating overship");
        check(scenario.state.stockAt(producer, SettlementEconomyEngine.LEATHER) == 70,
                "producer retained its reserve and remainder");
        check(scenario.state.shipmentCount() == 1, "equilibrium published no duplicate route");
    }

    private static void shortagesBlockRecruitmentAndArmySupply() {
        Scenario scenario = new Scenario(10, 4, 4, 1, 16);
        int row = scenario.settlement(8L, 88L, 0, 0);
        for (int commodity = 0; commodity < PackedSettlementEconomyState.COMMODITY_COUNT; commodity++) {
            scenario.rates(row, commodity, 10, 0, 0);
            scenario.engine.observePhysicalStock(row, commodity, 10);
        }
        scenario.engine.projectionReady();
        check(scenario.engine.factionSupplyPercent(1) == 0,
                "existing army UI supply metric exposes reserve-only shortage");
        check(!scenario.engine.tryConsumeRecruitmentKit(8L, 88L),
                "reserves block recruitment");
        int foodKey = scenario.state.commodityItemKey(SettlementEconomyEngine.FOOD);
        check(!scenario.engine.tryDebit(1, 0, foodKey, 1), "reserves block army dispatch");

        for (int commodity = 0; commodity < PackedSettlementEconomyState.COMMODITY_COUNT; commodity++) {
            scenario.engine.observePhysicalStock(row, commodity, 100);
        }
        check(scenario.engine.factionSupplyPercent(1) == 100,
                "army UI supply metric recovers with surplus");
        check(scenario.engine.tryConsumeRecruitmentKit(8L, 88L), "surplus equips recruit atomically");
        check(scenario.state.stockAt(row, SettlementEconomyEngine.FOOD) == 92, "food kit debited");
        check(scenario.state.stockAt(row, SettlementEconomyEngine.IRON) == 99, "iron kit debited");
        check(scenario.engine.tryDebit(1, 0, foodKey, 20), "surplus supplies army");
        check(scenario.state.stockAt(row, SettlementEconomyEngine.FOOD) == 72, "army debit committed");
    }

    private static void garrisonUpkeepIsSettlementLocalAndAtomic() {
        Scenario scenario = new Scenario(10, 4, 4, 1, 16);
        int garrison = scenario.settlement(81L, 810L, 0, 0);
        int remote = scenario.settlement(82L, 820L, 32, 0);
        scenario.rates(garrison, SettlementEconomyEngine.FOOD, 10, 0, 0);
        scenario.rates(garrison, SettlementEconomyEngine.ARROWS, 10, 0, 0);
        scenario.rates(remote, SettlementEconomyEngine.FOOD, 10, 0, 0);
        scenario.rates(remote, SettlementEconomyEngine.ARROWS, 10, 0, 0);
        scenario.observeAll(garrison, 0);
        scenario.observeAll(remote, 100);
        scenario.engine.observePhysicalStock(garrison, SettlementEconomyEngine.FOOD, 50);
        scenario.engine.observePhysicalStock(garrison, SettlementEconomyEngine.ARROWS, 20);
        scenario.engine.projectionReady();

        check(!scenario.engine.tryConsumeGarrisonUpkeep(81L, 810L, 20, 11),
                "insufficient local arrows reject the entire upkeep debit");
        check(scenario.state.stockAt(garrison, SettlementEconomyEngine.FOOD) == 50
                        && scenario.state.stockAt(garrison, SettlementEconomyEngine.ARROWS) == 20,
                "failed garrison upkeep is atomic");
        check(scenario.state.stockAt(remote, SettlementEconomyEngine.FOOD) == 100,
                "remote same-faction settlement cannot silently feed the garrison");

        check(scenario.engine.tryConsumeGarrisonUpkeep(81L, 810L, 20, 10),
                "local surplus funds one upkeep interval");
        check(scenario.state.stockAt(garrison, SettlementEconomyEngine.FOOD) == 30
                        && scenario.state.stockAt(garrison, SettlementEconomyEngine.ARROWS) == 10,
                "successful debit preserves exact settlement reserves");
        check(scenario.engine.settlementSupplyPercent(81L, 810L) >= 0,
                "bound settlement exposes a coarse supply percentage");
    }

    private static void terminalWalRowsAreReused() {
        Scenario scenario = new Scenario(10, 4, 4, 1, 1);
        int first = scenario.settlement(70L, 700L, 0, 0);
        int second = scenario.settlement(71L, 701L, 16, 0);
        scenario.rates(first, SettlementEconomyEngine.FOOD, 20, 0, 0);
        scenario.rates(second, SettlementEconomyEngine.FOOD, 20, 0, 0);
        scenario.observeAll(first, 100);
        scenario.observeAll(second, 0);
        scenario.engine.projectionReady();
        scenario.engine.tick(10L);
        long firstId = scenario.state.shipmentIdAt(0);
        scenario.engine.tick(100L);
        scenario.rates(first, SettlementEconomyEngine.FOOD, 100, 0, 0);
        scenario.rates(second, SettlementEconomyEngine.FOOD, 0, 0, 0);
        scenario.engine.tick(110L);
        check(scenario.state.shipmentCount() == 1, "terminal WAL row reused at configured cap");
        check(scenario.state.shipmentIdAt(0) > firstId, "reused WAL row received a fresh identity");
        check(scenario.state.shipmentStatusAt(0) == PackedSettlementEconomyState.SHIPMENT_IN_TRANSIT,
                "reused WAL row entered a new one-way transaction");
        PackedSettlementEconomyState restored = PackedSettlementEconomyState.load(
                scenario.state.save(new CompoundTag()), 8, 1);
        check(restored.shipmentIdAt(0) == scenario.state.shipmentIdAt(0),
                "recycled WAL identity survives restart");
    }

    private static void hundredSettlementMemoryAndAllocationBudget() {
        Scenario scenario = new Scenario(1_000, 16, 16, 1, 128);
        for (int index = 0; index < 100; index++) {
            int row = scenario.settlement(1_000L + index, 2_000L + index, index * 8, 0);
            scenario.observeAll(row, 0);
        }
        scenario.engine.projectionReady();
        for (long tick = 1L; tick <= 2_000L; tick++) scenario.engine.tick(tick);

        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long allocated = -1L;
        if (bean.isThreadAllocatedMemorySupported()) {
            bean.setThreadAllocatedMemoryEnabled(true);
            long thread = Thread.currentThread().threadId();
            long before = bean.getThreadAllocatedBytes(thread);
            for (long tick = 2_001L; tick <= 12_000L; tick++) scenario.engine.tick(tick);
            allocated = bean.getThreadAllocatedBytes(thread) - before;
            check(allocated <= 4_096L, "steady 100-settlement ticks allocated " + allocated + " bytes");
        }
        int primitiveBytes = scenario.state.estimatedPrimitiveBytes();
        int runtimeBytes = scenario.engine.estimatedRuntimePrimitiveBytes();
        check(primitiveBytes + runtimeBytes < 96 * 1_024,
                "100-settlement primitive state fits 96 KiB: " + (primitiveBytes + runtimeBytes));
        check(scenario.state.settlementCapacity() < 160, "tiered settlement capacity remains bounded");
        check(scenario.engine.lastTickWorkUnits() <= scenario.engine.maximumTickWorkUnits(),
                "tick stayed inside declared primitive work budget");
        System.out.println("SETTLEMENT_ECONOMY_METRICS settlements=100 primitive_bytes=" + primitiveBytes
                + " hot_alloc_bytes=" + allocated
                + " runtime_bytes=" + runtimeBytes
                + " hash=" + Long.toUnsignedString(scenario.state.deterministicHash())
                + " last_tick_work=" + scenario.engine.lastTickWorkUnits());
    }

    private static void strategicLogisticsDispatchDebitsSettlement() {
        Scenario scenario = new Scenario(1_000, 4, 4, 1, 16);
        int row = scenario.settlement(9L, 99L, 0, 0);
        scenario.rates(row, SettlementEconomyEngine.FOOD, 20, 0, 0);
        scenario.observeAll(row, 100);
        scenario.engine.projectionReady();

        PackedLogisticsState requests = new PackedLogisticsState(1);
        StrategicLogisticsEngine logistics = new StrategicLogisticsEngine(8, 8, 8, 1, 8);
        logistics.start(requests, new PackedCommandState(0), () -> {});
        logistics.installSupplyMutationSink(scenario.engine);
        int foodKey = scenario.state.commodityItemKey(SettlementEconomyEngine.FOOD);
        long request = logistics.requestSupply(1, 123, foodKey, 30, 0, 0L, 0L, (byte) 1);
        check(logistics.publishSupply(1, 0, foodKey, scenario.engine.absoluteAvailableStock(1, 0, foodKey)),
                "settlement surplus published to packed ledger");
        logistics.tick(0L);
        check(logistics.dispatch(request), "assigned army dispatch accepted");
        logistics.tick(1L);
        check(scenario.state.stockAt(row, SettlementEconomyEngine.FOOD) == 70,
                "packed logistics commit debited source settlement");
        check(logistics.inTransitAmount(request) == 30, "army shipment entered transit");
        scenario.engine.observePhysicalStock(row, SettlementEconomyEngine.FOOD, 0);
        check(scenario.state.stockAt(row, SettlementEconomyEngine.FOOD) == 0,
                "physical consumption cannot resurrect or underflow strategic debit");
        check(scenario.engine.physicalReconciliationShortfall() == 30L,
                "physical/strategic double-spend conflict is measured");
        PackedSettlementEconomyState restored = PackedSettlementEconomyState.load(
                scenario.state.save(new CompoundTag()), 16, 16);
        check(restored.physicalReconciliationShortfall() == 30L,
                "physical reconciliation conflict survives restart");
    }

    private static long total(PackedSettlementEconomyState state, int commodity) {
        long total = 0L;
        for (int row = 0; row < state.settlementCount(); row++) total += state.stockAt(row, commodity);
        for (int row = 0; row < state.shipmentCount(); row++) {
            if (state.shipmentStatusAt(row) == PackedSettlementEconomyState.SHIPMENT_IN_TRANSIT) {
                total += state.shipmentAmountAt(row);
            }
        }
        return total;
    }

    private static SettlementEconomyEngine engine(
            PackedSettlementEconomyState state,
            long[] dirties,
            int interval,
            int settlementsPerTick,
            int shipmentsPerTick,
            int routesPerTick,
            int maxShipments) {
        return new SettlementEconomyEngine(
                state, () -> dirties[0]++, interval, settlementsPerTick, shipmentsPerTick,
                routesPerTick, 1_024, maxShipments, 10_000);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Scenario {
        private final PackedSettlementEconomyState state = new PackedSettlementEconomyState();
        private final long[] dirties = {0L};
        private final SettlementEconomyEngine engine;

        private Scenario(int interval, int settlementBudget, int shipmentBudget, int routeBudget, int maxShipments) {
            state.configureCommodityKeys(10, 11, 12, 13);
            engine = engine(state, dirties, interval, settlementBudget, shipmentBudget, routeBudget, maxShipments);
        }

        private int settlement(long most, long least, int x, int z) {
            return engine.registerSettlement(most, least, 1, 0, BlockPos.asLong(x, 64, z), 0L);
        }

        private void rates(int row, int commodity, int reserve, int production, int consumption) {
            engine.configureRates(row, commodity, reserve, production, consumption);
        }

        private void observeAll(int row, int amount) {
            for (int commodity = 0; commodity < PackedSettlementEconomyState.COMMODITY_COUNT; commodity++) {
                engine.observePhysicalStock(row, commodity, amount);
            }
        }
    }
}
