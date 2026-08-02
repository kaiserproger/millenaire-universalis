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
