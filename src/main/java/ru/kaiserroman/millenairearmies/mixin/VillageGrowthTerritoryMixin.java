package ru.kaiserroman.millenairearmies.mixin;

import net.minecraft.server.level.ServerLevel;
import org.millenaire.building.BuildingPlanSet;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillageType;
import org.millenaire.village.ControlledQueuedProject;
import org.millenaire.village.Village;
import org.millenaire.village.VillageGrowthManager;
import org.millenaire.world.PlacedLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.kaiserroman.millenairearmies.server.settlement.PlayerSettlementTerritoryRegistry;

/** Uses the persisted per-village Universalis radius when Millenaire launches a queued project. */
@Mixin(value = VillageGrowthManager.class, remap = false)
public abstract class VillageGrowthTerritoryMixin {
    @Inject(
            method = "tryLaunchControlledQueueHead("
                    + "Lnet/minecraft/server/level/ServerLevel;"
                    + "Lorg/millenaire/village/Village;)Z",
            at = @At("HEAD"),
            cancellable = true,
            require = 1)
    private static void universalis$deferUnloadedExpandedProject(
            ServerLevel level,
            Village village,
            CallbackInfoReturnable<Boolean> callback) {
        ControlledQueuedProject project = village.controlledQueueHead();
        if (project == null || project.plannedLocation() == null) return;
        BuildingPlanSet planSet = ModCultures.getBuildingPlanSet(project.planSetId());
        VillageType villageType = ModCultures.getVillageType(village.getVillageTypeId());
        BuildingPlanSet.LevelDef levelDef = planSet == null
                ? null
                : planSet.getLevel(project.variant(), project.level());
        if (planSet == null || villageType == null || levelDef == null) return;
        int footprintRadius = Math.max(levelDef.width(), levelDef.depth())
                + planSet.clearMargins().maxMargin();
        if (!PlayerSettlementTerritoryRegistry.mayLaunchPlanned(
                level,
                village,
                project.plannedLocation().position(),
                villageType.radius(),
                footprintRadius)) {
            callback.setReturnValue(false);
        }
    }

    @Redirect(
            method = "placeNewBuildingForConstruction("
                    + "Lnet/minecraft/server/level/ServerLevel;"
                    + "Lorg/millenaire/village/Village;"
                    + "Lorg/millenaire/building/BuildingPlanSet;"
                    + "Lorg/millenaire/building/BuildingPlanSet$LevelDef;"
                    + "Ljava/lang/String;"
                    + "Lorg/millenaire/culture/VillageType$LayoutSlot;"
                    + "Lorg/millenaire/world/PlacedLocation;Z)Z",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/millenaire/culture/VillageType;radius()I",
                    remap = false),
            require = 1)
    private static int universalis$developedTerritoryRadius(
            VillageType villageType,
            ServerLevel level,
            Village village,
            BuildingPlanSet planSet,
            BuildingPlanSet.LevelDef levelDef,
            String variant,
            VillageType.LayoutSlot slot,
            PlacedLocation plannedLocation,
            boolean playerChosen) {
        return PlayerSettlementTerritoryRegistry.radius(level, village, villageType.radius());
    }
}
