package ru.kaiserroman.millenairearmies.server.realm;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenaire.realm.GovernmentForm;
import ru.kaiserroman.millenaire.realm.RealmMemberKind;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenairearmies.persistence.PlayerRealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.RealmGovernanceSavedData;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;

/** Legacy player Realm state must mirror without touching native NPC Realms. */
public final class LegacyRealmMirrorServiceSelfTest {
    private LegacyRealmMirrorServiceSelfTest() {}

    public static void main(String[] args) {
        PlayerRealmSavedData legacyRealms = new PlayerRealmSavedData();
        RealmGovernanceSavedData legacyGovernance = new RealmGovernanceSavedData();
        ResourceLocation overworld = ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
        UUID headA = uuid(1);
        UUID capitalA = uuid(101);
        UUID headB = uuid(2);
        UUID capitalB = uuid(102);
        UUID governor = uuid(3);
        UUID province = uuid(103);

        check(legacyRealms.found(headA, "Alder March", capitalA, overworld, 0L), "legacy A founded");
        check(legacyGovernance.foundCapital(
                headA, capitalA, RealmGovernanceSavedData.GOVERNMENT_FEUDAL),
                "legacy A governance");
        check(legacyRealms.found(headB, "Copper Crown", capitalB, overworld, 0L), "legacy B founded");
        check(legacyGovernance.foundCapital(
                headB, capitalB, RealmGovernanceSavedData.GOVERNMENT_ADMINISTRATIVE),
                "legacy B governance");
        check(legacyGovernance.attachRegion(
                headA, governor, province, RealmGovernanceSavedData.ROLE_FEUDAL),
                "legacy province attached");
        legacyRealms.collectTaxes(
                headA.getMostSignificantBits(), headA.getLeastSignificantBits(), 1_200L, 24_000L);

        RealmSavedData target = new RealmSavedData();
        long npcCapital = target.keys().internSettlement(uuid(900));
        long npcRealm = target.registry().createRealm(
                npcCapital,
                RealmMemberKind.NPC_SETTLEMENT,
                0L,
                GovernmentForm.CLAN_CONFEDERATION,
                700,
                0L);
        check(npcRealm != RealmRegistry.NO_REALM, "native NPC Realm created");
        target.upsertMetadata(npcRealm, "Wolf River Council", 0, 0L, false);

        LegacyRealmMirrorService mirror = new LegacyRealmMirrorService(target);
        check(mirror.reconcile(legacyRealms, legacyGovernance) == 1, "initial mirror changed");
        long realmA = target.realmForPlayer(headA);
        long realmB = target.realmForPlayer(headB);
        check(realmA != 0L && realmB != 0L && realmA != realmB, "legacy Realms mapped separately");
        check(target.realmForSettlement(capitalA) == realmA, "capital A mapped");
        check(target.realmForSettlement(capitalB) == realmB, "capital B mapped");
        check(target.realmForSettlement(province) == realmA, "province initially in A");
        check(target.realmForPlayer(governor) == realmA, "governor initially in A");
        check(target.registry().government(realmA) == GovernmentForm.FEUDAL_MONARCHY,
                "feudal government mapped");
        check(target.registry().government(realmB) == GovernmentForm.BUREAUCRATIC_MONARCHY,
                "administrative government mapped");
        check(target.treasury(realmA) == 1_200L, "treasury mirrored");
        check(target.registry().exists(npcRealm) && !target.isLegacy(npcRealm),
                "native NPC Realm preserved");

        check(legacyGovernance.removeRegion(headA, province), "province removed from A");
        check(legacyGovernance.attachRegion(
                headB, governor, province, RealmGovernanceSavedData.ROLE_GOVERNOR),
                "province captured by B");
        legacyRealms.rename(headB, "Copper Empire");
        legacyRealms.setTaxRate(headB, 20);
        legacyRealms.collectTaxes(
                headB.getMostSignificantBits(), headB.getLeastSignificantBits(), 900L, 48_000L);

        check(mirror.reconcile(legacyRealms, legacyGovernance) == 1, "capture mirror changed");
        check(target.realmForSettlement(province) == realmB, "captured province transferred");
        check(target.realmForPlayer(governor) == realmB, "captured governor transferred");
        check("Copper Empire".equals(target.name(realmB)), "rename mirrored");
        check(target.taxRate(realmB) == 20 && target.treasury(realmB) == 900L,
                "fiscal changes mirrored");
        check(target.registry().exists(npcRealm), "native NPC Realm still preserved");
        target.registry().setGovernment(realmA, GovernmentForm.MERCHANT_REPUBLIC);
        target.registry().setLegitimacy(realmA, 640);
        target.institutions().update(
                realmA,
                target.institutions().constitution(realmA)
                        .towards(GovernmentForm.MERCHANT_REPUBLIC, 180)
                        .withLegitimacy(640),
                0,
                100L);
        check(mirror.reconcile(legacyRealms, legacyGovernance) == 0,
                "legacy mirror ignores evolved constitution");
        check(target.registry().government(realmA) == GovernmentForm.MERCHANT_REPUBLIC
                        && target.registry().legitimacy(realmA) == 640,
                "evolved constitution preserved");
        System.out.println("Legacy Realm mirror self-test passed");
    }

    private static UUID uuid(long least) {
        return new UUID(0x4000000000000000L, least);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
