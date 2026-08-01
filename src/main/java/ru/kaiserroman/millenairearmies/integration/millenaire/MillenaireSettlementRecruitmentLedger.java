package ru.kaiserroman.millenairearmies.integration.millenaire;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingInventory;
import org.millenaire.village.Village;
import ru.kaiserroman.millenairearmies.ArmiesConfig;

/** Charges emeralds from the loaded Millenaire town-hall inventory through its public API. */
public final class MillenaireSettlementRecruitmentLedger implements SettlementRecruitmentLedger {
    @Override
    public int debit(ServerLevel level, Village village, int armyCount, int unitCount) {
        if (level == null || village == null || armyCount < 0 || unitCount < 0) {
            return UNAVAILABLE;
        }
        long requested = (long) armyCount * ArmiesConfig.ARMY_FORMATION_EMERALD_COST
                + (long) unitCount * ArmiesConfig.UNIT_RECRUITMENT_EMERALD_COST;
        if (requested > Integer.MAX_VALUE) {
            return INSUFFICIENT_RESOURCES;
        }
        int amount = (int) requested;
        if (amount == 0) {
            return 0;
        }

        BuildingInventory inventory = townHallInventory(level, village);
        if (inventory == null) {
            return UNAVAILABLE;
        }
        inventory.invalidateCache();
        if (inventory.getCount(level, Items.EMERALD) < amount) {
            return INSUFFICIENT_RESOURCES;
        }
        int removed = inventory.remove(level, Items.EMERALD, amount);
        if (removed == amount) {
            village.markDirty();
            return amount;
        }
        if (removed > 0) {
            inventory.add(level, Items.EMERALD, removed);
            village.markDirty();
        }
        return UNAVAILABLE;
    }

    @Override
    public boolean refund(ServerLevel level, Village village, int amount) {
        if (amount <= 0) {
            return amount == 0;
        }
        BuildingInventory inventory = townHallInventory(level, village);
        if (inventory == null) {
            return false;
        }
        boolean restored = inventory.add(level, Items.EMERALD, amount) == amount;
        inventory.invalidateCache();
        village.markDirty();
        return restored;
    }

    private static BuildingInventory townHallInventory(ServerLevel level, Village village) {
        BuildingInstance townHall = village.getTownhall();
        if (townHall == null || !townHall.isOperational()) {
            return null;
        }
        BuildingInventory inventory = townHall.getInventory();
        if (inventory == null || !allLoaded(level, townHall.getChestPositions())
                || !allLoaded(level, townHall.getFurnacePositions())
                || !allLoaded(level, townHall.getFirePitPositions())) {
            return null;
        }
        return inventory;
    }

    private static boolean allLoaded(ServerLevel level, List<BlockPos> positions) {
        if (positions == null) {
            return false;
        }
        for (int index = 0; index < positions.size(); index++) {
            BlockPos position = positions.get(index);
            if (position == null || !level.isLoaded(position)) {
                return false;
            }
        }
        return true;
    }
}
