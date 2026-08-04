package ru.kaiserroman.millenairearmies.integration.millenaire;

import com.mojang.logging.LogUtils;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingPlan;
import org.millenaire.building.SpecialPoint;
import org.millenaire.commerce.ShopProfile;
import org.millenaire.commerce.ShopProfileLoader;
import org.millenaire.commerce.TradeGood;
import org.millenaire.commerce.TradeGoodsLoader;
import org.millenaire.commerce.TradeMenu;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillageType;
import org.millenaire.culture.VillagerType;
import org.millenaire.entity.MillVillager;
import org.millenaire.goal.impl.SellerGoal;
import org.millenaire.hire.HiringHelper;
import org.millenaire.item.SummoningWandItem;
import org.millenaire.village.Village;
import org.slf4j.Logger;
import ru.kaiserroman.millenaire.simulation.SettlementStatus;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;

/** Opens the normal Millenaire trade menu with a per-village Simulation-adjusted catalog. */
public final class MillenaireDynamicTradeService {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final SimulationSavedData simulation;
    private final SimulationTradePricePolicy prices;

    private long interceptedCount;
    private long openedCount;
    private long noProjectionCount;
    private long unchangedCatalogCount;
    private long failureCount;
    private long adjustedDirectionCount;
    private long fixedOverrideCount;
    private long unmappedGoodCount;

    public MillenaireDynamicTradeService(
            SimulationSavedData simulation,
            SimulationTradePricePolicy prices) {
        if (simulation == null || prices == null) {
            throw new NullPointerException("dynamic trade dependency");
        }
        this.simulation = simulation;
        this.prices = prices;
    }

    /** Returns true only when this service opened and owns the interaction result. */
    public boolean tryOpen(ServerPlayer player, MillVillager villager) {
        if (player == null || villager == null || !(villager.level() instanceof ServerLevel level)
                || player.getMainHandItem().getItem() instanceof SummoningWandItem) {
            return false;
        }
        VillagerType villagerType = villager.getVillagerTypeId() == null
                ? null
                : ModCultures.getVillagerType(villager.getVillagerTypeId());
        if (villagerType == null
                || !villagerType.hasTag("seller")
                || villagerType.hasTag("foreignmerchant")
                || villagerType.hasTag("chief")
                || villagerType.hasTag("localmerchant")
                || HiringHelper.isHireable(villagerType.hiringCost())
                || villager.isSleeping()
                || villager.isVillagerSleeping()
                || villager.getGoalScheduler() == null
                || !SellerGoal.ID.equals(villager.getGoalScheduler().getCurrentGoalId())) {
            return false;
        }
        Village village = villager.getVillageId() == null
                ? null
                : Village.resolve(level, villager.getVillageId());
        if (village == null
                || village.isControlledBy(player.getUUID())
                || !village.areChestsLocked()
                || village.getCombinedReputation(level, player.getUUID()) < -1024
                || village.getId() == null
                || village.getId().uuid() == null) {
            return false;
        }
        long settlementId = simulation.keys().findSettlement(village.getId().uuid());
        int simulationRow = settlementId == 0L ? -1 : simulation.state().find(settlementId);
        if (simulationRow < 0
                || simulation.state().statusAt(simulationRow) == SettlementStatus.RUINED
                || !simulation.state().physicallyPresentAt(simulationRow)) {
            noProjectionCount++;
            return false;
        }

        BuildingInstance shop = nearestShop(village, villager.blockPosition());
        if (shop == null) return false;
        BuildingPlan plan = ModCultures.getBuildingPlan(shop.getPlanId());
        if (plan == null || plan.shopId() == null) return false;
        ShopProfile profile = ShopProfileLoader.getProfile(village.getCultureId(), plan.shopId());
        List<TradeGood> source = TradeGoodsLoader.getGoods(village.getCultureId());
        if (profile == null || source.isEmpty()) return false;

        VillageType villageType = ModCultures.getVillageType(village.getVillageTypeId());
        SimulationTradePricePolicy.Adjustment adjustment;
        try {
            adjustment = prices.adjustCatalog(
                    simulation.state(), simulationRow, villageType, source);
        } catch (RuntimeException failure) {
            failureCount++;
            LOGGER.warn(
                    "Dynamic trade pricing failed open for village {}",
                    village.getId().uuid(),
                    failure);
            return false;
        }
        interceptedCount++;
        adjustedDirectionCount += adjustment.adjustedDirections();
        fixedOverrideCount += adjustment.fixedOverrides();
        unmappedGoodCount += adjustment.unmappedGoods();
        if (!adjustment.changed()) {
            unchangedCatalogCount++;
            return false;
        }

        BlockPos sellingPos = nearestSellingPosition(shop, villager.blockPosition());
        List<TradeGood> catalog = adjustment.catalog();
        TradeMenu preview = new TradeMenu(
                0,
                player.getInventory(),
                village,
                shop,
                profile,
                catalog,
                sellingPos);
        player.openMenu(
                new SimpleMenuProvider(
                        (containerId, inventory, ignored) -> new TradeMenu(
                                containerId,
                                inventory,
                                village,
                                shop,
                                profile,
                                catalog,
                                sellingPos),
                        Component.translatable("container.millenaire.trade")),
                preview::writeToBuffer);
        openedCount++;
        LOGGER.debug(
                "Dynamic TradeMenu opened: player={} village={} adjusted_directions={} fixed_overrides={} unmapped={}",
                player.getGameProfile().getName(),
                village.getId().uuid(),
                adjustment.adjustedDirections(),
                adjustment.fixedOverrides(),
                adjustment.unmappedGoods());
        return true;
    }

    public long interceptedCount() { return interceptedCount; }
    public long openedCount() { return openedCount; }
    public long noProjectionCount() { return noProjectionCount; }
    public long unchangedCatalogCount() { return unchangedCatalogCount; }
    public long failureCount() { return failureCount; }

    public void logShutdownMetrics() {
        LOGGER.info(
                "[BANNEROK_DYNAMIC_TRADE_METRICS] intercepted={} opened={} no_projection={} unchanged={} failures={} adjusted_directions={} fixed_overrides={} unmapped_goods={}",
                interceptedCount,
                openedCount,
                noProjectionCount,
                unchangedCatalogCount,
                failureCount,
                adjustedDirectionCount,
                fixedOverrideCount,
                unmappedGoodCount);
    }

    private static BuildingInstance nearestShop(Village village, BlockPos villagerPosition) {
        BuildingInstance best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BuildingInstance building : village.getBuildings()) {
            if (!building.isOperational()) continue;
            BuildingPlan plan = ModCultures.getBuildingPlan(building.getPlanId());
            if (plan == null || plan.shopId() == null) continue;
            BlockPos selling = nearestSellingPosition(building, villagerPosition);
            if (selling == null) continue;
            double distance = villagerPosition.distSqr(selling);
            if (distance < 25.0 && distance < bestDistance) {
                best = building;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static BlockPos nearestSellingPosition(
            BuildingInstance building,
            BlockPos villagerPosition) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        List<SpecialPoint> sellingPoints = building.getPointsByType("sellingPos");
        if (sellingPoints.isEmpty()) {
            return building.getFirstPointPos("sleepingPos");
        }
        for (SpecialPoint point : sellingPoints) {
            double distance = villagerPosition.distSqr(point.pos());
            if (distance < bestDistance) {
                best = point.pos();
                bestDistance = distance;
            }
        }
        return best;
    }
}
