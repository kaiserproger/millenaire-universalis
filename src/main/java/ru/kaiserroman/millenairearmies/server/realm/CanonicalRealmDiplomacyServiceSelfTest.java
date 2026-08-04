package ru.kaiserroman.millenairearmies.server.realm;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenaire.realm.BattleOutcome;
import ru.kaiserroman.millenaire.realm.Constitution;
import ru.kaiserroman.millenaire.realm.DiplomaticStatus;
import ru.kaiserroman.millenaire.realm.GovernmentForm;
import ru.kaiserroman.millenaire.realm.RealmHistoricalAssessment;
import ru.kaiserroman.millenaire.realm.RealmHistoricalPhase;
import ru.kaiserroman.millenaire.realm.RealmMemberKind;
import ru.kaiserroman.millenaire.realm.RealmScale;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenaire.realm.WarGoal;
import ru.kaiserroman.millenaire.simulation.SettlementStatus;
import ru.kaiserroman.millenaire.simulation.SettlementTier;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;

/** Canonical diplomacy must control hostility and absorb physical Armies outcomes. */
public final class CanonicalRealmDiplomacyServiceSelfTest {
    private CanonicalRealmDiplomacyServiceSelfTest() {}

    public static void main(String[] args) {
        RealmSavedData realms = new RealmSavedData();
        SimulationSavedData simulation = new SimulationSavedData();
        int cultureA = simulation.keys().internCulture(
                ResourceLocation.fromNamespaceAndPath("millenaire", "norman"));
        int cultureB = simulation.keys().internCulture(
                ResourceLocation.fromNamespaceAndPath("millenaire", "mayan"));
        long region = 0x10000000077L;

        UUID ruler = uuid(1);
        UUID capitalA = uuid(101);
        long rulerSubject = realms.keys().internPlayer(ruler);
        long capitalASubject = realms.keys().internSettlement(capitalA);
        long firstRealm = realms.registry().createRealm(
                capitalASubject,
                RealmMemberKind.PLAYER_SETTLEMENT,
                rulerSubject,
                GovernmentForm.MILITARY_AUTOCRACY,
                700,
                0L);
        check(firstRealm != RealmRegistry.NO_REALM, "first Realm created");
        check(realms.registry().addMember(
                firstRealm, rulerSubject, RealmMemberKind.PLAYER, rulerSubject, 1000),
                "ruler attached");
        realms.institutions().ensureRealm(
                firstRealm,
                Constitution.archetype(GovernmentForm.MILITARY_AUTOCRACY, 700),
                0L);
        realms.upsertMetadata(firstRealm, "Iron March", 15, 0L, false);

        UUID capitalB = uuid(201);
        long capitalBSubject = realms.keys().internSettlement(capitalB);
        long secondRealm = realms.registry().createRealm(
                capitalBSubject,
                RealmMemberKind.NPC_SETTLEMENT,
                0L,
                GovernmentForm.CITIZEN_POLITY,
                650,
                0L);
        check(secondRealm != RealmRegistry.NO_REALM, "second Realm created");
        realms.institutions().ensureRealm(
                secondRealm,
                Constitution.archetype(GovernmentForm.CITIZEN_POLITY, 650),
                0L);
        realms.upsertMetadata(secondRealm, "River Assembly", 6, 0L, false);
        UUID provinceB = uuid(202);
        long provinceBSubject = realms.keys().internSettlement(provinceB);
        check(realms.registry().addMember(
                secondRealm,
                provinceBSubject,
                RealmMemberKind.NPC_SETTLEMENT,
                0L,
                420),
                "second Realm province attached");

        long simA = addSettlement(
                simulation, capitalA, cultureA, firstRealm, region,
                1_200L, 900, 850, 900, 850, 850, 30);
        long simB = addSettlement(
                simulation, capitalB, cultureB, secondRealm, region,
                300L, 250, 450, 420, 380, 360, 80);
        long simProvinceB = addSettlement(
                simulation, provinceB, cultureB, secondRealm, region + 1L,
                180L, 320, 420, 400, 360, 330, 60);
        check(simulation.state().find(simA) >= 0
                        && simulation.state().find(simB) >= 0
                        && simulation.state().find(simProvinceB) >= 0,
                "simulation settlements restored");

        check(realms.diplomacy().updateDrivers(
                        firstRealm, secondRealm,
                        100, 900, 120, 900,
                        60, 900, 850, 0,
                        0L),
                "first grievances seeded");
        check(realms.diplomacy().updateDrivers(
                        secondRealm, firstRealm,
                        100, 850, 800, 700,
                        60, 900, 850, 0,
                        0L),
                "second grievances seeded");

        CanonicalRealmDiplomacyService service = new CanonicalRealmDiplomacyService(
                realms,
                simulation,
                16,
                32,
                64,
                1,
                1,
                5,
                10L);
        service.tick(10L);
        check(service.status(firstRealm, secondRealm, 1L) == DiplomaticStatus.WAR,
                "hostile neighbours entered war");
        check(service.isAtWar(firstRealm, secondRealm), "military policy exposes war");
        check(service.warGoal(firstRealm, secondRealm) != WarGoal.NONE
                        && service.warGoal(secondRealm, firstRealm) == WarGoal.DEFEND,
                "war goals are directional");
        check(service.mayCommandSettlement(rulerSubject, capitalASubject),
                "canonical ruler commands capital");
        check(!service.mayCommandSettlement(rulerSubject, capitalBSubject),
                "canonical ruler cannot command enemy capital");

        service.recordBattleOutcome(new BattleOutcome(
                firstRealm,
                secondRealm,
                true,
                2,
                10,
                provinceBSubject,
                false,
                true));
        check(realms.registry().realmOfMember(provinceBSubject) == firstRealm,
                "occupied province transferred to attacker Realm");
        check(realms.registry().memberKind(provinceBSubject) == RealmMemberKind.NPC_SETTLEMENT,
                "occupied province loses former local controller");
        check(simulation.state().realmIdAt(simulation.state().find(simProvinceB)) == firstRealm,
                "Simulation ownership follows canonical capture");
        check(realms.registry().exists(secondRealm),
                "non-capital capture preserves defender Realm");

        for (int battle = 0; battle < 3; battle++) {
            service.recordBattleOutcome(new BattleOutcome(
                    firstRealm, secondRealm, true, 80, 80, false, true));
        }
        check(realms.diplomacy().warScore(firstRealm, secondRealm) > 0,
                "physical outcomes increased attacker score");
        check(realms.diplomacy().exhaustion(secondRealm, firstRealm) >= 800,
                "physical outcomes accumulated defender exhaustion");

        service.tick(20L);
        check(service.status(firstRealm, secondRealm, 2L) == DiplomaticStatus.TRUCE,
                "exhausted war became truce");
        check(!service.isAtWar(firstRealm, secondRealm), "truce disables physical hostility");

        UUID capitalC = uuid(301);
        long capitalCSubject = realms.keys().internSettlement(capitalC);
        long thirdRealm = realms.registry().createRealm(
                capitalCSubject,
                RealmMemberKind.NPC_SETTLEMENT,
                0L,
                GovernmentForm.CLAN_CONFEDERATION,
                500,
                2L);
        check(thirdRealm != RealmRegistry.NO_REALM, "annexation target created");
        realms.institutions().ensureRealm(
                thirdRealm,
                Constitution.archetype(GovernmentForm.CLAN_CONFEDERATION, 500),
                2L);
        realms.upsertMetadata(thirdRealm, "Hill Council", 5, 0L, false);
        long simC = addSettlement(
                simulation, capitalC, cultureB, thirdRealm, region + 2L,
                120L, 180, 240, 250, 220, 200, 120);
        RealmHistoricalAssessment decadent = new RealmHistoricalAssessment(
                RealmHistoricalPhase.DECADENT,
                RealmScale.REGIONAL_STATE,
                420,
                700,
                200,
                250,
                600_000,
                0,
                50_000,
                0,
                0,
                10_000L,
                false,
                false,
                false,
                false);
        check(realms.history().ensureRealm(firstRealm, decadent, 0L) == 0,
                "historical gate state inserted");
        check(!service.declareWar(firstRealm, thirdRealm, WarGoal.SUBJUGATE, 3L),
                "decadent Realm cannot initiate expansion");
        check(service.blockedExpansionWarCount() == 1L,
                "blocked expansion metric");
        RealmHistoricalAssessment ascendant = new RealmHistoricalAssessment(
                RealmHistoricalPhase.ASCENDANT,
                RealmScale.REGIONAL_STATE,
                850,
                120,
                900,
                840,
                0,
                0,
                0,
                20_000,
                0,
                20_000L,
                true,
                false,
                true,
                false);
        check(realms.history().update(firstRealm, ascendant, 20_000L),
                "historical recovery applied");
        check(service.declareWar(firstRealm, thirdRealm, WarGoal.SUBJUGATE, 3L),
                "ascendant Realm may declare expansion war");
        service.recordBattleOutcome(new BattleOutcome(
                firstRealm,
                thirdRealm,
                true,
                1,
                20,
                capitalCSubject,
                true,
                true));
        check(realms.registry().exists(thirdRealm),
                "subjugation preserves the defeated NPC Realm");
        check(realms.registry().realmOfMember(capitalCSubject) == thirdRealm,
                "subjugated capital remains with its Realm");
        check(simulation.state().realmIdAt(simulation.state().find(simC)) == thirdRealm,
                "Simulation ownership remains with the subject Realm");
        check(realms.dependencies().overlordOf(thirdRealm) == firstRealm,
                "defeated Realm became a dependency");
        check(realms.dependencies().tributeRate(thirdRealm) > 0
                        && realms.dependencies().militaryLevy(thirdRealm) > 0,
                "subjugation generated tribute and levy terms");
        check(!service.declareWar(thirdRealm, secondRealm, WarGoal.PUNITIVE, 4L),
                "low-autonomy subject cannot independently declare war");

        check(service.declareWar(secondRealm, thirdRealm, WarGoal.LIBERATE, 4L),
                "liberation war declared");
        service.recordBattleOutcome(new BattleOutcome(
                secondRealm,
                thirdRealm,
                true,
                2,
                18,
                capitalCSubject,
                true,
                true));
        check(!realms.dependencies().isSubject(thirdRealm),
                "liberation removes dependency");
        check(realms.registry().exists(thirdRealm)
                        && realms.registry().realmOfMember(capitalCSubject) == thirdRealm,
                "liberation preserves the restored independent Realm");
        check(service.battleOutcomeCount() == 6L, "battle outcome metric");
        check(service.capturedSettlementCount() == 1L, "capture metric");
        check(service.annexedRealmCount() == 0L, "subjugation avoids annexation");
        check(service.subjugatedRealmCount() == 1L, "subjugation metric");
        check(service.liberatedRealmCount() == 1L, "liberation metric");
        check(service.statusChangeCount() >= 2L, "war and truce transitions counted");
        System.out.println("Canonical Realm diplomacy self-test passed");
    }

    private static long addSettlement(
            SimulationSavedData simulation,
            UUID uuid,
            int culture,
            long realmId,
            long region,
            long population,
            int market,
            int security,
            int productivity,
            int stability,
            int capital,
            int damage) {
        long settlementId = simulation.keys().internSettlement(uuid);
        long[] stocks = new long[SimulationSavedData.COMMODITY_COUNT];
        int[] prices = new int[SimulationSavedData.COMMODITY_COUNT];
        long[] flows = new long[SimulationSavedData.COMMODITY_COUNT];
        for (int commodity = 0; commodity < prices.length; commodity++) {
            stocks[commodity] = population * 4L;
            prices[commodity] = 100 + commodity * 40;
        }
        simulation.state().restoreRow(
                settlementId,
                culture,
                realmId,
                region,
                population,
                population + 200L,
                20,
                16,
                market,
                security,
                damage,
                capital,
                800,
                650,
                market,
                population,
                productivity,
                stability,
                stability,
                capital,
                SettlementStatus.ACTIVE,
                SettlementTier.CITY,
                0,
                0,
                0,
                10L,
                true,
                stocks,
                prices,
                flows);
        return settlementId;
    }

    private static UUID uuid(long least) {
        return new UUID(0x7000000000000000L, least);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
