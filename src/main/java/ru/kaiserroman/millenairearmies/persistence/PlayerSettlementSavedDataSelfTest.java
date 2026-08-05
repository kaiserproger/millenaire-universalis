package ru.kaiserroman.millenairearmies.persistence;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenairearmies.server.settlement.PlayerSettlementTier;

/** Deterministic persistence checks for player settlement identity and territorial progression. */
public final class PlayerSettlementSavedDataSelfTest {
    private PlayerSettlementSavedDataSelfTest() {}

    public static void main(String[] args) {
        UUID owner = UUID.randomUUID();
        UUID capital = UUID.randomUUID();
        PlayerSettlementSavedData data = new PlayerSettlementSavedData();
        check(data.register(owner, capital, 7L,
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 80, 100L),
                "registered");
        check(!data.register(owner, UUID.randomUUID(), 8L,
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 96, 100L),
                "duplicate owner rejected");
        check(!data.register(UUID.randomUUID(), UUID.randomUUID(), 7L,
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"), 96, 100L),
                "duplicate Realm rejected");
        check(data.updateAssessment(owner, PlayerSettlementTier.TOWN, 224, 620, 500L),
                "assessment updated");
        long expandedTerritoryRevision = data.territoryRevision();
        check(data.updateAssessment(owner, PlayerSettlementTier.VILLAGE, 128, 400, 600L),
                "later assessment accepted");
        check(data.territoryRevision() == expandedTerritoryRevision,
                "development-only assessment does not churn territory geometry");
        PlayerSettlementSavedData.View monotonic = new PlayerSettlementSavedData.View();
        check(data.view(owner, monotonic) && monotonic.territoryRadius == 224,
                "territory does not shrink");
        long stableRevision = data.revision();
        check(data.updateAssessment(owner, PlayerSettlementTier.VILLAGE, 128, 400, 800L)
                        && data.revision() == stableRevision,
                "unchanged assessment does not churn the general revision");
        expectIllegal(() -> data.updateAssessment(owner, PlayerSettlementTier.TOWN, 513, 700, 700L),
                "oversized territory rejected");
        expectIllegal(() -> data.updateAssessment(owner, PlayerSettlementTier.TOWN, 224, 700, 99L),
                "pre-foundation assessment rejected");

        CompoundTag saved = data.save(new CompoundTag(), null);
        PlayerSettlementSavedData restored = PlayerSettlementSavedData.load(saved, null);
        PlayerSettlementSavedData.View view = new PlayerSettlementSavedData.View();
        check(restored.view(owner, view), "restored view");
        check(view.capital.equals(capital) && view.realmId == 7L, "identity restored");
        PlayerSettlementSavedData.View realmView = new PlayerSettlementSavedData.View();
        check(restored.viewRealm(7L, realmView) && realmView.owner.equals(owner),
                "Realm lookup restored");
        check(view.tier == PlayerSettlementTier.TOWN && view.territoryRadius == 224,
                "progression restored");
        check(view.development == 400
                        && restored.revision() == data.revision()
                        && restored.territoryRevision() == data.territoryRevision(),
                "revisions restored");
        CompoundTag malformed = saved.copy();
        malformed.getList("Settlements", Tag.TAG_COMPOUND)
                .getCompound(0)
                .putInt("TerritoryRadius", 513);
        expectIllegal(() -> PlayerSettlementSavedData.load(malformed, null),
                "oversized persisted territory rejected");
        System.out.println("PlayerSettlementSavedDataSelfTest: OK");
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
