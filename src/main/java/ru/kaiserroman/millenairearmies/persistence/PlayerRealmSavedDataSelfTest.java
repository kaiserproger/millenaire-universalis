package ru.kaiserroman.millenairearmies.persistence;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/** Deterministic persistence/validation checks for player-founded realm governance. */
public final class PlayerRealmSavedDataSelfTest {
    private PlayerRealmSavedDataSelfTest() {}

    public static void main(String[] args) {
        UUID owner = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID capital = UUID.fromString("20000000-0000-0000-0000-000000000002");
        PlayerRealmSavedData data = new PlayerRealmSavedData();
        check(data.found(owner, "Caen Realm", capital,
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 1_000L),
                "realm founded");
        check(!data.found(owner, "Duplicate", capital,
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 1_001L),
                "duplicate owner rejected");
        check(data.setTaxRate(owner, 15), "tax updated");
        data.collectTaxes(owner.getMostSignificantBits(), owner.getLeastSignificantBits(), 42L, 25_000L);
        data.recordCapture(owner);
        check(data.rename(owner, "Norman March"), "realm renamed");

        PlayerRealmSavedData.View before = new PlayerRealmSavedData.View();
        check(data.read(owner, before), "realm readable");
        check(before.taxRate() == 15 && before.treasury() == 42L
                        && before.capturedSettlements() == 1
                        && "Norman March".equals(before.name()),
                "governance state retained");

        CompoundTag saved = data.save(new CompoundTag(), null);
        PlayerRealmSavedData restored = PlayerRealmSavedData.load(saved, null);
        PlayerRealmSavedData.View after = new PlayerRealmSavedData.View();
        check(restored.read(owner, after), "restored realm readable");
        check(after.revision() == before.revision()
                        && after.taxRate() == before.taxRate()
                        && after.treasury() == before.treasury()
                        && after.lastTaxTick() == before.lastTaxTick()
                        && after.capitalMost() == capital.getMostSignificantBits()
                        && after.capitalLeast() == capital.getLeastSignificantBits(),
                "realm NBT round-trip parity");

        expectIllegal(() -> restored.setTaxRate(owner, 26), "tax upper bound");
        expectIllegal(() -> new PlayerRealmSavedData().found(
                UUID.randomUUID(), " ", capital,
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 0L),
                "blank realm name");

        UUID feudal = UUID.fromString("10000000-0000-0000-0000-000000000003");
        UUID governor = UUID.fromString("10000000-0000-0000-0000-000000000004");
        UUID feudalVillage = UUID.fromString("20000000-0000-0000-0000-000000000003");
        UUID governorVillage = UUID.fromString("20000000-0000-0000-0000-000000000004");
        RealmGovernanceSavedData governance = new RealmGovernanceSavedData();
        check(governance.foundCapital(
                        owner, capital, RealmGovernanceSavedData.GOVERNMENT_FEUDAL),
                "capital owner becomes realm head");
        check(!governance.foundCapital(
                        owner, feudalVillage, RealmGovernanceSavedData.GOVERNMENT_FEUDAL),
                "one player cannot control a second settlement");
        check(governance.attachRegion(owner, feudal, feudalVillage),
                "feudal government creates feudal region");
        check(!governance.attachRegion(owner, feudal, governorVillage),
                "regional player cannot control two settlements");
        check(governance.attachRegion(
                        owner, governor, governorVillage, RealmGovernanceSavedData.ROLE_GOVERNOR),
                "governor region attached");
        check(!governance.canCommandSettlement(owner, feudalVillage),
                "realm head cannot bypass a feudal owner");
        check(governance.canCommandSettlement(feudal, feudalVillage),
                "feudal owner commands own settlement");
        check(governance.canCommandSettlement(owner, governorVillage),
                "realm head may direct a governor region");
        check(governance.canCommandSettlement(governor, governorVillage),
                "governor keeps local control");
        check(governance.canDirectController(
                        owner.getMostSignificantBits(),
                        owner.getLeastSignificantBits(),
                        governor.getMostSignificantBits(),
                        governor.getLeastSignificantBits()),
                "realm head receives governor army delegation");
        check(!governance.canDirectController(
                        owner.getMostSignificantBits(),
                        owner.getLeastSignificantBits(),
                        feudal.getMostSignificantBits(),
                        feudal.getLeastSignificantBits()),
                "feudal army remains outside direct capital authority");
        check(governance.setRegionalRole(
                        owner, feudalVillage, RealmGovernanceSavedData.ROLE_GOVERNOR),
                "head may centralize a region");
        check(governance.canCommandSettlement(owner, feudalVillage),
                "centralized region accepts capital directives");
        check(governance.settlementCount(owner) == 3 && governance.regionCount(owner) == 2,
                "capital and regions counted separately");

        RealmGovernanceSavedData.AssignmentView membership =
                new RealmGovernanceSavedData.AssignmentView();
        check(governance.readPlayer(feudal, membership)
                        && membership.head().equals(owner)
                        && membership.village().equals(feudalVillage)
                        && membership.role() == RealmGovernanceSavedData.ROLE_GOVERNOR,
                "player assignment is explicit and unique");
        CompoundTag governanceSaved = governance.save(new CompoundTag(), null);
        RealmGovernanceSavedData governanceRestored =
                RealmGovernanceSavedData.load(governanceSaved, null);
        check(governanceRestored.size() == governance.size()
                        && governanceRestored.revision() == governance.revision()
                        && governanceRestored.canCommandSettlement(owner, governorVillage),
                "governance NBT round-trip parity");
        System.out.println("Player realm persistence self-test passed");
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
