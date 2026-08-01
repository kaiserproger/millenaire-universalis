package ru.kaiserroman.millenairearmies.integration.millenaire;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import org.millenaire.entity.MillVillager;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillagerRecord;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.persistence.PackedUnitMembership;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandAuthority;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandService;

/**
 * Server-authoritative membership layer for existing Millenaire villagers.
 *
 * <p>Recruitment creates only a packed ECS row and a persistent UUID association. It never creates
 * or replaces an entity, changes Millenaire goals, selects a target, runs pathfinding, or mutates
 * combat state. Loaded villagers keep their normal {@link MillVillager} implementation; unloaded
 * villagers are validated through Millenaire's public {@link VillagerRecord} API.</p>
 *
 * <p>This service has no tick method. Successful assignment/reassignment is allocation-free after
 * the ECS and membership columns have enough capacity. The explicit record path may allocate only
 * where Millenaire's public UUID-keyed map requires a caller-provided {@link UUID}.</p>
 */
public final class MillenaireRecruitmentService {
    public static final long NOT_RUNNING = -20L;
    public static final long PERMISSION_DENIED = -21L;
    public static final long ARMY_NOT_FOUND = -22L;
    public static final long VILLAGER_NOT_LOADED = -23L;
    public static final long VILLAGE_NOT_FOUND = -24L;
    public static final long VILLAGER_NOT_IN_VILLAGE = -25L;
    public static final long WRONG_FACTION = -26L;
    public static final long VILLAGER_UNAVAILABLE = -27L;
    public static final long UNIT_LIMIT_REACHED = -28L;

    private static final int MAX_UNIT_ROWS = 1 << 20;
    private static final int INITIAL_UNIT_STATE = 0;

    private final MillenaireVillageIndex villageIndex;
    private final MillenaireEntityBridge entityBridge;
    private final ArmyCommandService commandService;

    private MinecraftServer server;
    private PackedArmyEcs ecs;
    private PackedUnitMembership memberships;
    private ArmyCommandService.DirtyMarker dirtyMarker;
    private RecruitmentFactionPolicy factionPolicy = RecruitmentFactionPolicy.DENY_ALL;

    public MillenaireRecruitmentService(
            MillenaireVillageIndex villageIndex,
            MillenaireEntityBridge entityBridge,
            ArmyCommandService commandService) {
        this.villageIndex = Objects.requireNonNull(villageIndex, "villageIndex");
        this.entityBridge = Objects.requireNonNull(entityBridge, "entityBridge");
        this.commandService = Objects.requireNonNull(commandService, "commandService");
    }

    /** Attaches the persisted stores. The faction policy is installed through a separate hook. */
    public boolean start(
            MinecraftServer startingServer,
            PackedArmyEcs persistedEcs,
            PackedUnitMembership persistedMemberships,
            ArmyCommandService.DirtyMarker persistedDirtyMarker) {
        Objects.requireNonNull(startingServer, "startingServer");
        if (server == startingServer) {
            return false;
        }
        if (server != null) {
            throw new IllegalStateException("Recruitment service is already attached to another server");
        }
        Objects.requireNonNull(persistedEcs, "persistedEcs");
        Objects.requireNonNull(persistedMemberships, "persistedMemberships");
        Objects.requireNonNull(persistedDirtyMarker, "persistedDirtyMarker");
        if (!commandService.isRunning() || commandService.ecs() != persistedEcs) {
            throw new IllegalStateException(
                    "Army command service must own the same persisted ECS before recruitment starts");
        }
        server = startingServer;
        ecs = persistedEcs;
        memberships = persistedMemberships;
        dirtyMarker = persistedDirtyMarker;
        return true;
    }

    /** Detaches runtime references without clearing persisted membership. */
    public void stop(MinecraftServer stoppingServer) {
        if (server != stoppingServer) {
            return;
        }
        server = null;
        ecs = null;
        memberships = null;
        dirtyMarker = null;
        factionPolicy = RecruitmentFactionPolicy.DENY_ALL;
    }

    /**
     * Exact integration hook for the faction projection. Safe default is {@link
     * RecruitmentFactionPolicy#DENY_ALL}; operators are still able to administer membership.
     */
    public void installFactionPolicy(RecruitmentFactionPolicy installedPolicy) {
        Objects.requireNonNull(installedPolicy, "installedPolicy");
        if (server != null && !server.isSameThread()) {
            throw new IllegalStateException("Faction policy must be installed on the server thread");
        }
        factionPolicy = installedPolicy;
    }

    /** Assigns an already loaded Millenaire entity, addressed without retaining it. */
    public long recruitLoaded(
            ArmyCommandAuthority authority, int armyHandle, long villagerUuidMost, long villagerUuidLeast) {
        if (!prepare(authority, armyHandle)) {
            return preparationFailure(authority, armyHandle);
        }

        MillVillager villager = entityBridge.findLoaded(villagerUuidMost, villagerUuidLeast);
        if (villager == null || villager.isRemoved()) {
            return VILLAGER_NOT_LOADED;
        }
        Village village = entityBridge.villageFor(villager);
        if (village == null || village.getId() == null || village.getId().uuid() == null) {
            return VILLAGE_NOT_FOUND;
        }

        VillageId entityVillageId = villager.getVillageId();
        if (entityVillageId == null
                || entityVillageId.uuid() == null
                || entityVillageId.uuid().getMostSignificantBits()
                        != village.getId().uuid().getMostSignificantBits()
                || entityVillageId.uuid().getLeastSignificantBits()
                        != village.getId().uuid().getLeastSignificantBits()) {
            return VILLAGER_NOT_IN_VILLAGE;
        }

        UUID villagerUuid = villager.getUUID();
        VillagerRecord record = village.getVillagerRecord(villagerUuid);
        if (record == null
                || record.getUuid() == null
                || record.getUuid().getMostSignificantBits() != villagerUuidMost
                || record.getUuid().getLeastSignificantBits() != villagerUuidLeast) {
            return VILLAGER_NOT_IN_VILLAGE;
        }
        if (record.isKilled() || villager.isChild()) {
            return VILLAGER_UNAVAILABLE;
        }

        long villageMost = village.getId().uuid().getMostSignificantBits();
        long villageLeast = village.getId().uuid().getLeastSignificantBits();
        if (!factionAllowed(authority, armyHandle, villageMost, villageLeast)) {
            return WRONG_FACTION;
        }
        return assignVerified(
                authority, armyHandle, villagerUuidMost, villagerUuidLeast, villager.blockPosition().asLong());
    }

    /**
     * Assigns an existing but possibly unloaded Millenaire record. No chunk or entity is loaded.
     * The caller supplies the existing UUID object used by Millenaire's public UUID-keyed record
     * map; the primitive village id is resolved only from the already reconciled index.
     */
    public long recruitRecord(
            ArmyCommandAuthority authority,
            int armyHandle,
            long villageUuidMost,
            long villageUuidLeast,
            UUID villagerUuid) {
        Objects.requireNonNull(villagerUuid, "villagerUuid");
        if (!prepare(authority, armyHandle)) {
            return preparationFailure(authority, armyHandle);
        }

        Village village = villageIndex.find(villageUuidMost, villageUuidLeast);
        if (village == null) {
            return VILLAGE_NOT_FOUND;
        }
        VillagerRecord record = village.getVillagerRecord(villagerUuid);
        if (record == null || record.getUuid() == null) {
            return VILLAGER_NOT_IN_VILLAGE;
        }
        if (record.isKilled() || record.getChildSize() < MillVillager.MAX_CHILD_SIZE) {
            return VILLAGER_UNAVAILABLE;
        }
        if (!factionAllowed(authority, armyHandle, villageUuidMost, villageUuidLeast)) {
            return WRONG_FACTION;
        }

        BlockPos lastKnownPos = record.getLastKnownPos();
        long packedPosition = lastKnownPos == null ? village.getCenter().asLong() : lastKnownPos.asLong();
        return assignVerified(
                authority,
                armyHandle,
                villagerUuid.getMostSignificantBits(),
                villagerUuid.getLeastSignificantBits(),
                packedPosition);
    }

    public int membershipCount() {
        return memberships == null ? 0 : memberships.size();
    }

    private boolean prepare(ArmyCommandAuthority authority, int armyHandle) {
        if (server == null || !commandService.isRunning()) {
            return false;
        }
        requireServerThread();
        return authority != null && ecs.isArmyAlive(armyHandle) && commandService.canControl(authority, armyHandle);
    }

    private long preparationFailure(ArmyCommandAuthority authority, int armyHandle) {
        if (server == null || !commandService.isRunning()) {
            return NOT_RUNNING;
        }
        if (!ecs.isArmyAlive(armyHandle)) {
            return ARMY_NOT_FOUND;
        }
        return PERMISSION_DENIED;
    }

    private boolean factionAllowed(
            ArmyCommandAuthority authority, int armyHandle, long villageUuidMost, long villageUuidLeast) {
        return authority.operator()
                || factionPolicy.villageBelongsToFaction(
                        ecs.armyFaction(armyHandle), villageUuidMost, villageUuidLeast);
    }

    private long assignVerified(
            ArmyCommandAuthority authority,
            int targetArmyHandle,
            long villagerUuidMost,
            long villagerUuidLeast,
            long packedPosition) {
        int targetOrder = ecs.armyOrder(targetArmyHandle);
        int unitHandle = memberships.unitHandleForUuid(villagerUuidMost, villagerUuidLeast);
        if (unitHandle != 0 && !ecs.isUnitAlive(unitHandle)) {
            memberships.unbindUnit(unitHandle);
            dirtyMarker.markDirty();
            unitHandle = 0;
        }

        if (unitHandle == 0) {
            if (ecs.unitSize() >= MAX_UNIT_ROWS) {
                return UNIT_LIMIT_REACHED;
            }
            unitHandle = ecs.createUnit(targetArmyHandle, targetOrder, INITIAL_UNIT_STATE, packedPosition);
            memberships.bind(unitHandle, villagerUuidMost, villagerUuidLeast);
            dirtyMarker.markDirty();
            return Integer.toUnsignedLong(unitHandle);
        }

        int oldArmyHandle = ecs.unitArmy(unitHandle);
        if (oldArmyHandle != PackedArmyEcs.NO_ARMY
                && oldArmyHandle != targetArmyHandle
                && !commandService.canControl(authority, oldArmyHandle)) {
            return PERMISSION_DENIED;
        }

        boolean changed = oldArmyHandle != targetArmyHandle;
        changed |= ecs.unitPackedPos(unitHandle) != packedPosition;
        changed |= ecs.unitOrder(unitHandle) != targetOrder;
        if (changed) {
            ecs.unitArmy(unitHandle, targetArmyHandle);
            ecs.unitOrder(unitHandle, targetOrder);
            ecs.unitPackedPos(unitHandle, packedPosition);
            dirtyMarker.markDirty();
        }
        return Integer.toUnsignedLong(unitHandle);
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Recruitment must be scheduled on the Minecraft server thread");
        }
    }
}
