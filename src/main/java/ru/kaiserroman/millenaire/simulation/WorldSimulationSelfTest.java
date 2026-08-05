package ru.kaiserroman.millenaire.simulation;

import java.util.ArrayList;
import java.util.List;

/** Executable assertions for the pure settlement simulation kernel. */
public final class WorldSimulationSelfTest {
    private static final CommodityProfile[] COMMODITIES = {
        new CommodityProfile(100, 5_000, 80_000, 12_000, 1_500, 500),
        new CommodityProfile(300, 1_000, 8_000, 500, 900, 350)
    };

    private WorldSimulationSelfTest() {}

    public static void main(String[] args) {
        prosperousSettlementGeneratesFoundingCandidate();
        scarcityRaisesPriceAndShocksPersist();
        poorSettlementDeclinesAndBecomesAbandonmentCandidate();
        missingSettlementBecomesRuinAndCanRecover();
        refugeeFlowsAreBoundedAndAuditable();
        regionalShocksPropagateDeterministically();
        catchUpAndMemoryStayBounded();
        historicalCadenceAndRemaindersAreDeterministic();
        physicalStockObservationsConvergeWithoutTeleporting();
        journalIsPersistentStyleFifoAndFailsClosed();
        System.out.println("WorldSimulationSelfTest: OK");
    }

    private static void prosperousSettlementGeneratesFoundingCandidate() {
        Fixture fixture = fixture();
        fixture.engine.beginReconciliation();
        int row = fixture.engine.observe(new SettlementObservation(
                1L, 10, 5L, 100L,
                110L, 120L, 12, 10,
                900, 900, 0, 800, 900, 700, 900), 0L);
        fixture.engine.finishReconciliation();
        assert row == 0;
        fixture.engine.tick(60L);
        assert fixture.state.populationAt(row) >= 100L;
        assert fixture.state.productivityAt(row) > 500;
        assert fixture.events.stream().anyMatch(event ->
                event.type() == SimulationEventType.FOUNDING_CANDIDATE);
        assert fixture.state.foundingCooldownAt(row) > 0;
    }

    private static void scarcityRaisesPriceAndShocksPersist() {
        Fixture fixture = fixture();
        fixture.engine.beginReconciliation();
        int row = fixture.engine.observe(new SettlementObservation(
                2L, 10, 0L, 200L,
                80L, 150L, 6, 1,
                100, 300, 200, 200, 500, 450, 100), 0L);
        fixture.engine.finishReconciliation();
        int initial = fixture.state.priceIndexAt(row, 0);
        fixture.state.stockAt(row, 0, 0L);
        assert fixture.engine.addShock(new WorldShock(
                ShockType.HARVEST_FAILURE, 2L, 0L, 0, 900, 3), 0L);
        fixture.engine.tick(10L);
        assert fixture.state.priceIndexAt(row, 0) > initial;
        assert fixture.engine.activeShockCount() == 1;
        fixture.engine.tick(40L);
        assert fixture.engine.activeShockCount() == 0;
        assert fixture.events.stream().anyMatch(event ->
                event.type() == SimulationEventType.PRICE_SHOCK);
    }

    private static void poorSettlementDeclinesAndBecomesAbandonmentCandidate() {
        Fixture fixture = fixture();
        fixture.engine.beginReconciliation();
        int row = fixture.engine.observe(new SettlementObservation(
                3L, 20, 0L, 300L,
                4L, 40L, 2, 0,
                0, 0, 1000, 0, 100, 0, 0), 0L);
        fixture.engine.finishReconciliation();
        fixture.engine.tick(50L);
        assert fixture.state.statusAt(row) == SettlementStatus.ABANDONED;
        assert fixture.events.stream().anyMatch(event ->
                event.type() == SimulationEventType.DECLINE_STARTED);
        assert fixture.events.stream().anyMatch(event ->
                event.type() == SimulationEventType.ABANDONMENT_CANDIDATE);
    }

    private static void missingSettlementBecomesRuinAndCanRecover() {
        Fixture fixture = fixture();
        fixture.engine.beginReconciliation();
        int row = fixture.engine.observe(new SettlementObservation(
                4L, 30, 0L, 400L,
                30L, 50L, 5, 3,
                500, 500, 100, 300, 500, 500, 500), 0L);
        fixture.engine.finishReconciliation();
        fixture.engine.tick(10L);

        fixture.engine.beginReconciliation();
        fixture.engine.finishReconciliation();
        fixture.engine.tick(30L);
        assert fixture.state.statusAt(row) == SettlementStatus.RUINED;
        assert fixture.events.stream().anyMatch(event -> event.type() == SimulationEventType.RUINED);

        fixture.engine.beginReconciliation();
        fixture.engine.observe(new SettlementObservation(
                4L, 30, 0L, 400L,
                12L, 30L, 3, 2,
                600, 700, 100, 400, 600, 600, 500), 30L);
        fixture.engine.finishReconciliation();
        fixture.engine.tick(40L);
        assert fixture.state.statusAt(row) != SettlementStatus.RUINED;
        assert fixture.events.stream().anyMatch(event -> event.type() == SimulationEventType.RECOVERED);
    }

    private static void refugeeFlowsAreBoundedAndAuditable() {
        Fixture fixture = fixture();
        fixture.engine.beginReconciliation();
        int source = fixture.engine.observe(new SettlementObservation(
                10L, 70, 1L, 700L,
                100L, 80L, 8, 3,
                200, 180, 900, 250, 300, 350, 200), 0L);
        int destination = fixture.engine.observe(new SettlementObservation(
                11L, 80, 2L, 800L,
                20L, 60L, 10, 8,
                850, 850, 0, 700, 800, 700, 650), 0L);
        int smallSource = fixture.engine.observe(new SettlementObservation(
                12L, 70, 1L, 700L,
                9L, 20L, 3, 1,
                400, 500, 100, 300, 500, 400, 350), 0L);
        fixture.engine.finishReconciliation();

        long refugees = fixture.engine.relocatePopulation(
                10L, 11L, 20L, MigrationReason.WAR, 0L);
        assert refugees == 20L;
        assert fixture.state.populationAt(source) == 80L;
        assert fixture.state.populationAt(destination) == 40L;
        assert fixture.engine.relocatedPopulationCount() == 20L;
        SimulationEvent event = fixture.events.stream()
                .filter(candidate -> candidate.type() == SimulationEventType.REFUGEE_FLOW)
                .findFirst()
                .orElseThrow();
        assert event.settlementId() == 11L;
        assert event.sourceSettlementId() == 10L;
        assert event.cultureKey() == 70;
        assert event.realmId() == 2L;
        assert event.regionKey() == 800L;
        assert (event.reasonMask() & WorldSimulationEngine.REASON_MIGRATION) != 0;
        assert (event.reasonMask() & MigrationReason.WAR.reasonMask()) != 0;

        long voluntary = fixture.engine.relocatePopulation(
                10L, 11L, 100L, MigrationReason.ECONOMIC, 0L);
        assert voluntary == 9L;
        assert fixture.state.populationAt(source) == 71L;
        assert fixture.state.populationAt(destination) == 49L;
        assert fixture.engine.relocatedPopulationCount() == 29L;

        assert fixture.engine.relocatePopulation(
                12L, 11L, 100L, MigrationReason.ECONOMIC, 0L) == 1L;
        assert fixture.state.populationAt(smallSource) == fixture.policy.minimumViablePopulation();
        assert fixture.engine.relocatePopulation(
                12L, 11L, 100L, MigrationReason.ECONOMIC, 0L) == 0L;
        assert fixture.engine.relocatedPopulationCount() == 30L;

        fixture.state.populationAt(destination, 60L);
        assert fixture.engine.relocatePopulation(
                10L, 11L, 10L, MigrationReason.FAMINE, 0L) == 0L;
        fixture.state.statusAt(destination, SettlementStatus.RUINED);
        assert fixture.engine.relocatePopulation(
                10L, 11L, 10L, MigrationReason.RESETTLEMENT, 0L) == 0L;
    }

    private static void regionalShocksPropagateDeterministically() {
        Fixture fixture = fixture();
        fixture.engine.beginReconciliation();
        fixture.engine.observe(new SettlementObservation(
                20L, 5, 1L, 900L,
                80L, 120L, 8, 5,
                800, 400, 100, 500, 700, 600, 500), 0L);
        fixture.engine.observe(new SettlementObservation(
                21L, 5, 2L, 900L,
                60L, 120L, 10, 7,
                900, 300, 0, 650, 800, 700, 600), 0L);
        fixture.engine.observe(new SettlementObservation(
                22L, 8, 3L, 999L,
                60L, 120L, 10, 7,
                200, 900, 0, 650, 800, 700, 600), 0L);
        fixture.engine.finishReconciliation();

        WorldShock epidemic = new WorldShock(
                ShockType.EPIDEMIC, 20L, 0L, 0, 900, 5);
        assert fixture.engine.propagateShock(
                epidemic,
                21L,
                new ShockPropagationInputs(900, 100, 100, 0),
                0L);
        assert fixture.engine.activeShockCount() == 1;
        assert fixture.engine.shockLedger().typeAt(0) == ShockType.EPIDEMIC;
        assert fixture.engine.shockLedger().targetSettlementIdAt(0) == 21L;
        assert fixture.engine.shockLedger().magnitudeAt(0) == 900;

        assert !fixture.engine.propagateShock(
                epidemic,
                22L,
                new ShockPropagationInputs(100, 900, 900, 0),
                0L);
        assert fixture.engine.activeShockCount() == 1;

        WorldShock harvestFailure = new WorldShock(
                ShockType.HARVEST_FAILURE, 20L, 0L, 0, 800, 4);
        assert fixture.engine.propagateShock(
                harvestFailure,
                21L,
                new ShockPropagationInputs(400, 200, 200, 0),
                0L);
        assert fixture.engine.activeShockCount() == 2;
        assert fixture.engine.shockLedger().typeAt(1) == ShockType.HARVEST_FAILURE;
        assert fixture.engine.shockLedger().magnitudeAt(1) > 500;
        assert !fixture.engine.propagateShock(
                harvestFailure,
                22L,
                new ShockPropagationInputs(0, 900, 900, 4),
                0L);
    }

    private static void catchUpAndMemoryStayBounded() {
        Fixture fixture = fixture();
        fixture.engine.beginReconciliation();
        for (int id = 1; id <= 12; id++) {
            fixture.engine.observe(new SettlementObservation(
                    100L + id, id, 0L, id,
                    20L + id, 80L, 6, 3,
                    500, 600, 0, 400, 600, 500, 500), 0L);
        }
        fixture.engine.finishReconciliation();
        fixture.engine.tick(10_000L);
        int maximumWork = fixture.policy.rowsPerTick() * (fixture.policy.maximumCatchUpCycles() + 1);
        assert fixture.engine.lastTickWorkUnits() <= maximumWork;
        assert fixture.engine.simulatedCycleCount()
                <= (long) fixture.policy.rowsPerTick() * fixture.policy.maximumCatchUpCycles();
        assert fixture.state.estimatedPrimitiveBytes() < 200_000;
    }

    private static void historicalCadenceAndRemaindersAreDeterministic() {
        long historicalYearTicks = 1_728_000L;
        HistoricalFixture fine = historicalFixture(24_000L, historicalYearTicks);
        fine.engine.tick(historicalYearTicks / 2L);
        assert fine.engine.simulatedCycleCount() == 36L;
        PackedSettlementSimulationState restoredState = cloneState(fine.state);
        WorldSimulationEngine restoredEngine = new WorldSimulationEngine(
                restoredState, fine.policy, COMMODITIES, event -> {});

        fine.engine.tick(historicalYearTicks);
        restoredEngine.tick(historicalYearTicks);
        assert fine.engine.simulatedCycleCount() == 72L;
        assertHistoricalStateEqual(fine.state, restoredState);
        assert fine.state.historicalTimeRemainderAt(0) == 0L;
        assert fine.state.populationAt(0) >= 19L && fine.state.populationAt(0) <= 21L;
        assert fine.state.populationAt(0) != 20L
                || fine.state.populationGrowthRemainderAt(0) != 0L;

        HistoricalFixture annual = historicalFixture(historicalYearTicks, historicalYearTicks);
        annual.engine.tick(historicalYearTicks);
        assert annual.engine.simulatedCycleCount() == 1L;
        assert annual.state.historicalTimeRemainderAt(0) == 0L;
        assert Math.abs(fine.state.populationAt(0) - annual.state.populationAt(0)) <= 1L;
        assert Math.abs(fine.state.productiveCapitalAt(0) - annual.state.productiveCapitalAt(0)) <= 5;
        assert Math.abs(fine.state.productivityAt(0) - annual.state.productivityAt(0)) <= 5;
        assert Math.abs(fine.state.stabilityAt(0) - annual.state.stabilityAt(0)) <= 6;
        assert Math.abs(fine.state.attractivenessAt(0) - annual.state.attractivenessAt(0)) <= 6;
    }

    private static HistoricalFixture historicalFixture(
            long technicalCycleTicks,
            long historicalYearTicks) {
        SimulationPolicy policy = new SimulationPolicy(
                4,
                COMMODITIES.length,
                technicalCycleTicks,
                historicalYearTicks,
                4,
                8,
                25,
                5,
                30,
                80,
                8,
                128);
        PackedSettlementSimulationState state = new PackedSettlementSimulationState(
                policy.maximumSettlements(), policy.commodityCount());
        WorldSimulationEngine engine = new WorldSimulationEngine(
                state, policy, COMMODITIES, event -> {});
        engine.beginReconciliation();
        int row = engine.observe(new SettlementObservation(
                90L,
                9,
                0L,
                9_000L,
                20L,
                60L,
                6,
                5,
                700,
                800,
                0,
                700,
                800,
                900,
                700), 0L);
        engine.finishReconciliation();
        assert row == 0;
        return new HistoricalFixture(policy, state, engine);
    }

    private static PackedSettlementSimulationState cloneState(
            PackedSettlementSimulationState source) {
        PackedSettlementSimulationState target = new PackedSettlementSimulationState(
                4, source.commodityCount());
        long[] stocks = new long[source.commodityCount()];
        int[] prices = new int[source.commodityCount()];
        long[] flows = new long[source.commodityCount()];
        long[] flowRemainders = new long[source.commodityCount()];
        long[] priceRemainders = new long[source.commodityCount()];
        for (int commodity = 0; commodity < source.commodityCount(); commodity++) {
            stocks[commodity] = source.stockAt(0, commodity);
            prices[commodity] = source.priceIndexAt(0, commodity);
            flows[commodity] = source.netFlowAt(0, commodity);
            flowRemainders[commodity] = source.flowRemainderAt(0, commodity);
            priceRemainders[commodity] = source.priceMoveRemainderAt(0, commodity);
        }
        int row = target.restoreRow(
                source.settlementIdAt(0),
                source.cultureKeyAt(0),
                source.realmIdAt(0),
                source.regionKeyAt(0),
                source.observedPopulationAt(0),
                source.housingCapacityAt(0),
                source.buildingCountAt(0),
                source.productiveBuildingsAt(0),
                source.marketAccessAt(0),
                source.securityAt(0),
                source.damageAt(0),
                source.educationAt(0),
                source.geographicCapacityAt(0),
                source.fertilityAt(0),
                source.specializationAt(0),
                source.populationAt(0),
                source.productivityAt(0),
                source.stabilityAt(0),
                source.attractivenessAt(0),
                source.productiveCapitalAt(0),
                source.statusAt(0),
                source.tierAt(0),
                source.declineMilliYearsAt(0),
                source.missingMilliYearsAt(0),
                source.foundingCooldownMilliYearsAt(0),
                source.nextDueTickAt(0),
                source.physicallyPresentAt(0),
                stocks,
                prices,
                flows);
        target.restoreHistoricalState(
                row,
                source.historicalTimeRemainderAt(0),
                source.populationGrowthRemainderAt(0),
                source.populationObservationRemainderAt(0),
                source.capitalMoveRemainderAt(0),
                source.productivityMoveRemainderAt(0),
                source.stabilityMoveRemainderAt(0),
                source.attractivenessMoveRemainderAt(0),
                flowRemainders,
                priceRemainders);
        target.restoreRevision(source.revision());
        return target;
    }

    private static void assertHistoricalStateEqual(
            PackedSettlementSimulationState expected,
            PackedSettlementSimulationState actual) {
        assert expected.populationAt(0) == actual.populationAt(0);
        assert expected.productivityAt(0) == actual.productivityAt(0);
        assert expected.stabilityAt(0) == actual.stabilityAt(0);
        assert expected.attractivenessAt(0) == actual.attractivenessAt(0);
        assert expected.productiveCapitalAt(0) == actual.productiveCapitalAt(0);
        assert expected.declineMilliYearsAt(0) == actual.declineMilliYearsAt(0);
        assert expected.foundingCooldownMilliYearsAt(0)
                == actual.foundingCooldownMilliYearsAt(0);
        assert expected.nextDueTickAt(0) == actual.nextDueTickAt(0);
        assert expected.historicalTimeRemainderAt(0)
                == actual.historicalTimeRemainderAt(0);
        assert expected.populationGrowthRemainderAt(0)
                == actual.populationGrowthRemainderAt(0);
        assert expected.populationObservationRemainderAt(0)
                == actual.populationObservationRemainderAt(0);
        assert expected.capitalMoveRemainderAt(0) == actual.capitalMoveRemainderAt(0);
        assert expected.productivityMoveRemainderAt(0)
                == actual.productivityMoveRemainderAt(0);
        assert expected.stabilityMoveRemainderAt(0) == actual.stabilityMoveRemainderAt(0);
        assert expected.attractivenessMoveRemainderAt(0)
                == actual.attractivenessMoveRemainderAt(0);
        for (int commodity = 0; commodity < expected.commodityCount(); commodity++) {
            assert expected.stockAt(0, commodity) == actual.stockAt(0, commodity);
            assert expected.priceIndexAt(0, commodity) == actual.priceIndexAt(0, commodity);
            assert expected.netFlowAt(0, commodity) == actual.netFlowAt(0, commodity);
            assert expected.flowRemainderAt(0, commodity)
                    == actual.flowRemainderAt(0, commodity);
            assert expected.priceMoveRemainderAt(0, commodity)
                    == actual.priceMoveRemainderAt(0, commodity);
        }
    }

    private static void physicalStockObservationsConvergeWithoutTeleporting() {
        Fixture fixture = fixture();
        fixture.engine.beginReconciliation();
        int row = fixture.engine.observe(new SettlementObservation(
                700L, 7, 0L, 7_000L,
                40L, 80L, 8, 6,
                700, 700, 0, 600, 700, 600, 500), 0L);
        fixture.engine.finishReconciliation();
        long initial = fixture.state.stockAt(row, 0);
        assert fixture.engine.observePhysicalStock(row, 0, initial + 100L, 250);
        assert fixture.state.stockAt(row, 0) == initial + 25L;
        assert fixture.engine.observePhysicalStock(row, 0, 10L, 1000);
        assert fixture.state.stockAt(row, 0) == 10L;
        assert !fixture.engine.observePhysicalStock(row, 0, 10L, 500);
    }

    private static void journalIsPersistentStyleFifoAndFailsClosed() {
        SimulationEventJournal journal = new SimulationEventJournal(2);
        SimulationEvent first = new SimulationEvent(
                SimulationEventType.FOUNDING_CANDIDATE, 1L, 1L, 2, 0L, 3L, 700, 4, 5L);
        SimulationEvent second = new SimulationEvent(
                SimulationEventType.DECLINE_STARTED, 2L, 0L, 2, 0L, 3L, 600, 8, 6L);
        assert journal.append(first) == 1L;
        assert journal.append(second) == 2L;
        assert journal.append(first) == 0L;
        assert journal.droppedEventCount() == 1L;
        List<Long> sequences = new ArrayList<>();
        journal.visit((sequence, event) -> sequences.add(sequence));
        assert sequences.equals(List.of(1L, 2L));
        List<Long> head = new ArrayList<>();
        assert journal.visitHead((sequence, event) -> head.add(sequence));
        assert head.equals(List.of(1L));
        assert journal.acknowledgeThrough(1L) == 1;
        assert journal.size() == 1;

        SimulationEventJournal restored = new SimulationEventJournal(2);
        restored.restore(2L, second);
        restored.restoreMetadata(3L, 1L);
        assert restored.nextSequence() == 3L;
        assert restored.droppedEventCount() == 1L;
        assert restored.append(first) == 3L;
    }

    private static Fixture fixture() {
        SimulationPolicy policy = new SimulationPolicy(
                32, COMMODITIES.length, 10L, 10L, 16,
                2, 4, 2, 3, 80, 8, 32);
        PackedSettlementSimulationState state = new PackedSettlementSimulationState(
                policy.maximumSettlements(), policy.commodityCount());
        List<SimulationEvent> events = new ArrayList<>();
        WorldSimulationEngine engine = new WorldSimulationEngine(state, policy, COMMODITIES, events::add);
        return new Fixture(policy, state, engine, events);
    }

    private record HistoricalFixture(
            SimulationPolicy policy,
            PackedSettlementSimulationState state,
            WorldSimulationEngine engine) {
    }

    private record Fixture(
            SimulationPolicy policy,
            PackedSettlementSimulationState state,
            WorldSimulationEngine engine,
            List<SimulationEvent> events) {
    }
}
