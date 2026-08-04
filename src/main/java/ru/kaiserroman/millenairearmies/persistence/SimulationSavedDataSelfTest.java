package ru.kaiserroman.millenairearmies.persistence;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenaire.simulation.SettlementObservation;
import ru.kaiserroman.millenaire.simulation.ShockType;
import ru.kaiserroman.millenaire.simulation.SimulationEvent;
import ru.kaiserroman.millenaire.simulation.SimulationEventType;
import ru.kaiserroman.millenaire.simulation.WorldShock;
import ru.kaiserroman.millenairearmies.ArmiesConfig;

/** Deterministic round-trip and stable-key checks for millenaire_simulation.dat. */
public final class SimulationSavedDataSelfTest {
    private SimulationSavedDataSelfTest() {}

    public static void main(String[] args) {
        SimulationSavedData data = new SimulationSavedData();
        UUID village = UUID.fromString("10000000-0000-0000-0000-000000000001");
        long settlementId = data.keys().internSettlement(village);
        int cultureKey = data.keys().internCulture(
                ResourceLocation.fromNamespaceAndPath("millenaire", "norman"));
        int dimensionKey = data.keys().internDimension(
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));
        check(settlementId == 1L && cultureKey == 1 && dimensionKey == 1, "one-based keys");
        check(data.keys().internSettlement(village) == settlementId, "stable settlement key");

        data.state().beginObservation();
        int row = data.state().observe(new SettlementObservation(
                settlementId,
                cultureKey,
                0L,
                packRegion(dimensionKey, -2, 5),
                64L,
                96L,
                8,
                5,
                700,
                650,
                40,
                500,
                720,
                560,
                440),
                24_000L);
        data.state().finishObservation();
        check(row == 0, "settlement observed");
        long[] flowRemainders = new long[SimulationSavedData.COMMODITY_COUNT];
        long[] priceMoveRemainders = new long[SimulationSavedData.COMMODITY_COUNT];
        for (int commodity = 0; commodity < SimulationSavedData.COMMODITY_COUNT; commodity++) {
            flowRemainders[commodity] = commodity % 2 == 0 ? 100L + commodity : -100L - commodity;
            priceMoveRemainders[commodity] = commodity % 2 == 0
                    ? 10_000L + commodity
                    : -10_000L - commodity;
        }
        data.state().restoreHistoricalState(
                row,
                12_345L,
                345_678L,
                -234_567L,
                111L,
                -222L,
                333L,
                -444L,
                flowRemainders,
                priceMoveRemainders);
        long regionKey = packRegion(dimensionKey, -2, 5);
        data.events().append(new SimulationEvent(
                SimulationEventType.FOUNDING_CANDIDATE,
                settlementId,
                settlementId,
                cultureKey,
                0L,
                regionKey,
                800,
                7,
                12L));
        check(data.shocks().add(new WorldShock(
                        ShockType.WAR_DEVASTATION,
                        0L,
                        regionKey,
                        0,
                        650,
                        4),
                10L),
                "shock recorded");
        check(data.prepareMutationAttempt(1L, 100L), "initial mutation attempt due");
        data.scheduleMutationRetry(1L, 500L);
        check(!data.prepareMutationAttempt(1L, 400L), "mutation retry delayed");
        check(data.mutationAttempts() == 1 && data.nextMutationAttemptTick() == 500L,
                "mutation retry metadata retained");

        CompoundTag saved = data.save(new CompoundTag(), null);
        SimulationSavedData restored = SimulationSavedData.load(saved, null);
        check(restored.keys().settlementCount() == 1, "settlement key restored");
        check(restored.keys().settlement(1L).equals(village), "settlement UUID restored");
        check(restored.keys().culture(1).equals(
                ResourceLocation.fromNamespaceAndPath("millenaire", "norman")),
                "culture restored");
        check(restored.state().size() == 1, "state row restored");
        check(restored.state().populationAt(0) == data.state().populationAt(0), "population parity");
        check(restored.state().priceIndexAt(0, 0) == data.state().priceIndexAt(0, 0), "price parity");
        check(restored.state().historicalTimeRemainderAt(0) == 12_345L,
                "historical time remainder parity");
        check(restored.state().populationGrowthRemainderAt(0) == 345_678L
                        && restored.state().populationObservationRemainderAt(0) == -234_567L,
                "population remainder parity");
        check(restored.state().capitalMoveRemainderAt(0) == 111L
                        && restored.state().productivityMoveRemainderAt(0) == -222L
                        && restored.state().stabilityMoveRemainderAt(0) == 333L
                        && restored.state().attractivenessMoveRemainderAt(0) == -444L,
                "historical index remainder parity");
        for (int commodity = 0; commodity < SimulationSavedData.COMMODITY_COUNT; commodity++) {
            check(restored.state().flowRemainderAt(0, commodity) == flowRemainders[commodity],
                    "commodity flow remainder parity");
            check(restored.state().priceMoveRemainderAt(0, commodity)
                            == priceMoveRemainders[commodity],
                    "commodity price remainder parity");
        }
        check(restored.events().size() == 1 && restored.events().nextSequence() == 2L,
                "journal metadata restored");
        check(restored.shocks().size() == 1
                        && restored.shocks().typeAt(0) == ShockType.WAR_DEVASTATION
                        && restored.shocks().targetRegionKeyAt(0) == regionKey
                        && restored.shocks().magnitudeAt(0) == 650
                        && restored.shocks().untilCycleAt(0) == 15L,
                "shock round-trip parity");
        check(restored.mutationSequence() == 1L
                        && restored.mutationAttempts() == 1
                        && restored.nextMutationAttemptTick() == 500L,
                "mutation retry round-trip parity");
        check(restored.prepareMutationAttempt(1L, 500L), "restored mutation retry becomes due");
        restored.completeMutationAttempt(1L);
        check(restored.mutationSequence() == 0L && restored.mutationAttempts() == 0,
                "completed mutation retry cleared");
        final long[] eventSequence = {0L};
        restored.events().visit((sequence, event) -> {
            eventSequence[0] = sequence;
            check(event.type() == SimulationEventType.FOUNDING_CANDIDATE, "event type parity");
        });
        check(eventSequence[0] == 1L, "event sequence parity");

        CompoundTag legacy = saved.copy();
        legacy.putInt("SchemaVersion", 1);
        ListTag legacySettlements = legacy.getList("Settlements", Tag.TAG_COMPOUND);
        CompoundTag legacySettlement = legacySettlements.getCompound(0);
        legacySettlement.putInt("DeclineCycles", 6);
        legacySettlement.putInt("MissingCycles", 1);
        legacySettlement.putInt("FoundingCooldown", 10);
        SimulationSavedData migrated = SimulationSavedData.load(legacy, null);
        check(migrated.state().declineMilliYearsAt(0)
                        == 6 * ArmiesConfig.WORLD_SIMULATION_ABANDONMENT_GRACE_YEARS * 1000
                                / ArmiesConfig.WORLD_SIMULATION_ABANDONMENT_GRACE_CYCLES,
                "schema-1 decline progress migrated proportionally");
        check(migrated.state().missingMilliYearsAt(0)
                        == ArmiesConfig.WORLD_SIMULATION_MISSING_YEARS_BEFORE_RUIN * 1000
                                / ArmiesConfig.WORLD_SIMULATION_MISSING_CYCLES_BEFORE_RUIN,
                "schema-1 missing progress migrated proportionally");
        check(migrated.state().foundingCooldownMilliYearsAt(0)
                        == 10 * ArmiesConfig.WORLD_SIMULATION_FOUNDING_COOLDOWN_YEARS * 1000
                                / ArmiesConfig.WORLD_SIMULATION_FOUNDING_COOLDOWN_CYCLES,
                "schema-1 founding cooldown migrated proportionally");
        check(migrated.state().historicalTimeRemainderAt(0) == 0L
                        && migrated.state().populationGrowthRemainderAt(0) == 0L
                        && migrated.state().flowRemainderAt(0, 0) == 0L,
                "schema-1 migration starts without fabricated residuals");

        CompoundTag corruptResidual = saved.copy();
        corruptResidual.getList("Settlements", Tag.TAG_COMPOUND)
                .getCompound(0)
                .putLong("PopulationGrowthRemainder", 1_000_000L);
        expectIllegal(
                () -> SimulationSavedData.load(corruptResidual, null),
                "historical residual validation");

        CompoundTag corrupt = saved.copy();
        corrupt.putInt("CommodityCount", SimulationSavedData.COMMODITY_COUNT + 1);
        expectIllegal(() -> SimulationSavedData.load(corrupt, null), "commodity migration gate");
        System.out.println("Simulation SavedData self-test passed");
    }

    private static long packRegion(int dimensionKey, int regionX, int regionZ) {
        return ((long) dimensionKey << 40)
                | ((long) regionX & 0xfffffL) << 20
                | ((long) regionZ & 0xfffffL);
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
