package ru.kaiserroman.millenairearmies.integration.millenaire;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenaire.simulation.CommodityProfile;
import ru.kaiserroman.millenaire.simulation.PackedSettlementSimulationState;
import ru.kaiserroman.millenaire.simulation.SettlementStatus;
import ru.kaiserroman.millenaire.simulation.SettlementTier;
import ru.kaiserroman.millenaire.simulation.ShockType;
import ru.kaiserroman.millenaire.simulation.SimulationEventType;
import ru.kaiserroman.millenaire.simulation.SimulationPolicy;
import ru.kaiserroman.millenaire.simulation.WorldShock;
import ru.kaiserroman.millenaire.simulation.WorldSimulationEngine;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;

/** Real Millenaire adapter boundary: packed regions drive bounded spillover and refugee events. */
public final class MillenaireRegionalDynamicsServiceSelfTest {
    private MillenaireRegionalDynamicsServiceSelfTest() {}

    public static void main(String[] args) {
        SimulationSavedData data = new SimulationSavedData();
        SimulationPolicy policy = new SimulationPolicy(
                32,
                SimulationSavedData.COMMODITY_COUNT,
                10L,
                10L,
                16,
                2,
                4,
                2,
                3,
                80,
                8,
                32);
        CommodityProfile[] commodities = new CommodityProfile[SimulationSavedData.COMMODITY_COUNT];
        for (int index = 0; index < commodities.length; index++) {
            commodities[index] = new CommodityProfile(
                    100 + index * 20,
                    1_000,
                    2_000,
                    300,
                    1_000,
                    250);
        }
        WorldSimulationEngine engine = new WorldSimulationEngine(
                data.state(),
                policy,
                commodities,
                data.events()::append,
                data.shocks());
        int dimension = data.keys().internDimension(
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));
        int culture = data.keys().internCulture(
                ResourceLocation.fromNamespaceAndPath("millenaire", "norman"));
        int remoteCulture = data.keys().internCulture(
                ResourceLocation.fromNamespaceAndPath("millenaire", "byzantine"));
        long sourceId = data.keys().internSettlement(uuid(1));
        long destinationId = data.keys().internSettlement(uuid(2));
        long remoteId = data.keys().internSettlement(uuid(3));
        long famineId = data.keys().internSettlement(uuid(4));
        int regionSize = 512;
        long sourceRegion = MillenaireWorldSimulationBridge.packRegion(
                dimension, new BlockPos(10, 64, 10), regionSize);
        long destinationRegion = MillenaireWorldSimulationBridge.packRegion(
                dimension, new BlockPos(530, 64, 10), regionSize);
        long remoteRegion = MillenaireWorldSimulationBridge.packRegion(
                dimension, new BlockPos(10_500, 64, 10), regionSize);
        long famineRegion = MillenaireWorldSimulationBridge.packRegion(
                dimension, new BlockPos(-530, 64, 10), regionSize);

        int sourceRow = restore(
                data.state(), sourceId, culture, 1L, sourceRegion,
                100L, 120L, 650, 300, 450, false);
        int destinationRow = restore(
                data.state(), destinationId, culture, 1L, destinationRegion,
                30L, 160L, 900, 700, 900, false);
        int remoteRow = restore(
                data.state(), remoteId, remoteCulture, 2L, remoteRegion,
                30L, 160L, 900, 900, 900, false);
        int famineRow = restore(
                data.state(), famineId, culture, 3L, famineRegion,
                50L, 120L, 300, 900, 500, true);

        MillenaireRegionalDynamicsService service = new MillenaireRegionalDynamicsService(
                new MillenaireVillageIndex(),
                data,
                engine,
                true,
                true,
                true,
                4,
                2,
                4_096,
                regionSize,
                2,
                16,
                2,
                10L);
        check(service.applyShock(
                        new WorldShock(
                                ShockType.EPIDEMIC,
                                sourceId,
                                0L,
                                0,
                                1_000,
                                5),
                        0L),
                "source shock accepted");

        check(data.shocks().size() == 2, "one nearby settlement received propagated epidemic");
        check(data.shocks().targetSettlementIdAt(0) == sourceId, "source shock persisted");
        check(data.shocks().targetSettlementIdAt(1) == destinationId,
                "nearby culturally linked settlement targeted");
        check(data.shocks().typeAt(1) == ShockType.EPIDEMIC
                        && data.shocks().magnitudeAt(1) >= 700,
                "propagated epidemic retained bounded strength");
        check(data.state().populationAt(sourceRow) == 90L, "refugees left affected settlement");
        check(data.state().populationAt(destinationRow) == 40L,
                "refugees entered attractive settlement with housing");
        check(data.state().populationAt(remoteRow) == 30L, "remote settlement unaffected");
        check(service.acceptedShockCount() == 1L, "accepted shock metric");
        check(service.propagatedShockCount() == 1L, "propagation metric");
        check(service.refugeeFlowCount() == 1L, "refugee flow metric");
        check(service.relocatedPopulationCount() == 10L, "relocated population metric");

        int[] refugeeEvents = {0};
        data.events().visit((sequence, event) -> {
            if (event.type() == SimulationEventType.REFUGEE_FLOW) {
                refugeeEvents[0]++;
                check(event.sourceSettlementId() == sourceId, "refugee event source");
                check(event.settlementId() == destinationId, "refugee event destination");
                check(event.cultureKey() == culture, "refugee culture retained");
                check(event.realmId() == 1L, "receiving Realm recorded");
            }
        });
        check(refugeeEvents[0] == 1, "one auditable refugee event emitted");

        check(service.tick(0L) == 4, "endogenous evaluation obeyed row budget");
        check(service.endogenousShockCount() == 1L, "one endogenous crisis generated");
        boolean harvestFailureFound = false;
        for (int row = 0; row < data.shocks().size(); row++) {
            if (data.shocks().typeAt(row) == ShockType.HARVEST_FAILURE
                    && data.shocks().targetSettlementIdAt(row) == famineId) {
                harvestFailureFound = true;
            }
        }
        check(harvestFailureFound, "food deficit generated a settlement harvest failure");
        check(data.state().populationAt(famineRow) < 50L,
                "endogenous famine produced a refugee outflow");
        check(service.relocatedPopulationCount() > 10L,
                "endogenous crisis increased relocated population metric");
        int[] famineEvents = {0};
        data.events().visit((sequence, event) -> {
            if (event.type() == SimulationEventType.REFUGEE_FLOW
                    && event.sourceSettlementId() == famineId) {
                famineEvents[0]++;
            }
        });
        check(famineEvents[0] == 1, "endogenous famine emitted an auditable refugee event");

        check(MillenaireRegionalDynamicsService.regionDimension(sourceRegion) == dimension,
                "packed region dimension round-trip");
        check(MillenaireRegionalDynamicsService.regionX(destinationRegion) == 1,
                "packed region X round-trip");
        check(MillenaireRegionalDynamicsService.regionZ(destinationRegion) == 0,
                "packed region Z round-trip");
        System.out.println("Millenaire regional dynamics self-test passed");
    }

    private static int restore(
            PackedSettlementSimulationState state,
            long settlementId,
            int culture,
            long realm,
            long region,
            long population,
            long housing,
            int market,
            int security,
            int attractiveness,
            boolean foodScarcity) {
        long[] stocks = new long[SimulationSavedData.COMMODITY_COUNT];
        int[] prices = new int[SimulationSavedData.COMMODITY_COUNT];
        long[] flows = new long[SimulationSavedData.COMMODITY_COUNT];
        for (int commodity = 0; commodity < prices.length; commodity++) {
            stocks[commodity] = population * 4L;
            prices[commodity] = 100 + commodity * 20;
        }
        if (foodScarcity) {
            stocks[0] = 0L;
            flows[0] = -Math.max(1L, population / 5L);
        }
        return state.restoreRow(
                settlementId,
                culture,
                realm,
                region,
                population,
                housing,
                12,
                8,
                market,
                security,
                0,
                600,
                800,
                600,
                500,
                population,
                650,
                700,
                attractiveness,
                600,
                SettlementStatus.ACTIVE,
                SettlementTier.forPopulation(population),
                0,
                0,
                0,
                10L,
                true,
                stocks,
                prices,
                flows);
    }

    private static UUID uuid(long least) {
        return new UUID(0x6000000000000000L, least);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
