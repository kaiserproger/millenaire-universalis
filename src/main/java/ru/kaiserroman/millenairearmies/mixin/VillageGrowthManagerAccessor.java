package ru.kaiserroman.millenairearmies.mixin;

import org.millenaire.village.Village;
import org.millenaire.village.VillageGrowthManager;
import org.millenaire.world.VillageTerrainMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes Millenaire's package-private occupancy projection without creating a split package. */
@Mixin(value = VillageGrowthManager.class, remap = false)
public interface VillageGrowthManagerAccessor {
    @Invoker("markExistingBuildingsOccupied")
    static void universalis$markExistingBuildingsOccupied(
            VillageTerrainMap map,
            Village village) {
        throw new AssertionError("Mixin invoker was not transformed");
    }
}
