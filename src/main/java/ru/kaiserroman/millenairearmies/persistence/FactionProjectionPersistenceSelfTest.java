package ru.kaiserroman.millenairearmies.persistence;

import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenairearmies.model.FactionAllegiance;

/** Lightweight assertions for stable ids and relation pruning; run with {@code -ea}. */
public final class FactionProjectionPersistenceSelfTest {
    private FactionProjectionPersistenceSelfTest() {
    }

    public static void main(String[] args) {
        ResourceLocation norman = ResourceLocation.parse("millenaire:norman");
        int normanId = FactionIdentitySavedData.stableId(norman, 0);
        check(normanId >= 0, "stable faction id is non-negative");
        check(normanId == FactionIdentitySavedData.stableId(ResourceLocation.parse("millenaire:norman"), 0),
                "same culture has the same id");
        check(normanId != FactionIdentitySavedData.stableId(norman, 1),
                "collision salt selects a different id");
        check(normanId != FactionIdentitySavedData.stableId(ResourceLocation.parse("millenaire:japanese"), 0),
                "distinct fixture cultures have distinct ids");

        FactionIdentitySavedData identityData = new FactionIdentitySavedData();
        int persistedNorman = identityData.resolve(norman);
        int persistedJapanese = identityData.resolve(ResourceLocation.parse("millenaire:japanese"));
        FactionIdentitySavedData restoredIdentities =
                FactionIdentitySavedData.load(identityData.save(new net.minecraft.nbt.CompoundTag(), null), null);
        check(restoredIdentities.factionId(norman) == persistedNorman, "norman faction id persisted");
        check(restoredIdentities.factionId(ResourceLocation.parse("millenaire:japanese")) == persistedJapanese,
                "japanese faction id persisted");

        PackedFactionState relations = new PackedFactionState(4);
        relations.put(10, 20, FactionAllegiance.NEUTRAL.code(), (short) 0);
        relations.put(20, 10, FactionAllegiance.FRIENDLY.code(), (short) 40);
        relations.put(20, 30, FactionAllegiance.HOSTILE.code(), (short) -60);
        long revisionBeforeRemoval = relations.nextRevision();
        int removed = relations.removeRelationsOutside(new int[] {10, 20}, 2);
        check(removed == 1 && relations.size() == 2, "stale faction relation pruned");
        check(relations.nextRevision() == revisionBeforeRemoval + 1, "removal advances relation revision");

        PackedFactionState.Cursor cursor = relations.newCursor();
        for (cursor.reset(); cursor.advance(); ) {
            check(cursor.sourceFactionId() != 30 && cursor.targetFactionId() != 30,
                    "removed faction is absent from surviving rows");
        }

        PackedFactionState denseGraph = new PackedFactionState();
        for (int source = 0; source < 64; source++) {
            for (int target = 0; target < 64; target++) {
                if (source != target) {
                    check(denseGraph.put(source, target, FactionAllegiance.NEUTRAL.code(), (short) 0),
                            "new dense relation inserted");
                }
            }
        }
        check(denseGraph.size() == 64 * 63, "dense indexed graph size");
        check(!denseGraph.put(17, 42, FactionAllegiance.NEUTRAL.code(), (short) 0),
                "indexed no-op update found existing pair");
        check(denseGraph.put(17, 42, FactionAllegiance.HOSTILE.code(), (short) -90),
                "indexed mutation found existing pair");
        int[] retained = new int[32];
        for (int faction = 0; faction < retained.length; faction++) {
            retained[faction] = faction;
        }
        check(denseGraph.removeRelationsOutside(retained, retained.length) == (64 * 63 - 32 * 31),
                "bulk relation pruning compacts dense graph");
        check(denseGraph.size() == 32 * 31, "dense lookup rebuilt after compaction");
        check(!denseGraph.put(7, 11, FactionAllegiance.NEUTRAL.code(), (short) 0),
                "rebuilt lookup still finds surviving pair");
        System.out.println("Faction projection persistence self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
