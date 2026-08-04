package ru.kaiserroman.millenairearmies.server.supply;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.millenaire.entity.MillVillager;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireEntityBridge;
import ru.kaiserroman.millenairearmies.persistence.ArmySavedData;
import ru.kaiserroman.millenairearmies.persistence.PackedArmySupplyState;
import ru.kaiserroman.millenairearmies.persistence.PackedUnitMembership;
import ru.kaiserroman.millenairearmies.persistence.StableDimensionTable;

/** Bounded physical projection from one selected container to loaded members of its army. */
public final class ArmySupplyChestService implements ArmySupplyAccess {
    private static final int UNITS_PER_TICK = 16;
    private static final double ACCESS_RANGE_SQ = 64.0D * 64.0D;

    private MinecraftServer server;
    private PackedArmyEcs ecs;
    private PackedUnitMembership memberships;
    private PackedArmySupplyState supplies;
    private StableDimensionTable dimensions;
    private MillenaireEntityBridge entities;
    private int nextMembershipRow;

    public void start(
            MinecraftServer server,
            ArmySavedData data,
            MillenaireEntityBridge entities) {
        this.server = Objects.requireNonNull(server, "server");
        this.ecs = Objects.requireNonNull(data, "data").ecs();
        this.memberships = data.memberships();
        this.supplies = data.armySupplies();
        this.dimensions = data.dimensions();
        this.entities = Objects.requireNonNull(entities, "entities");
        this.nextMembershipRow = 0;
    }

    public void stop(MinecraftServer stoppingServer) {
        if (server != stoppingServer) return;
        server = null;
        ecs = null;
        memberships = null;
        supplies = null;
        dimensions = null;
        entities = null;
        nextMembershipRow = 0;
    }

    public void tick(MinecraftServer tickingServer) {
        if (server != tickingServer || memberships == null || memberships.size() == 0) return;
        if (!tickingServer.isSameThread()) {
            throw new IllegalStateException("Army supply projection escaped the server thread");
        }
        int count = memberships.size();
        if (nextMembershipRow >= count) nextMembershipRow = 0;
        int work = Math.min(UNITS_PER_TICK, count);
        for (int processed = 0; processed < work; processed++) {
            if (nextMembershipRow >= count) nextMembershipRow = 0;
            int row = nextMembershipRow++;
            int unitHandle = memberships.unitHandleAt(row);
            if (!ecs.isUnitAlive(unitHandle)) continue;
            int armyHandle = ecs.unitArmy(unitHandle);
            if (armyHandle == PackedArmyEcs.NO_ARMY || supplies.findArmy(armyHandle) < 0) continue;
            MillVillager villager = entities.findLoaded(
                    memberships.uuidMostAt(row), memberships.uuidLeastAt(row));
            if (villager == null || villager.isRemoved() || villager.getHealth() >= villager.getMaxHealth()) continue;
            Container container = container(armyHandle, villager);
            if (container != null && consumeFood(container, villager)) container.setChanged();
        }
    }

    @Override
    public boolean hasAssignment(int armyHandle) {
        return supplies != null && supplies.findArmy(armyHandle) >= 0;
    }

    @Override
    public boolean hasArrow(int armyHandle, MillVillager unit) {
        if (!hasAssignment(armyHandle)) return true;
        Container container = container(armyHandle, unit);
        if (container == null) return false;
        return arrowSlot(container) >= 0;
    }

    @Override
    public boolean consumeArrow(int armyHandle, MillVillager unit) {
        if (!hasAssignment(armyHandle)) return true;
        Container container = container(armyHandle, unit);
        if (container == null) return false;
        int slot = arrowSlot(container);
        if (slot < 0) return false;
        container.removeItem(slot, 1);
        container.setChanged();
        return true;
    }

    private static int arrowSlot(Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(Items.ARROW) && !stack.isEmpty()) return slot;
        }
        return -1;
    }

    private Container container(int armyHandle, MillVillager unit) {
        int row = supplies.findArmy(armyHandle);
        if (row < 0 || !(unit.level() instanceof ServerLevel level)
                || !dimensions.matches(supplies.dimensionIdAt(row), level.dimension().location())) {
            return null;
        }
        BlockPos pos = BlockPos.of(supplies.chestPositionAt(row));
        if (!level.hasChunkAt(pos) || unit.distanceToSqr(
                pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > ACCESS_RANGE_SQ) {
            return null;
        }
        return level.getBlockEntity(pos) instanceof Container container ? container : null;
    }

    private static boolean consumeFood(Container container, MillVillager villager) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (stack.isEmpty() || food == null) continue;
            container.removeItem(slot, 1);
            villager.heal(Math.max(1.0F, food.nutrition() * 0.5F));
            return true;
        }
        return false;
    }
}
