package ru.kaiserroman.millenairearmies.server.unit;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.millenaire.entity.MillVillager;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireEntityBridge;
import ru.kaiserroman.millenairearmies.persistence.PackedUnitMembership;

/**
 * Descriptor assignment plus an explicit, server-thread equipment projection operation.
 *
 * <p>This service intentionally has no tick hook. The owner invokes {@link #projectDirtyEquipment}
 * after recruitment, a role/loadout change, or an operator-requested datapack refresh. It never
 * changes targets, goals, navigation, attributes, or combat rules.</p>
 */
public final class UnitRoleService {
    private final PackedUnitMembership memberships;
    private final MillenaireEntityBridge entityBridge;
    private final UnitDescriptorCatalog catalog;
    private final PackedUnitRoleState state;
    private final PackedUnitMembership.Cursor membershipCursor;
    private final PackedUnitMembership.UuidBits unitUuidBits;
    private final PackedUnitRoleState.View roleView;
    private final ProjectionResult projectionResult = new ProjectionResult();
    private long observedCatalogGeneration;

    /**
     * Lifecycle integration hook: construct once after {@code ArmySavedData} and the Millenaire
     * entity bridge are available. This does not retain any entity beyond the bridge's loaded set.
     */
    public UnitRoleService(
            PackedUnitMembership memberships,
            MillenaireEntityBridge entityBridge,
            UnitDescriptorCatalog catalog,
            PackedUnitRoleState state,
            int expectedUnits) {
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.entityBridge = Objects.requireNonNull(entityBridge, "entityBridge");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.state = Objects.requireNonNull(state, "state");
        this.state.reserve(expectedUnits);
        this.membershipCursor = memberships.newCursor();
        this.unitUuidBits = memberships.newUuidBits();
        this.roleView = state.newView();
        this.observedCatalogGeneration = catalog.generation();
    }

    public PackedUnitRoleState state() { return state; }
    public UnitDescriptorCatalog catalog() { return catalog; }

    /** Cold API used by commands/recruitment; missing definitions are rejected before mutation. */
    public boolean assign(
            int unitHandle,
            ResourceLocation roleKey,
            ResourceLocation rankOverride,
            ResourceLocation loadoutOverride) {
        Objects.requireNonNull(roleKey, "roleKey");
        UnitRoleDescriptor role = catalog.role(roleKey);
        if (role == null) {
            throw new IllegalArgumentException("Unknown army role " + roleKey);
        }
        int rankToken = 0;
        if (rankOverride != null) {
            UnitRankDescriptor rank = catalog.rank(rankOverride);
            if (rank == null) {
                throw new IllegalArgumentException("Unknown army rank " + rankOverride);
            }
            rankToken = rank.token();
        }
        int loadoutToken = 0;
        if (loadoutOverride != null) {
            UnitLoadoutDescriptor loadout = catalog.loadout(loadoutOverride);
            if (loadout == null) {
                throw new IllegalArgumentException("Unknown army loadout " + loadoutOverride);
            }
            loadoutToken = loadout.token();
        }
        return state.assign(unitHandle, role.token(), rankToken, loadoutToken);
    }

    /** Allocation-free hot API for already validated tokens. */
    public boolean assignTokens(int unitHandle, int roleToken, int rankToken, int loadoutToken) {
        return state.assign(unitHandle, roleToken, rankToken, loadoutToken);
    }

    /** Persists the economic/military class without requiring a datapack role descriptor. */
    public boolean assignTroopClass(int unitHandle, byte troopClass) {
        return state.assignTroopClass(unitHandle, troopClass);
    }

    /** Returns whether this unit already has a role-state row. */
    public boolean hasAssignment(int unitHandle) {
        return state.read(unitHandle, roleView);
    }

    /** Allocation-free hot API to mutate only the loadout token while preserving role/rank. */
    public boolean assignLoadoutOnly(int unitHandle, int loadoutToken) {
        return state.assignLoadoutOnly(unitHandle, loadoutToken);
    }

    public boolean remove(int unitHandle) {
        return state.remove(unitHandle);
    }

    /**
     * Explicit cold operation. The returned result object is reused by this service; consume it
     * before calling this method again.
     *
     * @param scanLimit maximum membership rows inspected during this call
     */
    public ProjectionResult projectDirtyEquipment(MinecraftServer server, int scanLimit) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) {
            throw new IllegalStateException("Army equipment projection must run on the server thread");
        }
        if (scanLimit < 0) {
            throw new IllegalArgumentException("Scan limit must be non-negative");
        }
        projectionResult.reset();

        long catalogGeneration = catalog.generation();
        if (catalogGeneration != observedCatalogGeneration) {
            state.markAllEquipmentDirty();
            observedCatalogGeneration = catalogGeneration;
            projectionResult.catalogRefresh = true;
        }

        PackedUnitMembership.Cursor cursor = membershipCursor.reset();
        while (projectionResult.scanned < scanLimit && cursor.advance()) {
            projectionResult.scanned++;
            int unitHandle = cursor.unitHandle();
            if (!state.read(unitHandle, roleView)) {
                projectionResult.unassigned++;
                continue;
            }
            if ((roleView.flags() & PackedUnitRoleState.FLAG_EQUIPMENT_DIRTY) == 0) {
                projectionResult.clean++;
                continue;
            }
            MillVillager villager = entityBridge.findLoaded(cursor.uuidMost(), cursor.uuidLeast());
            if (villager == null || villager.isRemoved()) {
                projectionResult.unloaded++;
                continue;
            }

            UnitRoleDescriptor role = roleView.roleToken() == 0 ? null : catalog.role(roleView.roleToken());
            if (roleView.roleToken() != 0 && role == null) {
                projectionResult.missingDescriptors++;
                continue;
            }
            int loadoutToken = roleView.loadoutToken();
            if (loadoutToken == 0 && role != null) {
                loadoutToken = role.defaultLoadoutToken();
            }
            if (loadoutToken == 0) {
                state.markEquipmentProjected(unitHandle);
                projectionResult.projected++;
                continue;
            }
            UnitLoadoutDescriptor loadout = catalog.loadout(loadoutToken);
            if (loadout == null) {
                projectionResult.missingDescriptors++;
                continue;
            }

            projectLoadout(villager, loadout, projectionResult);
            state.markEquipmentProjected(unitHandle);
            projectionResult.projected++;
        }
        return projectionResult;
    }

    public ProjectionResult projectUnitLoadout(MinecraftServer server, int unitHandle) {
        Objects.requireNonNull(server, "server");
        if (!server.isSameThread()) {
            throw new IllegalStateException("Army equipment projection must run on the server thread");
        }
        projectionResult.reset();
        projectionResult.scanned = 1;
        if (!state.read(unitHandle, roleView)) {
            projectionResult.unassigned++;
            return projectionResult;
        }
        if ((roleView.flags() & PackedUnitRoleState.FLAG_EQUIPMENT_DIRTY) == 0) {
            projectionResult.clean++;
            return projectionResult;
        }
        if (!memberships.read(unitHandle, unitUuidBits)) {
            projectionResult.unassigned++;
            return projectionResult;
        }
        MillVillager villager = entityBridge.findLoaded(unitUuidBits.most(), unitUuidBits.least());
        if (villager == null || villager.isRemoved()) {
            projectionResult.unloaded++;
            return projectionResult;
        }

        UnitRoleDescriptor role = roleView.roleToken() == 0 ? null : catalog.role(roleView.roleToken());
        if (roleView.roleToken() != 0 && role == null) {
            projectionResult.missingDescriptors++;
            return projectionResult;
        }
        int loadoutToken = roleView.loadoutToken();
        if (loadoutToken == 0 && role != null) {
            loadoutToken = role.defaultLoadoutToken();
        }
        UnitLoadoutDescriptor loadout = loadoutToken == 0 ? null : catalog.loadout(loadoutToken);
        if (loadout == null && loadoutToken != 0) {
            projectionResult.missingDescriptors++;
            return projectionResult;
        }
        if (loadout == null) {
            state.markEquipmentProjected(unitHandle);
            projectionResult.projected++;
            return projectionResult;
        }
        projectLoadout(villager, loadout, projectionResult);
        state.markEquipmentProjected(unitHandle);
        projectionResult.projected++;
        return projectionResult;
    }

    private static void projectLoadout(
            MillVillager villager,
            UnitLoadoutDescriptor loadout,
            ProjectionResult result) {
        for (int slotIndex = 0; slotIndex < UnitLoadoutDescriptor.SLOT_COUNT; slotIndex++) {
            Item target = loadout.resolvedItem(slotIndex);
            if (target == null) {
                if (loadout.candidates(slotIndex).length == 0) {
                    EquipmentSlot slot = UnitLoadoutDescriptor.slot(slotIndex);
                    ItemStack current = villager.getItemBySlot(slot);
                    if (!current.isEmpty()) {
                        villager.setItemSlot(slot, ItemStack.EMPTY);
                        result.changedSlots++;
                    }
                } else {
                    result.unresolvedSlots++;
                }
                continue;
            }
            EquipmentSlot slot = UnitLoadoutDescriptor.slot(slotIndex);
            ItemStack current = villager.getItemBySlot(slot);
            if (current.is(target)) {
                result.unchangedSlots++;
                continue;
            }
            // The only per-unit allocation is an ItemStack for a slot that really changes.
            villager.setItemSlot(slot, new ItemStack(target));
            result.changedSlots++;
        }
    }

    public static final class ProjectionResult {
        private int scanned;
        private int projected;
        private int clean;
        private int unassigned;
        private int unloaded;
        private int missingDescriptors;
        private int changedSlots;
        private int unchangedSlots;
        private int unresolvedSlots;
        private boolean catalogRefresh;

        private ProjectionResult() {}

        private void reset() {
            scanned = 0;
            projected = 0;
            clean = 0;
            unassigned = 0;
            unloaded = 0;
            missingDescriptors = 0;
            changedSlots = 0;
            unchangedSlots = 0;
            unresolvedSlots = 0;
            catalogRefresh = false;
        }

        public int scanned() { return scanned; }
        public int projected() { return projected; }
        public int clean() { return clean; }
        public int unassigned() { return unassigned; }
        public int unloaded() { return unloaded; }
        public int missingDescriptors() { return missingDescriptors; }
        public int changedSlots() { return changedSlots; }
        public int unchangedSlots() { return unchangedSlots; }
        public int unresolvedSlots() { return unresolvedSlots; }
        public boolean catalogRefresh() { return catalogRefresh; }
    }
}
