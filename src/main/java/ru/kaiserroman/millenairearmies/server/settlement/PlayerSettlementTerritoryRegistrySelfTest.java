package ru.kaiserroman.millenairearmies.server.settlement;

import net.minecraft.core.BlockPos;

/** Pure checks for loaded-chunk territory capping without starting a Minecraft server. */
public final class PlayerSettlementTerritoryRegistrySelfTest {
    private PlayerSettlementTerritoryRegistrySelfTest() {}

    public static void main(String[] args) {
        BlockPos center = new BlockPos(8, 64, 8);

        int oneRing = PlayerSettlementTerritoryRegistry.loadedRadius(
                center,
                16,
                64,
                (chunkX, chunkZ) -> Math.abs(chunkX) <= 1 && Math.abs(chunkZ) <= 1);
        check(oneRing == 23, "one loaded chunk ring exposes exactly 23 blocks from an offset center");

        int hole = PlayerSettlementTerritoryRegistry.loadedRadius(
                center,
                16,
                64,
                (chunkX, chunkZ) -> Math.abs(chunkX) <= 2
                        && Math.abs(chunkZ) <= 2
                        && !(chunkX == 2 && chunkZ == 0));
        check(hole == 23, "a hole in the next ring stops expansion before force-loading");

        int full = PlayerSettlementTerritoryRegistry.loadedRadius(
                center,
                16,
                64,
                (chunkX, chunkZ) -> Math.abs(chunkX) <= 4 && Math.abs(chunkZ) <= 4);
        check(full == 64, "complete loaded square reaches the requested radius");

        int belowMinimum = PlayerSettlementTerritoryRegistry.loadedRadius(
                center,
                32,
                64,
                (chunkX, chunkZ) -> Math.abs(chunkX) <= 1 && Math.abs(chunkZ) <= 1);
        check(belowMinimum == 0, "insufficient contiguous area fails closed");

        int capped = PlayerSettlementTerritoryRegistry.loadedRadius(
                center,
                16,
                700,
                (chunkX, chunkZ) -> Math.abs(chunkX) <= 40 && Math.abs(chunkZ) <= 40);
        check(capped == PlayerSettlementPolicy.MAXIMUM_RADIUS, "loaded radius remains globally capped");

        check(PlayerSettlementTerritoryRegistry.loadedRadius(
                        center,
                        32,
                        16,
                        (chunkX, chunkZ) -> true) == 0,
                "requested radius below the minimum is rejected");

        System.out.println("PlayerSettlementTerritoryRegistrySelfTest: OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
