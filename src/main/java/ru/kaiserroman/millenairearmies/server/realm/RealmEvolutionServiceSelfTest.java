package ru.kaiserroman.millenairearmies.server.realm;

import java.util.UUID;
import ru.kaiserroman.millenaire.realm.Constitution;
import ru.kaiserroman.millenaire.realm.GovernmentForm;
import ru.kaiserroman.millenaire.realm.RealmMemberKind;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenaire.simulation.SettlementStatus;
import ru.kaiserroman.millenaire.simulation.SettlementTier;
import ru.kaiserroman.millenaire.simulation.ShockType;
import ru.kaiserroman.millenaire.simulation.WorldShock;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;

/** Simulation aggregates must drive bounded, path-dependent constitutional change. */
public final class RealmEvolutionServiceSelfTest {
    private RealmEvolutionServiceSelfTest() {}

    public static void main(String[] args) {
        RealmSavedData realms = new RealmSavedData();
        long commercialRealm = createRealm(
                realms, 1, GovernmentForm.FEUDAL_MONARCHY, 760, "Harbour March");
        long emergencyRealm = createRealm(
                realms, 2, GovernmentForm.CLAN_CONFEDERATION, 210, "Ash Confederation");
        check(realms.dependencies().establish(
                        emergencyRealm, commercialRealm, 400, 200, 250, 0L),
                "emergency Realm dependency established");
        realms.institutions().ensureRealm(
                commercialRealm,
                Constitution.archetype(GovernmentForm.FEUDAL_MONARCHY, 760),
                0L);
        realms.institutions().update(
                commercialRealm,
                Constitution.archetype(GovernmentForm.FEUDAL_MONARCHY, 760),
                30_000,
                0L);
        Constitution mobilisingConfederation = new Constitution(
                GovernmentForm.CLAN_CONFEDERATION,
                700,
                600,
                500,
                200,
                100,
                250,
                650,
                750,
                420);
        realms.institutions().ensureRealm(emergencyRealm, mobilisingConfederation, 0L);
        realms.institutions().update(
                emergencyRealm,
                mobilisingConfederation,
                4_000,
                0L);

        SimulationSavedData simulation = new SimulationSavedData();
        for (int index = 0; index < 6; index++) {
            restoreSettlement(
                    simulation,
                    100L + index,
                    commercialRealm,
                    1,
                    1_000L + index,
                    420L + index * 30L,
                    index < 4 ? SettlementTier.TOWN : SettlementTier.CITY,
                    930,
                    850,
                    30,
                    860,
                    900,
                    880,
                    900,
                    840,
                    900,
                    900);
        }
        for (int index = 0; index < 9; index++) {
            long region = 2_000L + index;
            restoreSettlement(
                    simulation,
                    200L + index,
                    emergencyRealm,
                    index % 3 + 2,
                    region,
                    300L + index * 20L,
                    index < 3 ? SettlementTier.TOWN : SettlementTier.VILLAGE,
                    260,
                    620,
                    180,
                    680,
                    500,
                    200,
                    450,
                    500,
                    350,
                    550);
            check(simulation.shocks().add(
                            new WorldShock(
                                    ShockType.WAR_DEVASTATION,
                                    0L,
                                    region,
                                    0,
                                    950,
                                    6),
                            0L),
                    "war shock added");
        }

        RealmEvolutionService service = new RealmEvolutionService(
                realms,
                simulation,
                16,
                32,
                1,
                1,
                120,
                10L,
                1_000L);
        service.tick(10L);
        check(service.lastTickWorkUnits() == 1, "one Realm processed per tick");
        check(service.hasPendingEvaluation(), "second Realm retained for next tick");
        check(realms.registry().government(commercialRealm) != GovernmentForm.FEUDAL_MONARCHY,
                "commercial Realm changed government");
        check(realms.institutions().lastEvaluationMilliYear(commercialRealm) == 10L,
                "commercial historical evaluation persisted");

        service.tick(10L);
        check(!service.hasPendingEvaluation(), "evaluation stripe completed");
        GovernmentForm emergencyGovernment = realms.registry().government(emergencyRealm);
        check(emergencyGovernment == GovernmentForm.MILITARY_AUTOCRACY
                        || emergencyGovernment == GovernmentForm.BUREAUCRATIC_MONARCHY,
                "war crisis centralised government");
        check(realms.institutions().lastEvaluationMilliYear(emergencyRealm) == 10L,
                "emergency historical evaluation persisted");
        check(service.snapshotCount() == 1L, "single aggregate snapshot reused");
        check(service.evaluatedRealmCount() == 2L, "both Realms evaluated");
        check(service.governmentChangeCount() == 2L, "both governments changed");
        check(service.overextendedEvaluationCount() >= 1L,
                "large war-damaged Realm exceeded administrative capacity");
        check(service.secessionRiskEvaluationCount() >= 1L,
                "fragmented emergency Realm reached secession risk");
        check(service.legitimacyAdjustmentCount() == 2L,
                "administrative assessment adjusted both constitutions");
        check(realms.registry().legitimacy(emergencyRealm) < mobilisingConfederation.legitimacy(),
                "administrative crisis reduced emergency Realm legitimacy");
        check(service.grossTaxRevenue() > service.netTaxRevenue()
                        && service.netTaxRevenue() > 0L,
                "administrative leakage reduced nominal tax revenue");
        check(service.tributeTransferred() > 0L,
                "dependent Realm transferred persisted tribute");
        check(realms.treasury(commercialRealm) > realms.treasury(emergencyRealm),
                "overlord treasury received own taxes and tribute");
        check(realms.treasury(emergencyRealm) > 0L,
                "subject retained revenue after tribute");
        System.out.println("Realm evolution service self-test passed");
    }

    private static long createRealm(
            RealmSavedData data,
            long suffix,
            GovernmentForm government,
            int legitimacy,
            String name) {
        UUID capital = new UUID(0x5000000000000000L, suffix);
        long capitalId = data.keys().internSettlement(capital);
        long realmId = data.registry().createRealm(
                capitalId,
                RealmMemberKind.NPC_SETTLEMENT,
                0L,
                government,
                legitimacy,
                0L);
        check(realmId != RealmRegistry.NO_REALM, "Realm created");
        data.upsertMetadata(realmId, name, 10, 0L, false);
        return realmId;
    }

    private static void restoreSettlement(
            SimulationSavedData simulation,
            long settlementId,
            long realmId,
            int cultureKey,
            long regionKey,
            long population,
            SettlementTier tier,
            int market,
            int education,
            int damage,
            int geographicCapacity,
            int fertility,
            int specialization,
            int productivity,
            int stability,
            int attractiveness,
            int productiveCapital) {
        long[] stocks = new long[SimulationSavedData.COMMODITY_COUNT];
        int[] prices = new int[SimulationSavedData.COMMODITY_COUNT];
        long[] flows = new long[SimulationSavedData.COMMODITY_COUNT];
        for (int commodity = 0; commodity < prices.length; commodity++) {
            stocks[commodity] = population * 4L;
            prices[commodity] = 100 + commodity * 50;
        }
        simulation.state().restoreRow(
                settlementId,
                cultureKey,
                realmId,
                regionKey,
                population,
                population + 100L,
                20,
                16,
                market,
                Math.max(0, 1000 - damage),
                damage,
                education,
                geographicCapacity,
                fertility,
                specialization,
                population,
                productivity,
                stability,
                attractiveness,
                productiveCapital,
                SettlementStatus.ACTIVE,
                tier,
                0,
                0,
                0,
                10L,
                true,
                stocks,
                prices,
                flows);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
