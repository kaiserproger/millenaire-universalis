package ru.kaiserroman.millenairearmies.server.realm;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenaire.realm.Constitution;
import ru.kaiserroman.millenaire.realm.GovernmentForm;
import ru.kaiserroman.millenaire.realm.RealmHistoricalAssessment;
import ru.kaiserroman.millenaire.realm.RealmHistoricalPhase;
import ru.kaiserroman.millenaire.realm.RealmMemberKind;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenaire.realm.RealmScale;
import ru.kaiserroman.millenaire.simulation.SettlementStatus;
import ru.kaiserroman.millenaire.simulation.SettlementTier;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;

/** Autonomous NPC Realm formation/dissolution must remain hysteretic and player-safe. */
public final class AutonomousRealmLifecycleServiceSelfTest {
    private AutonomousRealmLifecycleServiceSelfTest() {}

    public static void main(String[] args) {
        formsNpcRealmAfterPersistentPressure();
        formsAndSustainsViableCityState();
        ascendantCityStateIntegratesNearbyVillage();
        collapsingRealmCreatesSuccessorStateAndHonoursCooldown();
        shatteredProvinceBecomesStateless();
        dissolvesNpcRealmWithoutCapital();
        neverDissolvesPlayerOrMixedRealm();
        System.out.println("Autonomous Realm lifecycle self-test passed");
    }

    private static void formsNpcRealmAfterPersistentPressure() {
        RealmSavedData realms = new RealmSavedData();
        SimulationSavedData simulation = new SimulationSavedData();
        int culture = simulation.keys().internCulture(
                ResourceLocation.fromNamespaceAndPath("millenaire", "norman"));
        long region = 0x10000000055L;
        long first = addSettlement(simulation, uuid(101), culture, 0L, region, 240L,
                SettlementTier.TOWN, SettlementStatus.ACTIVE, true,
                900, 820, 850, 860, 800, 760, 780, 820, 850);
        long second = addSettlement(simulation, uuid(102), culture, 0L, region, 180L,
                SettlementTier.VILLAGE, SettlementStatus.ACTIVE, true,
                860, 780, 790, 800, 760, 720, 740, 780, 800);
        long third = addSettlement(simulation, uuid(103), culture, 0L, region, 140L,
                SettlementTier.VILLAGE, SettlementStatus.ACTIVE, true,
                820, 760, 770, 780, 740, 700, 720, 760, 780);

        AutonomousRealmLifecycleService service = new AutonomousRealmLifecycleService(
                realms, simulation, 16, 32, 1, 1, 16, 8, 5, 10, 2,
                true, 12, 20, 10L, 1_000L);
        for (int year = 1; year <= 5; year++) {
            service.tick(year * 1_000L);
            check(realms.registry().realmCount() == 0,
                    "regional Realm must not form before five historical years qualify");
            check(realms.lifecycle().formationQualifyingCycles(region, culture) > 0,
                    "historical qualification must persist year-by-year");
        }
        service.tick(6_000L);
        check(realms.registry().realmCount() == 1, "regional NPC Realm formed after sustained years");
        long realmId = simulation.state().realmIdAt(simulation.state().find(first));
        check(realmId != RealmRegistry.NO_REALM, "first settlement assigned");
        check(simulation.state().realmIdAt(simulation.state().find(second)) == realmId,
                "second settlement assigned");
        check(simulation.state().realmIdAt(simulation.state().find(third)) == realmId,
                "third settlement assigned");
        check(realms.registry().memberCount(realmId) == 3, "all settlements became members");
        check(!realms.registry().hasPlayerMembers(realmId), "formed Realm is NPC-only");
        check(realms.institutions().constitution(realmId) != null,
                "formed Realm has persisted constitution");
        check(realms.lifecycle().formationQualifyingCycles(region, culture) == 0,
                "consumed formation candidate removed");
        check(service.formedRealmCount() == 1L, "formation metric");
    }

    private static void formsAndSustainsViableCityState() {
        RealmSavedData realms = new RealmSavedData();
        SimulationSavedData simulation = new SimulationSavedData();
        int culture = simulation.keys().internCulture(
                ResourceLocation.fromNamespaceAndPath("millenaire", "byzantines"));
        long region = 0x10000000066L;
        long simulationSettlement = addSettlement(
                simulation, uuid(151), culture, 0L, region, 80L,
                SettlementTier.VILLAGE, SettlementStatus.ACTIVE, true,
                850, 820, 0, 800, 780, 800, 780, 820, 840);

        AutonomousRealmLifecycleService service = new AutonomousRealmLifecycleService(
                realms, simulation, 16, 32, 1, 1, 16, 8, 5, 10, 2,
                true, 12, 20, 10L, 1_000L);
        for (int year = 1; year <= 8; year++) {
            service.tick(year * 1_000L);
            check(realms.registry().realmCount() == 0,
                    "city-state must sustain viability for eight complete historical years");
        }
        service.tick(9_000L);
        check(realms.registry().realmCount() == 1,
                "strong single settlement formed a city-state");
        long realmId = simulation.state().realmIdAt(simulation.state().find(simulationSettlement));
        check(realmId != RealmRegistry.NO_REALM, "city settlement assigned to canonical Realm");
        check(realms.registry().memberCount(realmId) == 1,
                "city-state has exactly one settlement member");
        check(realms.history().scale(realmId) == RealmScale.CITY_STATE,
                "city-state scale persisted immediately");
        for (int year = 10; year <= 50; year++) service.tick(year * 1_000L);
        check(realms.registry().exists(realmId),
                "viable city-state survives for decades without artificial second member");
        check(realms.lifecycle().crisisQualifyingCycles(realmId) == 0,
                "healthy city-state does not accumulate dissolution pressure");
        check(service.dissolvedRealmCount() == 0L, "no size-only dissolution");
    }

    private static void ascendantCityStateIntegratesNearbyVillage() {
        RealmSavedData realms = new RealmSavedData();
        SimulationSavedData simulation = new SimulationSavedData();
        int culture = simulation.keys().internCulture(
                ResourceLocation.fromNamespaceAndPath("millenaire", "norman"));
        long region = 0x10000000077L;
        UUID capital = uuid(171);
        UUID neighbour = uuid(172);
        long capitalSimulationId = addSettlement(
                simulation, capital, culture, 0L, region, 120L,
                SettlementTier.TOWN, SettlementStatus.ACTIVE, true,
                900, 860, 0, 850, 820, 850, 820, 860, 870);
        long neighbourSimulationId = addSettlement(
                simulation, neighbour, culture, 0L, region, 60L,
                SettlementTier.VILLAGE, SettlementStatus.ACTIVE, true,
                720, 700, 0, 650, 680, 700, 650, 700, 720);
        long capitalSubject = realms.keys().internSettlement(capital);
        long realmId = realms.registry().createRealm(
                capitalSubject,
                RealmMemberKind.NPC_SETTLEMENT,
                0L,
                GovernmentForm.CITY_LEAGUE,
                800,
                0L);
        check(realmId != RealmRegistry.NO_REALM, "expansion city-state created");
        realms.institutions().ensureRealm(
                realmId,
                Constitution.archetype(GovernmentForm.CITY_LEAGUE, 800),
                0L);
        realms.upsertMetadata(realmId, "Aureate Port", 10, 20_000L, false);
        simulation.state().assignRealm(capitalSimulationId, realmId);
        RealmHistoricalAssessment history = new RealmHistoricalAssessment(
                RealmHistoricalPhase.ASCENDANT,
                RealmScale.CITY_STATE,
                850,
                100,
                900,
                820,
                0,
                0,
                0,
                20_000,
                0,
                0L,
                false,
                false,
                true,
                false);
        check(realms.history().ensureRealm(realmId, history, 0L) == 0,
                "expansion history inserted");

        AutonomousRealmLifecycleService service = new AutonomousRealmLifecycleService(
                realms, simulation, 16, 32, 1, 1, 16, 8, 5, 10, 2,
                true, 12, 20, 10L, 1_000L);
        service.tick(1_000L);
        check(simulation.state().realmIdAt(simulation.state().find(neighbourSimulationId)) == realmId,
                "ascendant city-state integrated nearby same-culture village");
        check(realms.registry().settlementCount(realmId) == 2,
                "integrated village became canonical Realm member");
        check(service.peacefulExpansionCount() == 1L,
                "peaceful expansion metric");
    }

    private static void collapsingRealmCreatesSuccessorStateAndHonoursCooldown() {
        RealmSavedData realms = new RealmSavedData();
        SimulationSavedData simulation = new SimulationSavedData();
        int coreCulture = simulation.keys().internCulture(
                ResourceLocation.fromNamespaceAndPath("millenaire", "norman"));
        int frontierCulture = simulation.keys().internCulture(
                ResourceLocation.fromNamespaceAndPath("millenaire", "mayan"));
        long coreRegion = 0x10000000088L;
        long frontierRegionA = 0x10000000099L;
        long frontierRegionB = 0x100000000AAL;
        UUID capital = uuid(181);
        UUID core = uuid(182);
        UUID frontierA = uuid(183);
        UUID frontierB = uuid(184);
        long capitalSubject = realms.keys().internSettlement(capital);
        long coreSubject = realms.keys().internSettlement(core);
        long frontierSubjectA = realms.keys().internSettlement(frontierA);
        long frontierSubjectB = realms.keys().internSettlement(frontierB);
        long realmId = realms.registry().createRealm(
                capitalSubject,
                RealmMemberKind.NPC_SETTLEMENT,
                0L,
                GovernmentForm.CLAN_CONFEDERATION,
                220,
                0L);
        check(realmId != RealmRegistry.NO_REALM, "collapsing parent Realm created");
        check(realms.registry().addMember(
                        realmId, coreSubject, RealmMemberKind.NPC_SETTLEMENT, 0L, 800),
                "core province attached");
        check(realms.registry().addMember(
                        realmId, frontierSubjectA, RealmMemberKind.NPC_SETTLEMENT, 0L, 350),
                "first frontier attached");
        check(realms.registry().addMember(
                        realmId, frontierSubjectB, RealmMemberKind.NPC_SETTLEMENT, 0L, 320),
                "second frontier attached");
        Constitution weakCentre = new Constitution(
                GovernmentForm.CLAN_CONFEDERATION,
                120,
                100,
                620,
                180,
                100,
                220,
                700,
                400,
                220);
        realms.institutions().ensureRealm(realmId, weakCentre, 0L);
        realms.upsertMetadata(realmId, "Fractured Crown", 15, 500L, false);
        long capitalSimulation = addSettlement(
                simulation, capital, coreCulture, realmId, coreRegion, 160L,
                SettlementTier.TOWN, SettlementStatus.ACTIVE, true,
                250, 220, 800, 200, 500, 300, 200, 260, 240);
        long coreSimulation = addSettlement(
                simulation, core, coreCulture, realmId, coreRegion, 110L,
                SettlementTier.VILLAGE, SettlementStatus.ACTIVE, true,
                300, 280, 720, 250, 520, 350, 220, 300, 280);
        long frontierSimulationA = addSettlement(
                simulation, frontierA, frontierCulture, realmId, frontierRegionA, 120L,
                SettlementTier.TOWN, SettlementStatus.ACTIVE, true,
                760, 320, 780, 700, 760, 650, 720, 700, 420);
        long frontierSimulationB = addSettlement(
                simulation, frontierB, frontierCulture, realmId, frontierRegionB, 100L,
                SettlementTier.VILLAGE, SettlementStatus.ACTIVE, true,
                700, 350, 720, 650, 720, 620, 680, 650, 450);
        RealmHistoricalAssessment collapse = new RealmHistoricalAssessment(
                RealmHistoricalPhase.COLLAPSING,
                RealmScale.REGIONAL_STATE,
                220,
                900,
                180,
                100,
                1_000_000,
                0,
                80_000,
                0,
                0,
                0L,
                false,
                false,
                false,
                false);
        check(realms.history().ensureRealm(realmId, collapse, 0L) == 0,
                "collapsing history inserted");

        AutonomousRealmLifecycleService service = new AutonomousRealmLifecycleService(
                realms, simulation, 16, 32, 1, 1, 16, 8, 5, 10, 2,
                true, 12, 20, 10L, 1_000L);
        service.tick(13_000L);
        long firstSuccessor = successorOf(
                simulation, realmId, frontierSimulationA, frontierSimulationB);
        check(firstSuccessor != RealmRegistry.NO_REALM,
                "strong frontier formed a successor Realm");
        check(realms.registry().exists(firstSuccessor),
                "successor Realm persisted in canonical registry");
        check(realms.history().phase(firstSuccessor) != null,
                "successor Realm received historical state");
        check(realms.registry().settlementCount(realmId) == 3,
                "parent lost exactly one province");
        check(service.secessionCount() == 1L && service.successorStateCount() == 1L,
                "successor secession metrics");
        check(realms.history().lastSecessionMilliYear(realmId) == 13_000L,
                "parent secession date persisted");

        service.tick(20_000L);
        check(realms.registry().settlementCount(realmId) == 3,
                "twenty-year cooldown blocks immediate second secession");
        check(service.secessionCount() == 1L,
                "cooldown does not increment secession metrics");

        service.tick(34_000L);
        check(realms.registry().settlementCount(realmId) == 2,
                "another frontier may leave after cooldown expires");
        check(service.secessionCount() == 2L && service.successorStateCount() == 2L,
                "second successor formed after historical cooldown");
        check(simulation.state().realmIdAt(simulation.state().find(capitalSimulation)) == realmId
                        && simulation.state().realmIdAt(simulation.state().find(coreSimulation)) == realmId,
                "capital core remains with the parent Realm");
    }

    private static void shatteredProvinceBecomesStateless() {
        RealmSavedData realms = new RealmSavedData();
        SimulationSavedData simulation = new SimulationSavedData();
        int coreCulture = simulation.keys().internCulture(
                ResourceLocation.fromNamespaceAndPath("millenaire", "byzantines"));
        int shatteredCulture = simulation.keys().internCulture(
                ResourceLocation.fromNamespaceAndPath("millenaire", "japanese"));
        UUID capital = uuid(191);
        UUID province = uuid(192);
        long capitalSubject = realms.keys().internSettlement(capital);
        long provinceSubject = realms.keys().internSettlement(province);
        long realmId = realms.registry().createRealm(
                capitalSubject,
                RealmMemberKind.NPC_SETTLEMENT,
                0L,
                GovernmentForm.MILITARY_AUTOCRACY,
                100,
                0L);
        check(realms.registry().addMember(
                        realmId, provinceSubject, RealmMemberKind.NPC_SETTLEMENT, 0L, 100),
                "shattered province attached");
        realms.institutions().ensureRealm(
                realmId,
                Constitution.archetype(GovernmentForm.MILITARY_AUTOCRACY, 100),
                0L);
        realms.upsertMetadata(realmId, "Ashen Remnant", 20, 0L, false);
        addSettlement(
                simulation, capital, coreCulture, realmId, 0x100000000BBL, 70L,
                SettlementTier.VILLAGE, SettlementStatus.ACTIVE, true,
                100, 100, 900, 100, 300, 100, 100, 100, 100);
        long provinceSimulation = addSettlement(
                simulation, province, shatteredCulture, realmId, 0x100000000CCL, 20L,
                SettlementTier.HAMLET, SettlementStatus.DECLINING, true,
                80, 60, 980, 50, 100, 50, 50, 80, 60);
        RealmHistoricalAssessment collapse = new RealmHistoricalAssessment(
                RealmHistoricalPhase.COLLAPSING,
                RealmScale.REGIONAL_STATE,
                100,
                980,
                50,
                0,
                1_000_000,
                0,
                100_000,
                0,
                0,
                0L,
                false,
                false,
                false,
                true);
        check(realms.history().ensureRealm(realmId, collapse, 0L) == 0,
                "terminal historical state inserted");

        AutonomousRealmLifecycleService service = new AutonomousRealmLifecycleService(
                realms, simulation, 16, 32, 1, 1, 16, 8, 5, 10, 2,
                true, 12, 20, 10L, 1_000L);
        service.tick(13_000L);
        check(simulation.state().realmIdAt(simulation.state().find(provinceSimulation))
                        == RealmRegistry.NO_REALM,
                "shattered province became stateless");
        check(realms.registry().realmOfMember(provinceSubject) == RealmRegistry.NO_REALM,
                "stateless province left canonical membership");
        check(realms.registry().realmCount() == 1,
                "weak province did not fabricate a successor state");
        check(service.secessionCount() == 1L
                        && service.statelessProvinceCount() == 1L
                        && service.successorStateCount() == 0L,
                "stateless secession metrics");
    }

    private static long successorOf(
            SimulationSavedData simulation,
            long parentRealm,
            long firstSettlement,
            long secondSettlement) {
        long first = simulation.state().realmIdAt(simulation.state().find(firstSettlement));
        if (first != RealmRegistry.NO_REALM && first != parentRealm) return first;
        long second = simulation.state().realmIdAt(simulation.state().find(secondSettlement));
        return second != RealmRegistry.NO_REALM && second != parentRealm
                ? second
                : RealmRegistry.NO_REALM;
    }

    private static void dissolvesNpcRealmWithoutCapital() {
        RealmSavedData realms = new RealmSavedData();
        SimulationSavedData simulation = new SimulationSavedData();
        int culture = simulation.keys().internCulture(
                ResourceLocation.fromNamespaceAndPath("millenaire", "mayan"));
        long capitalSimulationId = addSettlement(
                simulation, uuid(201), culture, 0L, 0x20000000011L, 0L,
                SettlementTier.OUTPOST, SettlementStatus.RUINED, false,
                0, 0, 1000, 0, 0, 0, 0, 0, 0);
        long provinceSimulationId = addSettlement(
                simulation, uuid(202), culture, 0L, 0x20000000012L, 0L,
                SettlementTier.OUTPOST, SettlementStatus.ABANDONED, false,
                50, 50, 950, 50, 50, 50, 50, 50, 50);
        long capitalSubject = realms.keys().internSettlement(uuid(201));
        long provinceSubject = realms.keys().internSettlement(uuid(202));
        long realmId = realms.registry().createRealm(
                capitalSubject,
                RealmMemberKind.NPC_SETTLEMENT,
                0L,
                GovernmentForm.CLAN_CONFEDERATION,
                120,
                0L);
        check(realmId != RealmRegistry.NO_REALM, "terminal NPC Realm created");
        check(realms.registry().addMember(
                realmId, provinceSubject, RealmMemberKind.NPC_SETTLEMENT, 0L, 300),
                "terminal province attached");
        realms.institutions().ensureRealm(
                realmId,
                Constitution.archetype(GovernmentForm.CLAN_CONFEDERATION, 120),
                0L);
        realms.upsertMetadata(realmId, "Fallen Council", 0, 0L, false);
        simulation.state().assignRealm(capitalSimulationId, realmId);
        simulation.state().assignRealm(provinceSimulationId, realmId);

        AutonomousRealmLifecycleService service = new AutonomousRealmLifecycleService(
                realms, simulation, 16, 32, 1, 1, 16, 8, 5, 10, 2,
                true, 12, 20, 10L, 1_000L);
        service.tick(10L);
        check(!realms.registry().exists(realmId), "NPC Realm without capital dissolved");
        check(simulation.state().realmIdAt(simulation.state().find(capitalSimulationId)) == 0L,
                "capital assignment cleared");
        check(simulation.state().realmIdAt(simulation.state().find(provinceSimulationId)) == 0L,
                "province assignment cleared");
        check(realms.institutions().constitution(realmId) == null,
                "dissolved institutions removed");
        check(realms.name(realmId) == null, "dissolved metadata removed");
        check(service.dissolvedRealmCount() == 1L, "dissolution metric");
    }

    private static void neverDissolvesPlayerOrMixedRealm() {
        RealmSavedData realms = new RealmSavedData();
        SimulationSavedData simulation = new SimulationSavedData();
        int culture = simulation.keys().internCulture(
                ResourceLocation.fromNamespaceAndPath("millenaire", "japanese"));
        long capitalSimulationId = addSettlement(
                simulation, uuid(301), culture, 0L, 0x30000000021L, 0L,
                SettlementTier.OUTPOST, SettlementStatus.RUINED, false,
                0, 0, 1000, 0, 0, 0, 0, 0, 0);
        long rulerSubject = realms.keys().internPlayer(uuid(399));
        long capitalSubject = realms.keys().internSettlement(uuid(301));
        long realmId = realms.registry().createRealm(
                capitalSubject,
                RealmMemberKind.PLAYER_SETTLEMENT,
                rulerSubject,
                GovernmentForm.FEUDAL_MONARCHY,
                50,
                0L);
        check(realms.registry().addMember(
                realmId, rulerSubject, RealmMemberKind.PLAYER, rulerSubject, 1000),
                "player ruler attached");
        realms.institutions().ensureRealm(
                realmId,
                Constitution.archetype(GovernmentForm.FEUDAL_MONARCHY, 50),
                0L);
        realms.upsertMetadata(realmId, "Player March", 20, 0L, false);
        simulation.state().assignRealm(capitalSimulationId, realmId);

        AutonomousRealmLifecycleService service = new AutonomousRealmLifecycleService(
                realms, simulation, 16, 32, 1, 2, 16, 8, 5, 10, 2,
                true, 12, 20, 10L, 1_000L);
        for (int cycle = 1; cycle <= 20; cycle++) service.tick(cycle * 10L);
        check(realms.registry().exists(realmId), "player Realm must survive automatic lifecycle");
        check(realms.registry().hasPlayerMembers(realmId), "player membership retained");
        check(service.dissolvedRealmCount() == 0L, "no player dissolution metric");
    }

    private static long addSettlement(
            SimulationSavedData simulation,
            UUID uuid,
            int culture,
            long realmId,
            long region,
            long population,
            SettlementTier tier,
            SettlementStatus status,
            boolean present,
            int market,
            int security,
            int damage,
            int education,
            int geography,
            int fertility,
            int specialization,
            int productivity,
            int stability) {
        long settlementId = simulation.keys().internSettlement(uuid);
        long[] stocks = new long[SimulationSavedData.COMMODITY_COUNT];
        int[] prices = new int[SimulationSavedData.COMMODITY_COUNT];
        long[] flows = new long[SimulationSavedData.COMMODITY_COUNT];
        for (int commodity = 0; commodity < prices.length; commodity++) {
            stocks[commodity] = Math.max(0L, population * 4L);
            prices[commodity] = 100 + commodity * 50;
        }
        simulation.state().restoreRow(
                settlementId,
                culture,
                realmId,
                region,
                population,
                population + 80L,
                12,
                9,
                market,
                security,
                damage,
                education,
                geography,
                fertility,
                specialization,
                population,
                productivity,
                stability,
                stability,
                productivity,
                status,
                tier,
                0,
                0,
                0,
                10L,
                present,
                stocks,
                prices,
                flows);
        return settlementId;
    }

    private static UUID uuid(long least) {
        return new UUID(0x6000000000000000L, least);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
