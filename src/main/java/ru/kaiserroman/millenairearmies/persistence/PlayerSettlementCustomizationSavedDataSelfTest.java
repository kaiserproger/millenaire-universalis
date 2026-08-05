package ru.kaiserroman.millenairearmies.persistence;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenairearmies.server.settlement.PlayerSettlementProfile;

/** Deterministic bounds and restart checks for player settlement customization. */
public final class PlayerSettlementCustomizationSavedDataSelfTest {
    private PlayerSettlementCustomizationSavedDataSelfTest() {}

    public static void main(String[] args) {
        UUID owner = UUID.fromString("31000000-0000-0000-0000-000000000001");
        UUID settlement = UUID.fromString("32000000-0000-0000-0000-000000000001");
        ResourceLocation type = ResourceLocation.fromNamespaceAndPath("millenaire", "norman/controlled");
        PlayerSettlementCustomizationSavedData data = new PlayerSettlementCustomizationSavedData();
        check(data.found(owner, settlement, type, "New Caen"), "foundation customization persisted");
        check(!data.found(owner, UUID.randomUUID(), type, "Duplicate owner"), "duplicate owner rejected");
        check(!data.found(UUID.randomUUID(), settlement, type, "Duplicate settlement"), "duplicate settlement rejected");
        check(data.setProfile(owner, PlayerSettlementProfile.INDUSTRY), "profile changed");
        check(data.setAutomatic(owner, false), "automatic development changed");
        check(data.setQueueLimit(owner, 5), "queue limit changed");
        check(data.rename(owner, "Iron March"), "name changed");

        PlayerSettlementCustomizationSavedData.View before =
                new PlayerSettlementCustomizationSavedData.View();
        check(data.read(owner, before), "owned row readable");
        check(before.owner().equals(owner)
                        && before.settlement().equals(settlement)
                        && before.villageType().equals(type)
                        && before.profile() == PlayerSettlementProfile.INDUSTRY
                        && !before.automatic()
                        && before.queueLimit() == 5
                        && "Iron March".equals(before.name())
                        && before.revision() > 0L,
                "customization retained");

        CompoundTag tag = data.save(new CompoundTag(), null);
        PlayerSettlementCustomizationSavedData restored =
                PlayerSettlementCustomizationSavedData.load(tag, null);
        PlayerSettlementCustomizationSavedData.View after =
                new PlayerSettlementCustomizationSavedData.View();
        check(restored.readSettlement(settlement, after), "settlement row restored");
        check(after.owner().equals(before.owner())
                        && after.settlement().equals(before.settlement())
                        && after.villageType().equals(before.villageType())
                        && after.profile() == before.profile()
                        && after.automatic() == before.automatic()
                        && after.queueLimit() == before.queueLimit()
                        && after.name().equals(before.name())
                        && after.revision() == before.revision(),
                "restart parity");

        check(restored.setAutomatic(owner, true), "automatic re-enabled");
        int[] visits = {0};
        int cursor = restored.visitAutomatic(0, 1,
                (ownerMost, ownerLeast, settlementMost, settlementLeast, profile, queueLimit) -> {
                    visits[0]++;
                    check(new UUID(ownerMost, ownerLeast).equals(owner), "visitor owner");
                    check(new UUID(settlementMost, settlementLeast).equals(settlement), "visitor settlement");
                    check(profile == PlayerSettlementProfile.INDUSTRY && queueLimit == 5,
                            "visitor customization");
                });
        check(visits[0] == 1 && cursor == 0, "bounded cyclic visitor");

        expectIllegal(() -> restored.setQueueLimit(owner, 0), "queue lower bound");
        expectIllegal(() -> restored.setQueueLimit(owner, 9), "queue upper bound");
        expectIllegal(() -> new PlayerSettlementCustomizationSavedData().found(
                UUID.randomUUID(), UUID.randomUUID(), type, " "), "blank name");
        expectIllegal(() -> new PlayerSettlementCustomizationSavedData().found(
                UUID.randomUUID(), UUID.randomUUID(), type, "x".repeat(49)), "long name");

        CompoundTag malformed = tag.copy();
        malformed.putInt("SchemaVersion", 99);
        expectIllegal(
                () -> PlayerSettlementCustomizationSavedData.load(malformed, null),
                "future schema rejected");
        System.out.println("PlayerSettlementCustomizationSavedDataSelfTest: OK");
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
