package ru.kaiserroman.millenairearmies.server.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenaire.simulation.ShockType;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireVillageIndex;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireWorldSimulationBridge;
import ru.kaiserroman.millenairearmies.integration.millenaire.SimulationBattleImpactAdapter;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;
import ru.kaiserroman.millenairearmies.persistence.StableDimensionTable;

/** Physical Armies facts must become bounded, coalesced and persistent Simulation pressure. */
public final class SimulationBattleImpactAdapterSelfTest {
    private SimulationBattleImpactAdapterSelfTest() {}

    public static void main(String[] args) {
        mapsAndCoalescesBattleConsequences();
        reportsSlowConsumerLoss();
        System.out.println("Simulation battle impact self-test passed");
    }

    private static void mapsAndCoalescesBattleConsequences() {
        PhysicalBattleEventLog log = new PhysicalBattleEventLog(8);
        StableDimensionTable dimensions = new StableDimensionTable();
        int dimension = dimensions.intern(
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));
        SimulationSavedData data = new SimulationSavedData();
        MillenaireWorldSimulationBridge simulation = new MillenaireWorldSimulationBridge(
                new MillenaireVillageIndex(), data, 1, 2_048);
        SimulationBattleImpactAdapter adapter = new SimulationBattleImpactAdapter(
                log, dimensions, simulation);
        long position = new BlockPos(4_200, 70, -2_100).asLong();

        append(log, PhysicalBattleEventLog.CONTACT, 10L, dimension, position, 0);
        append(log, PhysicalBattleEventLog.UNIT_DEFEATED, 20L, dimension, position, 0);
        append(log, PhysicalBattleEventLog.SIEGE_PROGRESS, 30L, dimension, position, 60);
        append(log, PhysicalBattleEventLog.SIEGE_SECURED, 40L, dimension, position, 90);

        check(adapter.tick(8) == 4, "all events consumed");
        check(adapter.processedEventCount() == 4L, "processed metric");
        check(adapter.appliedImpactCount() == 3L, "impact metric");
        check(adapter.ignoredEventCount() == 1L, "contact ignored");
        check(adapter.rejectedImpactCount() == 0L, "no rejected impacts");
        check(data.shocks().size() == 1, "same regional war damage coalesced");
        check(data.shocks().typeAt(0) == ShockType.WAR_DEVASTATION, "war shock type");
        check(data.shocks().magnitudeAt(0) == 730, "coalesced magnitude");
        check(data.shocks().untilCycleAt(0) == 9L, "secured siege duration");
        check(data.keys().dimensionCount() == 1, "dimension bridged once");
    }

    private static void reportsSlowConsumerLoss() {
        PhysicalBattleEventLog log = new PhysicalBattleEventLog(2);
        StableDimensionTable dimensions = new StableDimensionTable();
        int dimension = dimensions.intern(
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));
        SimulationSavedData data = new SimulationSavedData();
        MillenaireWorldSimulationBridge simulation = new MillenaireWorldSimulationBridge(
                new MillenaireVillageIndex(), data, 1, 2_048);
        SimulationBattleImpactAdapter adapter = new SimulationBattleImpactAdapter(
                log, dimensions, simulation);
        long position = new BlockPos(0, 64, 0).asLong();

        append(log, PhysicalBattleEventLog.UNIT_DEFEATED, 1L, dimension, position, 0);
        append(log, PhysicalBattleEventLog.UNIT_DEFEATED, 2L, dimension, position, 0);
        append(log, PhysicalBattleEventLog.UNIT_DEFEATED, 3L, dimension, position, 0);

        check(adapter.tick(8) == 2, "retained events consumed");
        check(adapter.droppedEventCount() == 1L, "overwritten event reported");
        check(adapter.appliedImpactCount() == 2L, "retained impacts applied");
        check(data.shocks().size() == 1, "retained impacts coalesced");
        check(data.shocks().magnitudeAt(0) == 180, "death impact accumulation");
    }

    private static void append(
            PhysicalBattleEventLog log,
            byte kind,
            long gameTime,
            int dimension,
            long position,
            int amount) {
        log.append(
                kind,
                gameTime,
                1,
                2,
                3,
                4,
                5,
                6,
                dimension,
                position,
                amount);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
