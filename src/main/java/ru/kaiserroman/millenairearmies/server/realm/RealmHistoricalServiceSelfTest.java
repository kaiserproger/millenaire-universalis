package ru.kaiserroman.millenairearmies.server.realm;

import java.util.UUID;
import ru.kaiserroman.millenaire.realm.Constitution;
import ru.kaiserroman.millenaire.realm.GovernmentForm;
import ru.kaiserroman.millenaire.realm.RealmHistoricalPhase;
import ru.kaiserroman.millenaire.realm.RealmMemberKind;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenaire.realm.RealmScale;
import ru.kaiserroman.millenaire.simulation.SettlementObservation;
import ru.kaiserroman.millenaire.simulation.SettlementStatus;
import ru.kaiserroman.millenaire.simulation.SettlementTier;
import ru.kaiserroman.millenaire.simulation.ShockType;
import ru.kaiserroman.millenaire.simulation.WorldShock;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;

/** Live packed Simulation aggregates must produce long stability, fast crisis and slow restoration. */
public final class RealmHistoricalServiceSelfTest {
    private static final int SETTLEMENTS = 5;
    private static final int CULTURE = 7;
    private static final long REGION = 0x70000000011L;

    private RealmHistoricalServiceSelfTest() {}

    public static void main(String[] args) {
        RealmSavedData realms = new RealmSavedData();
        SimulationSavedData simulation = new SimulationSavedData();
        UUID[] villages = new UUID[SETTLEMENTS];
        long[] settlementIds = new long[SETTLEMENTS];
        villages[0] = uuid(700);
        long capitalSubject = realms.keys().internSettlement(villages[0]);
        long realmId = realms.registry().createRealm(
                capitalSubject,
                RealmMemberKind.NPC_SETTLEMENT,
                0L,
                GovernmentForm.BUREAUCRATIC_MONARCHY,
                820,
                0L);
        check(realmId != RealmRegistry.NO_REALM, "historical test Realm created");
        Constitution constitution = new Constitution(
                GovernmentForm.BUREAUCRATIC_MONARCHY,
                780,
                850,
                420,
                360,
                500,
                650,
                380,
                620,
                820);
        realms.institutions().ensureRealm(realmId, constitution, 0L);
        realms.upsertMetadata(realmId, "Aureate Realm", 12, 500_000L, false);

        for (int index = 0; index < SETTLEMENTS; index++) {
            if (index > 0) {
                villages[index] = uuid(700 + index);
                long subject = realms.keys().internSettlement(villages[index]);
                check(realms.registry().addMember(
                                realmId,
                                subject,
                                RealmMemberKind.NPC_SETTLEMENT,
                                0L,
                                900),
                        "historical province attached");
            }
            settlementIds[index] = simulation.keys().internSettlement(villages[index]);
            restoreProsperousSettlement(simulation, settlementIds[index], realmId, index);
        }

        RealmHistoricalService service = new RealmHistoricalService(
                realms,
                simulation,
                16,
                32,
                8,
                16,
                1L,
                1_000L,
                10L);
        int legitimacyBeforeSeed = realms.registry().legitimacy(realmId);
        service.tick(0L);
        check(realms.history().phase(realmId) == RealmHistoricalPhase.ASCENDANT,
                "prosperous Realm seeded as ascendant");
        check(realms.history().scale(realmId) == RealmScale.KINGDOM,
                "five-settlement Realm seeded as kingdom");
        check(realms.registry().legitimacy(realmId) == legitimacyBeforeSeed,
                "initial historical classification does not alter legitimacy");
        check(realms.history().viability(realmId) >= 800,
                "prosperous Realm has high viability");
        check(realms.history().expansionReadiness(realmId) >= 700,
                "prosperous Realm is expansion-ready");

        service.tick(250_000L);
        check(realms.history().phase(realmId) == RealmHistoricalPhase.ASCENDANT,
                "Realm remains ascendant through 250 stable years");
        check(realms.history().crisisMomentum(realmId) == 0,
                "stable centuries accumulate no crisis momentum");

        observeCrisis(simulation, settlementIds, realmId);
        check(simulation.shocks().add(
                        new WorldShock(
                                ShockType.WAR_DEVASTATION,
                                0L,
                                REGION,
                                CULTURE,
                                1000,
                                20_000),
                        25_000L),
                "historical war shock inserted");
        for (long gameTime : new long[] {270_000L, 290_000L, 310_000L, 330_000L}) {
            service.tick(gameTime);
        }
        check(realms.history().phase(realmId) == RealmHistoricalPhase.COLLAPSING,
                "severe war and devastation collapse an old Realm within eighty years; phase="
                        + realms.history().phase(realmId)
                        + " viability=" + realms.history().viability(realmId)
                        + " crisisRate=" + realms.history().crisisRatePerYear(realmId)
                        + " crisisMomentum=" + realms.history().crisisMomentum(realmId)
                        + " recoveryMomentum=" + realms.history().recoveryMomentum(realmId));
        check(realms.history().crisisBurden(realmId) >= 600,
                "collapse is backed by an extreme live-world crisis burden");
        check(realms.history().scale(realmId).ordinal() < RealmScale.KINGDOM.ordinal(),
                "collapse contracts effective territorial scale");
        check(realms.history().expansionReadiness(realmId) < 700,
                "collapsing Realm loses expansion readiness");
        check(service.phaseChangeCount() >= 4L,
                "crisis generated multiple persisted historical transitions");

        observeRecovery(simulation, settlementIds, realmId);
        service.tick(460_000L);
        check(realms.history().phase(realmId) == RealmHistoricalPhase.RESTORING,
                "post-crisis Realm enters explicit restoration phase");
        service.tick(510_000L);
        service.tick(560_000L);
        check(realms.history().phase(realmId) == RealmHistoricalPhase.STABLE
                        || realms.history().phase(realmId) == RealmHistoricalPhase.ASCENDANT,
                "sustained recovery restores stable government over decades");
        check(realms.history().viability(realmId) >= 750,
                "restored Realm recovers high viability");
        check(realms.history().recoveryRatePerYear(realmId) > 0,
                "recovery has a persisted annual rate");
        check(service.snapshotCount() == 9L,
                "historical service reused bounded snapshots across the full timeline");
        check(service.evaluationCount() == 9L,
                "one Realm evaluated once per historical checkpoint");
        System.out.println("Realm historical service self-test passed");
    }

    private static void restoreProsperousSettlement(
            SimulationSavedData simulation,
            long settlementId,
            long realmId,
            int index) {
        long population = 900L + index * 100L;
        long[] stocks = new long[SimulationSavedData.COMMODITY_COUNT];
        int[] prices = new int[SimulationSavedData.COMMODITY_COUNT];
        long[] flows = new long[SimulationSavedData.COMMODITY_COUNT];
        for (int commodity = 0; commodity < stocks.length; commodity++) {
            stocks[commodity] = population * (commodity == 0 ? 24L : 6L);
            prices[commodity] = 100 + commodity * 40;
            flows[commodity] = commodity == 0 ? population / 5L : population / 20L;
        }
        simulation.state().restoreRow(
                settlementId,
                CULTURE,
                realmId,
                REGION,
                population,
                population + 600L,
                30,
                24,
                880,
                860,
                20,
                820,
                850,
                850,
                800,
                population,
                850,
                840,
                820,
                850,
                SettlementStatus.ACTIVE,
                index == 0 ? SettlementTier.CITY : SettlementTier.TOWN,
                0,
                0,
                0,
                0L,
                true,
                stocks,
                prices,
                flows);
    }

    private static void observeCrisis(
            SimulationSavedData simulation,
            long[] settlementIds,
            long realmId) {
        simulation.state().beginObservation();
        for (long settlementId : settlementIds) {
            int row = simulation.state().find(settlementId);
            long population = simulation.state().populationAt(row);
            simulation.state().observe(
                    new SettlementObservation(
                            settlementId,
                            CULTURE,
                            realmId,
                            REGION,
                            population,
                            population + 200L,
                            30,
                            18,
                            120,
                            80,
                            1000,
                            120,
                            400,
                            120,
                            100),
                    0L);
        }
        simulation.state().finishObservation();
    }

    private static void observeRecovery(
            SimulationSavedData simulation,
            long[] settlementIds,
            long realmId) {
        simulation.state().beginObservation();
        for (long settlementId : settlementIds) {
            int row = simulation.state().find(settlementId);
            long population = simulation.state().populationAt(row);
            simulation.state().observe(
                    new SettlementObservation(
                            settlementId,
                            CULTURE,
                            realmId,
                            REGION,
                            population,
                            population + 700L,
                            34,
                            28,
                            920,
                            900,
                            0,
                            880,
                            900,
                            900,
                            860),
                    0L);
        }
        simulation.state().finishObservation();
    }

    private static UUID uuid(long suffix) {
        return new UUID(0x7000000000000000L, suffix);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
