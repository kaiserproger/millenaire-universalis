package ru.kaiserroman.millenairearmies.server.integration;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenaire.realm.GovernmentForm;
import ru.kaiserroman.millenaire.realm.RealmMemberKind;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireEntityBridge;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireVillageIndex;
import ru.kaiserroman.millenairearmies.persistence.PackedUnitMembership;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.StableDimensionTable;
import ru.kaiserroman.millenairearmies.server.service.PackedArmyControllers;

/** Controller mapping and ECS generation reuse must not leak stale canonical Realm identities. */
public final class CanonicalArmyRealmIdentityResolverSelfTest {
    private CanonicalArmyRealmIdentityResolverSelfTest() {}

    public static void main(String[] args) {
        PackedArmyEcs ecs = new PackedArmyEcs(8, 8);
        PackedArmyControllers controllers = new PackedArmyControllers(8);
        PackedUnitMembership memberships = new PackedUnitMembership();
        StableDimensionTable dimensions = new StableDimensionTable();
        dimensions.intern(ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"));
        RealmSavedData realms = new RealmSavedData();

        UUID player = uuid(1);
        long playerSubject = realms.keys().internPlayer(player);
        long firstCapital = realms.keys().internSettlement(uuid(101));
        long firstRealm = realms.registry().createRealm(
                firstCapital,
                RealmMemberKind.PLAYER_SETTLEMENT,
                playerSubject,
                GovernmentForm.FEUDAL_MONARCHY,
                700,
                0L);
        check(realms.registry().addMember(
                firstRealm,
                playerSubject,
                RealmMemberKind.PLAYER,
                playerSubject,
                1000),
                "player attached to first Realm");

        long secondCapital = realms.keys().internSettlement(uuid(102));
        long secondRealm = realms.registry().createRealm(
                secondCapital,
                RealmMemberKind.NPC_SETTLEMENT,
                0L,
                GovernmentForm.CLAN_CONFEDERATION,
                650,
                0L);
        check(firstRealm != 0L && secondRealm != 0L, "Realms created");

        int playerArmy = ecs.createArmy(10, 0, 0, 0, PackedArmyEcs.packBlockPos(0, 64, 0));
        controllers.put(
                playerArmy,
                player.getMostSignificantBits(),
                player.getLeastSignificantBits(),
                true);
        int unresolvedArmy = ecs.createArmy(20, 0, 0, 0, PackedArmyEcs.packBlockPos(50, 64, 50));

        CanonicalArmyRealmIdentityResolver resolver = new CanonicalArmyRealmIdentityResolver(
                ecs,
                controllers,
                memberships,
                dimensions,
                new MillenaireEntityBridge(),
                new MillenaireVillageIndex(),
                realms,
                160);
        check(resolver.reconcile() == 2, "initial army projection built");
        check(resolver.realmForArmy(playerArmy) == firstRealm, "controller maps army to first Realm");
        check(resolver.realmForArmy(unresolvedArmy) == 0L, "anonymous army remains unresolved");
        check(resolver.unresolvedArmyCount() == 1, "unresolved metric");
        check(resolver.reconcile() == 0, "stable projection is idempotent");

        check(realms.registry().updateMember(
                playerSubject,
                secondRealm,
                RealmMemberKind.PLAYER,
                playerSubject,
                900),
                "player transferred to second Realm");
        check(resolver.reconcile() == 1, "Realm transfer changes one army projection");
        check(resolver.realmForArmy(playerArmy) == secondRealm, "controller transfer reaches army");

        check(ecs.removeArmy(playerArmy), "old army removed");
        controllers.remove(playerArmy);
        int replacement = ecs.createArmy(30, 0, 0, 0, PackedArmyEcs.packBlockPos(5, 64, 5));
        check(PackedArmyEcs.handleSlotIndex(replacement)
                        == PackedArmyEcs.handleSlotIndex(playerArmy),
                "ECS slot reused with a new generation");
        check(replacement != playerArmy, "generation changed handle");
        check(resolver.reconcile() >= 1, "slot reuse invalidates cached Realm identity");
        check(resolver.realmForArmy(replacement) == 0L,
                "replacement army does not inherit removed controller Realm");
        check(resolver.realmForArmy(playerArmy) == 0L, "stale handle resolves to no Realm");
        System.out.println("Canonical army Realm identity resolver self-test passed");
    }

    private static UUID uuid(long least) {
        return new UUID(0x7100000000000000L, least);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
