package ru.kaiserroman.millenairearmies.integration.millenaire;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingPlan;
import org.millenaire.building.BuildingPlanSet;
import org.millenaire.building.ClearMargins;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillageType;
import org.millenaire.village.Village;
import org.millenaire.world.BuildingLocationFinder;
import org.millenaire.world.PlacedLocation;
import org.millenaire.world.PlacementConstraints;
import org.millenaire.world.TerrainReachability;
import org.millenaire.world.VillageTerrainMap;
import ru.kaiserroman.millenairearmies.mixin.VillageGrowthManagerAccessor;
import ru.kaiserroman.millenairearmies.server.settlement.PlayerSettlementTerritoryRegistry;

/** Runs Millenaire's placement algorithm with a persisted per-village developed territory radius. */
public final class UniversalisGrowthBridge {
    private UniversalisGrowthBridge() {}

    public static PlacementSession begin(
            ServerLevel level,
            Village village,
            int territoryRadius) {
        if (level == null || village == null || village.getCenter() == null || territoryRadius <= 0) {
            throw new IllegalArgumentException("Invalid Universalis placement context");
        }
        VillageType type = ModCultures.getVillageType(village.getVillageTypeId());
        int minimumRadius = type == null ? Math.min(90, territoryRadius) : Math.max(1, type.radius());
        int radius = PlayerSettlementTerritoryRegistry.loadedRadius(
                level,
                village.getCenter(),
                minimumRadius,
                territoryRadius);
        if (radius == 0) return null;
        VillageTerrainMap map = VillageTerrainMap.compute(level, village.getCenter(), radius);
        VillageGrowthManagerAccessor.universalis$markExistingBuildingsOccupied(map, village);
        TerrainReachability reachability = TerrainReachability.compute(map, village.getCenter());
        return new PlacementSession(
                village.getCenter(),
                radius,
                map,
                reachability,
                new ArrayList<>(village.getBuildings()));
    }

    public static PlacedLocation findLocationForNewBuilding(
            ServerLevel level,
            Village village,
            BuildingPlanSet planSet,
            BuildingPlan plan,
            VillageType.LayoutSlot slot,
            int territoryRadius) {
        PlacementSession placement = begin(level, village, territoryRadius);
        return placement == null ? null : placement.findLocation(planSet, plan, slot);
    }

    public static BuildingLocationFinder.AnchorEvaluation validateLocationAt(
            ServerLevel level,
            Village village,
            BuildingPlanSet planSet,
            BuildingPlan plan,
            VillageType.LayoutSlot slot,
            BlockPos position,
            Integer rotation,
            int territoryRadius) {
        PlacementSession placement = begin(level, village, territoryRadius);
        return placement == null
                ? null
                : placement.validateLocation(planSet, plan, slot, position, rotation);
    }

    public static final class PlacementSession {
        private final BlockPos center;
        private final int radius;
        private final VillageTerrainMap map;
        private final TerrainReachability reachability;
        private final List<BuildingInstance> buildings;

        private PlacementSession(
                BlockPos center,
                int radius,
                VillageTerrainMap map,
                TerrainReachability reachability,
                List<BuildingInstance> buildings) {
            this.center = center;
            this.radius = radius;
            this.map = map;
            this.reachability = reachability;
            this.buildings = buildings;
        }

        public PlacedLocation findLocation(
                BuildingPlanSet planSet,
                BuildingPlan plan,
                VillageType.LayoutSlot slot) {
            Constraints constraints = constraints(planSet, slot);
            return BuildingLocationFinder.findLocation(
                    map,
                    plan,
                    center,
                    constraints.placement,
                    constraints.clearMargins,
                    buildings,
                    reachability);
        }

        public BuildingLocationFinder.AnchorEvaluation validateLocation(
                BuildingPlanSet planSet,
                BuildingPlan plan,
                VillageType.LayoutSlot slot,
                BlockPos position,
                Integer rotation) {
            if (position == null) throw new NullPointerException("position");
            Constraints constraints = constraints(planSet, slot);
            return BuildingLocationFinder.evaluateAnchor(
                    position.getX(),
                    position.getZ(),
                    map,
                    plan,
                    center,
                    constraints.placement,
                    constraints.clearMargins,
                    buildings,
                    reachability,
                    rotation);
        }

        private Constraints constraints(
                BuildingPlanSet planSet,
                VillageType.LayoutSlot slot) {
            if (planSet == null || slot == null) {
                throw new IllegalArgumentException("Missing building placement constraints");
            }
            PlacementConstraints placement = PlacementConstraints.resolve(planSet, slot, radius);
            ClearMargins clearMargins = planSet.clearMargins().atLeast(placement.clearMargin());
            return new Constraints(placement, clearMargins);
        }
    }

    private record Constraints(
            PlacementConstraints placement,
            ClearMargins clearMargins) {}
}
