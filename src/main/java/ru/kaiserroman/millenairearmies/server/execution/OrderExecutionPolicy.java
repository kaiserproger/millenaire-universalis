package ru.kaiserroman.millenairearmies.server.execution;

import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.persistence.StableDimensionTable;

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

    public static boolean targetInLevel(
            StableDimensionTable dimensions,
            int targetDimensionId,
            ResourceLocation levelDimension) {
        return targetDimensionId != PackedArmyEcs.UNKNOWN_DIMENSION
                && dimensions.matches(targetDimensionId, levelDimension);
    }
}
