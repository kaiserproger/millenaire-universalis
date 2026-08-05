package ru.kaiserroman.millenairearmies.server.settlement;

import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;

/** Checks the bounded, deterministic foundation-site search envelope without loading a world. */
public final class PlayerSettlementFoundationSelfTest {
    private PlayerSettlementFoundationSelfTest() {}

    public static void main(String[] args) {
        List<BlockPos> offsets = PlayerSettlementService.foundationCandidateOffsets();
        check(offsets.size() == 32, "foundation search uses the exact bounded attempt count");
        check(BlockPos.ZERO.equals(offsets.getFirst()), "player position is checked first");
        check(new HashSet<>(offsets).size() == offsets.size(), "foundation candidates are unique");

        int previousRing = -1;
        for (BlockPos offset : offsets) {
            int ring = Math.max(Math.abs(offset.getX()), Math.abs(offset.getZ()));
            check(offset.getY() == 0, "foundation offsets stay horizontal");
            check(ring <= 96, "foundation offset remains inside the search radius");
            check(offset.getX() % 32 == 0 && offset.getZ() % 32 == 0,
                    "foundation offsets follow the fixed search grid");
            check(ring >= previousRing, "foundation candidates progress from near to far rings");
            previousRing = ring;
        }
        System.out.println("PlayerSettlementFoundationSelfTest: OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
