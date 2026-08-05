package ru.kaiserroman.millenairearmies.server.realm;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenaire.realm.GovernmentForm;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenaire.simulation.SettlementObservation;
import ru.kaiserroman.millenairearmies.persistence.PlayerRealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.RealmGovernanceSavedData;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;

/** Canonical player administration must dual-write compatibility state and bind Simulation. */
public final class RealmAdministrationServiceSelfTest {
    private RealmAdministrationServiceSelfTest() {}

    public static void main(String[] args) {
        ResourceLocation overworld = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        ResourceLocation culture = ResourceLocation.fromNamespaceAndPath("millenaire", "norman");
        UUID owner = uuid(1);
        UUID outsider = uuid(2);
        UUID capital = uuid(101);

        RealmSavedData realms = new RealmSavedData();
        SimulationSavedData simulation = new SimulationSavedData();
        PlayerRealmSavedData legacyRealms = new PlayerRealmSavedData();
        RealmGovernanceSavedData legacyGovernance = new RealmGovernanceSavedData();

        long simulationSettlement = simulation.keys().internSettlement(capital);
        int cultureKey = simulation.keys().internCulture(culture);
        simulation.state().beginObservation();
        int simulationRow = simulation.state().observe(
                new SettlementObservation(
                        simulationSettlement,
                        cultureKey,
                        RealmRegistry.NO_REALM,
                        77L,
                        120L,
                        180L,
                        12,
                        7,
                        600,
                        700,
                        0,
                        450,
                        650,
                        700,
                        400),
                0L);
        simulation.state().finishObservation();
        check(simulationRow >= 0, "simulation capital prepared");

        RealmAdministrationService service = new RealmAdministrationService(
                realms,
                simulation,
                legacyRealms,
                legacyGovernance);
        check(service.canFoundPlayerRealm(owner, 2),
                "capacity-aware foundation preflight accepts capital plus hamlet");
        long realmId = service.foundPlayerRealm(
                owner,
                capital,
                "Alder March",
                overworld,
                24_000L,
                12L);

        check(realmId != RealmRegistry.NO_REALM, "canonical Realm founded");
        check(realms.realmForPlayer(owner) == realmId, "owner assigned canonically");
        check(realms.realmForSettlement(capital) == realmId, "capital assigned canonically");
        check(realms.registry().government(realmId) == GovernmentForm.FEUDAL_MONARCHY,
                "initial government canonical");
        check(realms.registry().settlementCount(realmId) == 1, "one canonical settlement");
        check("Alder March".equals(realms.name(realmId)), "canonical name stored");
        check(realms.taxRate(realmId) == RealmAdministrationService.INITIAL_TAX_RATE,
                "canonical initial tax stored");
        check(simulation.state().realmIdAt(simulationRow) == realmId,
                "Simulation settlement assigned to canonical Realm");

        PlayerRealmSavedData.View legacyView = new PlayerRealmSavedData.View();
        check(legacyRealms.read(owner, legacyView), "compatibility Realm written");
        check(legacyView.capitalMost() == capital.getMostSignificantBits()
                        && legacyView.capitalLeast() == capital.getLeastSignificantBits(),
                "compatibility capital preserved");
        check(legacyView.taxRate() == RealmAdministrationService.INITIAL_TAX_RATE,
                "compatibility initial tax preserved");

        RealmGovernanceSavedData.AssignmentView assignment =
                new RealmGovernanceSavedData.AssignmentView();
        check(legacyGovernance.readPlayer(owner, assignment), "compatibility governance written");
        check(assignment.isHead() && owner.equals(assignment.head()) && capital.equals(assignment.village()),
                "compatibility head assignment preserved");

        check(!service.setTaxRate(outsider, 17), "non-member cannot change tax");
        check(service.setTaxRate(owner, 17), "head changes canonical tax");
        check(realms.taxRate(realmId) == 17, "canonical tax changed");
        check(legacyRealms.read(owner, legacyView) && legacyView.taxRate() == 17,
                "compatibility tax changed");

        long ownerSubject = realms.keys().findPlayer(owner);
        UUID generatedHamlet = uuid(104);
        long generatedSubject = realms.keys().internSettlement(generatedHamlet);
        check(realms.registry().addMember(
                realmId,
                generatedSubject,
                ru.kaiserroman.millenaire.realm.RealmMemberKind.PLAYER_SETTLEMENT,
                ownerSubject,
                700), "generated hamlet attached canonically");
        check(service.attachFoundedRegion(owner, generatedHamlet),
                "generated hamlet mirrored without conquest");
        check(legacyGovernance.canCommandSettlement(owner, generatedHamlet),
                "head commands generated governor-led hamlet");
        check(realms.capturedSettlementCount(realmId) == 0,
                "generated hamlet is not recorded as a capture");

        UUID captured = uuid(103);
        check(service.canRecordCapture(owner, captured),
                "capture compatibility preflight accepts a free governor slot");
        long capturedSubject = realms.keys().internSettlement(captured);
        check(realms.registry().addMember(
                realmId,
                capturedSubject,
                ru.kaiserroman.millenaire.realm.RealmMemberKind.PLAYER_SETTLEMENT,
                ownerSubject,
                650), "captured settlement attached canonically");
        check(service.recordCapture(owner, captured), "capture metadata recorded");
        check(realms.capturedSettlementCount(realmId) == 1, "canonical capture count changed");
        check(legacyRealms.read(owner, legacyView) && legacyView.capturedSettlements() == 1,
                "compatibility capture count changed");
        check(legacyGovernance.canCommandSettlement(owner, captured),
                "head commands deterministic governor-led captured region");
        check(legacyGovernance.readVillage(captured, assignment)
                        && assignment.role() == RealmGovernanceSavedData.ROLE_GOVERNOR,
                "captured region mirrored as governor-led");

        check(service.foundPlayerRealm(
                        owner,
                        uuid(102),
                        "Duplicate Crown",
                        overworld,
                        48_000L,
                        24L)
                        == RealmRegistry.NO_REALM,
                "duplicate owner rejected");
        check(service.foundPlayerRealm(
                        outsider,
                        capital,
                        "Capital Thief",
                        overworld,
                        48_000L,
                        24L)
                        == RealmRegistry.NO_REALM,
                "duplicate capital rejected");
        check(service.foundationCount() == 1L, "foundation metric bounded");
        check(service.taxChangeCount() == 1L, "tax metric counts accepted mutations");
        check(service.compatibilityMismatchCount() == 0L, "compatibility writes stayed consistent");

        System.out.println("Realm administration self-test passed");
    }

    private static UUID uuid(long least) {
        return new UUID(0x5000000000000000L, least);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
