package ru.kaiserroman.millenairearmies.presentation.client;

import net.minecraft.resources.ResourceLocation;

/** Deterministic structural test for hash collisions, churn, alias replacement, and swap-remove. */
public final class ClientUnitPresentationIndexSelfTest {
    private ClientUnitPresentationIndexSelfTest() {}

    public static void main(String[] args) {
        ResourceLocation placeholder = ResourceLocation.fromNamespaceAndPath("millenaire_armies", "missing");
        ClientUnitPresentation presentation = ClientUnitPresentation.resolve(
                ClientPresentationCatalog.INSTANCE,
                placeholder,
                placeholder,
                placeholder,
                placeholder,
                (byte) 0);
        ClientUnitPresentationIndex index = new ClientUnitPresentationIndex();
        index.reserve(1_024);

        for (int i = 0; i < 1_000; i++) {
            index.put(0x1234_5678_0000_0000L + i, ~((long) i), i, presentation);
        }
        check(index.size() == 1_000, "initial size");
        for (int i = 0; i < 1_000; i++) {
            check(index.get(0x1234_5678_0000_0000L + i, ~((long) i)) == presentation, "UUID lookup");
            check(index.getByMirrorId(i) == presentation, "mirror lookup");
        }

        for (int i = 0; i < 1_000; i += 2) {
            check(index.removeByMirrorId(i), "remove by mirror id");
        }
        index.checkInvariants();
        check(index.size() == 500, "size after removal");

        for (int i = 0; i < 2_000; i++) {
            long most = 0x7fff_0000_0000_0000L + i;
            long least = Long.rotateLeft(i, 17);
            index.put(most, least, 10_000 + i, presentation);
            if ((i & 1) == 0) {
                check(index.remove(most, least), "churn removal");
            }
        }
        index.checkInvariants();

        long oldMost = 0x1234_5678_0000_0000L + 43;
        long oldLeast = ~43L;
        index.put(99L, 100L, 43, presentation);
        check(index.get(oldMost, oldLeast) == null, "mirror alias evicts old UUID");
        check(index.get(99L, 100L) == presentation, "replacement UUID present");

        index.put(99L, 100L, 99_999, presentation);
        check(index.getByMirrorId(43) == null, "old mirror removed on UUID update");
        check(index.getByMirrorId(99_999) == presentation, "new mirror present");
        index.checkInvariants();

        index.clear();
        index.checkInvariants();
        check(index.size() == 0, "clear");
        System.out.println("Client unit presentation index self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
