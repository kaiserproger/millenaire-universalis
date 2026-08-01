package ru.kaiserroman.millenairearmies.server.execution;

import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;

/** Pure validation helpers shared by the dormant gate self-test and optional runtime bridge. */
public final class OrderExecutionPolicy {
    private OrderExecutionPolicy() {}

    public static boolean shouldStart(boolean configured) {
        return configured;
    }

    public static boolean targetWithinBuildHeight(
            long packedTarget, int minimumY, int maximumYExclusive) {
        int y = PackedArmyEcs.unpackBlockY(packedTarget);
        return y >= minimumY && y < maximumYExclusive;
    }
}
