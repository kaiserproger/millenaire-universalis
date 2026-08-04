package ru.kaiserroman.millenairearmies.server.integration;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.millenaire.entity.MillVillager;
import org.millenaire.village.Village;
import org.millenaire.village.VillagerRecord;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireEntityBridge;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireVillageIndex;
import ru.kaiserroman.millenairearmies.persistence.PackedUnitMembership;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.StableDimensionTable;
import ru.kaiserroman.millenairearmies.server.service.PackedArmyControllers;

/**
 * Server-thread projection from physical Armies identities to canonical Realm subjects.
 * Player armies resolve by controller; NPC armies resolve through the home village of a member.
 */
public final class CanonicalArmyRealmIdentityResolver implements ArmyRealmIdentityResolver {
    private final PackedArmyEcs ecs;
    private final PackedArmyControllers controllers;
    private final PackedUnitMembership memberships;
    private final StableDimensionTable armyDimensions;
    private final MillenaireEntityBridge entities;
    private final MillenaireVillageIndex villages;
    private final MillenaireVillageIndex.Cursor villageCursor;
    private final RealmSavedData realms;
    private final long objectiveRadiusSquared;

    private int[] handlesBySlot = new int[16];
    private long[] realmsBySlot = new long[16];
    private int[] seenEpochs = new int[16];
    private int epoch;

    private int cachedObjectiveDimension = Integer.MIN_VALUE;
    private long cachedObjectivePosition = Long.MIN_VALUE;
    private long cachedObjectiveRealm;
    private long cachedObjectiveSettlement;

    private long reconciliationCount;
    private int unresolvedArmyCount;

    public CanonicalArmyRealmIdentityResolver(
            PackedArmyEcs ecs,
            PackedArmyControllers controllers,
            PackedUnitMembership memberships,
            StableDimensionTable armyDimensions,
            MillenaireEntityBridge entities,
            MillenaireVillageIndex villages,
            RealmSavedData realms,
            int objectiveRadiusBlocks) {
        if (ecs == null || controllers == null || memberships == null || armyDimensions == null
                || entities == null || villages == null || realms == null) {
            throw new NullPointerException("canonical Realm identity dependency");
        }
        if (objectiveRadiusBlocks <= 0 || objectiveRadiusBlocks > 30_000_000) {
            throw new IllegalArgumentException("Invalid Realm objective radius");
        }
        this.ecs = ecs;
        this.controllers = controllers;
        this.memberships = memberships;
        this.armyDimensions = armyDimensions;
        this.entities = entities;
        this.villages = villages;
        this.realms = realms;
        villageCursor = villages.newCursor();
        objectiveRadiusSquared = (long) objectiveRadiusBlocks * objectiveRadiusBlocks;
    }

    /** Rebuilds the packed army projection after membership, village or Realm reconciliation. */
    public int reconcile() {
        nextEpoch();
        unresolvedArmyCount = 0;
        int changes = 0;
        PackedArmyEcs.ArmyCursor cursor = ecs.newArmyCursor();
        for (cursor.reset(); cursor.advance(); ) {
            int army = cursor.handle();
            int slot = PackedArmyEcs.handleSlotIndex(army);
            ensureSlotCapacity(slot + 1);
            long oldRealm = handlesBySlot[slot] == army ? realmsBySlot[slot] : Long.MIN_VALUE;
            long realm = resolveArmy(army);
            handlesBySlot[slot] = army;
            realmsBySlot[slot] = realm;
            seenEpochs[slot] = epoch;
            if (realm == 0L) unresolvedArmyCount++;
            if (oldRealm != realm) changes++;
        }
        invalidateObjectiveCache();
        reconciliationCount++;
        return changes;
    }

    @Override
    public long realmForArmy(int armyHandle) {
        if (!ecs.isArmyAlive(armyHandle)) return 0L;
        int slot = PackedArmyEcs.handleSlotIndex(armyHandle);
        return slot < handlesBySlot.length
                        && seenEpochs[slot] == epoch
                        && handlesBySlot[slot] == armyHandle
                ? realmsBySlot[slot]
                : resolveArmy(armyHandle);
    }

    @Override
    public long realmAtObjective(int dimensionId, long packedPosition) {
        resolveObjective(dimensionId, packedPosition);
        return cachedObjectiveRealm;
    }

    @Override
    public long settlementAtObjective(int dimensionId, long packedPosition) {
        resolveObjective(dimensionId, packedPosition);
        return cachedObjectiveSettlement;
    }

    @Override
    public boolean isCapital(long realmId, long settlementId) {
        return realmId > 0L
                && settlementId > 0L
                && realms.registry().capitalMemberId(realmId) == settlementId;
    }

    public long reconciliationCount() { return reconciliationCount; }
    public int unresolvedArmyCount() { return unresolvedArmyCount; }

    private long resolveArmy(int armyHandle) {
        if (controllers.hasController(armyHandle)) {
            UUID controller = new UUID(
                    controllers.uuidMost(armyHandle),
                    controllers.uuidLeast(armyHandle));
            long realm = realms.realmForPlayer(controller);
            if (realm != 0L) return realm;
        }
        for (int row = 0; row < memberships.size(); row++) {
            int unit = memberships.unitHandleAt(row);
            if (!ecs.isUnitAlive(unit) || ecs.unitArmy(unit) != armyHandle) continue;
            long most = memberships.uuidMostAt(row);
            long least = memberships.uuidLeastAt(row);
            Village village = loadedVillage(most, least);
            if (village == null) village = indexedVillage(most, least);
            if (village == null || village.getId() == null || village.getId().uuid() == null) continue;
            long realm = realms.realmForSettlement(village.getId().uuid());
            if (realm != 0L) return realm;
        }
        return 0L;
    }

    private Village loadedVillage(long most, long least) {
        MillVillager entity = entities.findLoaded(most, least);
        return entity == null ? null : entities.villageFor(entity);
    }

    private Village indexedVillage(long most, long least) {
        for (villageCursor.reset(); villageCursor.advance(); ) {
            Village village = villageCursor.village();
            if (village == null || village.getVillagerRecords() == null) continue;
            for (VillagerRecord record : village.getVillagerRecords().values()) {
                if (record == null || record.getUuid() == null) continue;
                UUID uuid = record.getUuid();
                if (uuid.getMostSignificantBits() == most && uuid.getLeastSignificantBits() == least) {
                    return village;
                }
            }
        }
        return null;
    }

    private void resolveObjective(int dimensionId, long packedPosition) {
        if (dimensionId == cachedObjectiveDimension && packedPosition == cachedObjectivePosition) return;
        cachedObjectiveDimension = dimensionId;
        cachedObjectivePosition = packedPosition;
        cachedObjectiveRealm = 0L;
        cachedObjectiveSettlement = 0L;
        if (dimensionId < 0 || dimensionId >= armyDimensions.size()) return;
        ResourceLocation dimension = armyDimensions.name(dimensionId);
        int x = PackedArmyEcs.unpackBlockX(packedPosition);
        int z = PackedArmyEcs.unpackBlockZ(packedPosition);
        long bestDistance = Long.MAX_VALUE;
        for (villageCursor.reset(); villageCursor.advance(); ) {
            if (!dimension.equals(villageCursor.level().dimension().location())) continue;
            Village village = villageCursor.village();
            if (village == null || village.getId() == null || village.getId().uuid() == null
                    || village.getCenter() == null) {
                continue;
            }
            long dx = (long) village.getCenter().getX() - x;
            long dz = (long) village.getCenter().getZ() - z;
            long distance = dx * dx + dz * dz;
            if (distance > objectiveRadiusSquared || distance >= bestDistance) continue;
            long settlement = realms.keys().findSettlement(village.getId().uuid());
            if (settlement == 0L) continue;
            long realm = realms.registry().realmOfMember(settlement);
            if (realm == 0L) continue;
            bestDistance = distance;
            cachedObjectiveSettlement = settlement;
            cachedObjectiveRealm = realm;
        }
    }

    private void invalidateObjectiveCache() {
        cachedObjectiveDimension = Integer.MIN_VALUE;
        cachedObjectivePosition = Long.MIN_VALUE;
        cachedObjectiveRealm = 0L;
        cachedObjectiveSettlement = 0L;
    }

    private void nextEpoch() {
        epoch++;
        if (epoch == 0) {
            java.util.Arrays.fill(seenEpochs, 0);
            epoch = 1;
        }
    }

    private void ensureSlotCapacity(int required) {
        if (required <= handlesBySlot.length) return;
        int capacity = Math.max(required, handlesBySlot.length + Math.max(1, handlesBySlot.length >>> 1));
        handlesBySlot = java.util.Arrays.copyOf(handlesBySlot, capacity);
        realmsBySlot = java.util.Arrays.copyOf(realmsBySlot, capacity);
        seenEpochs = java.util.Arrays.copyOf(seenEpochs, capacity);
    }
}
