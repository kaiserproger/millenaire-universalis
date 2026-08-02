package ru.kaiserroman.millenairearmies.server.execution;

import com.mojang.logging.LogUtils;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import org.millenaire.entity.MillVillager;
import org.millenaire.village.Village;
import org.millenaire.village.VillageSavedData;
import org.slf4j.Logger;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireEntityBridge;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireVillageIndex;
import ru.kaiserroman.millenairearmies.persistence.PackedUnitMembership;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandService;
import ru.kaiserroman.millenairearmies.server.service.PackedArmyControllers;

/**
 * Server-thread battlefield index over real loaded Millenaire entities.
 *
 * <p>No hit points, casualties or capture outcome are simulated here. Tasks select actual
 * {@link MillVillager} targets and invoke Millenaire's combat API. Damage/death counters are fed by
 * NeoForge events after Minecraft has applied real damage. A village changes owner only when an
 * attacker is physically at its centre and no hostile loaded combatant remains on the field.</p>
 */
public final class PhysicalBattleCoordinator {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_ENTITY_SCAN = 512;
    private static final double ACQUIRE_DISTANCE_SQ = 72.0D * 72.0D;
    private static final double TARGET_AREA_DISTANCE_SQ = 112.0D * 112.0D;
    private static final double VILLAGE_MATCH_DISTANCE_SQ = 96.0D * 96.0D;
    private static final double CAPTURE_DISTANCE_SQ = 12.0D * 12.0D;

    private final MinecraftServer server;
    private final PackedArmyEcs ecs;
    private final PackedUnitMembership memberships;
    private final PackedArmyControllers controllers;
    private final MillenaireEntityBridge entities;
    private final MillenaireVillageIndex villages;
    private final MillenaireVillageIndex.Cursor villageCursor;
    private final ArmyCommandService.DirtyMarker dirtyMarker;
    private final RealmCapturePolicy capturePolicy;

    private long targetAcquisitions;
    private long attackActions;
    private long damageEvents;
    private long damageMilliHearts;
    private long deaths;
    private long captures;

    public PhysicalBattleCoordinator(
            MinecraftServer server,
            PackedArmyEcs ecs,
            PackedUnitMembership memberships,
            PackedArmyControllers controllers,
            MillenaireEntityBridge entities,
            MillenaireVillageIndex villages,
            ArmyCommandService.DirtyMarker dirtyMarker) {
        this(server, ecs, memberships, controllers, entities, villages, dirtyMarker, RealmCapturePolicy.ALLOW_ALL);
    }

    public PhysicalBattleCoordinator(
            MinecraftServer server,
            PackedArmyEcs ecs,
            PackedUnitMembership memberships,
            PackedArmyControllers controllers,
            MillenaireEntityBridge entities,
            MillenaireVillageIndex villages,
            ArmyCommandService.DirtyMarker dirtyMarker,
            RealmCapturePolicy capturePolicy) {
        this.server = server;
        this.ecs = ecs;
        this.memberships = memberships;
        this.controllers = controllers;
        this.entities = entities;
        this.villages = villages;
        this.villageCursor = villages.newCursor();
        this.dirtyMarker = dirtyMarker;
        this.capturePolicy = capturePolicy;
    }

    public MillVillager acquireEnemy(
            MillVillager attacker, int attackerArmy, int unitHandle, long packedTarget) {
        LivingEntity current = attacker.getAttackTarget();
        if (current instanceof MillVillager villager
                && isHostile(attacker, attackerArmy, villager, packedTarget)) {
            return villager;
        }

        int count = entities.size();
        if (count == 0) return null;
        int start = Math.floorMod(PackedArmyEcs.handleSlotIndex(unitHandle), count);
        int budget = Math.min(count, MAX_ENTITY_SCAN);
        MillVillager best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int inspected = 0; inspected < budget; inspected++) {
            int row = start + inspected;
            if (row >= count) row -= count;
            MillVillager candidate = entities.loadedVillagerAt(row);
            if (!isHostile(attacker, attackerArmy, candidate, packedTarget)) continue;
            double distance = attacker.distanceToSqr(candidate);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        if (best != null) {
            Village attackedVillage = targetVillage(
                    (ServerLevel) attacker.level(), attackerArmy, packedTarget);
            if (attackedVillage != null && !attackedVillage.isUnderAttack()) {
                attackedVillage.setUnderAttack(true);
                attackedVillage.markDirty();
                VillageSavedData.get((ServerLevel) attacker.level()).setDirty();
            }
            targetAcquisitions++;
            LOGGER.debug(
                    "[BANNEROK_PHYSICAL_BATTLE] target_acquired army={} unit={} attacker={} target={} distance_sq={}",
                    Integer.toUnsignedString(attackerArmy),
                    Integer.toUnsignedString(unitHandle),
                    attacker.getUUID(),
                    best.getUUID(),
                    bestDistance);
        }
        return best;
    }

    public CaptureResult tryCapture(
            MillVillager attacker, int attackerArmy, long packedTarget, ServerLevel level) {
        Village targetVillage = targetVillage(level, attackerArmy, packedTarget);
        if (targetVillage == null) {
            return distanceSquared(attacker, packedTarget) <= CAPTURE_DISTANCE_SQ
                    ? CaptureResult.FIELD_CLEARED
                    : CaptureResult.ACTIVE;
        }
        if (!controllers.hasController(attackerArmy)) return CaptureResult.BLOCKED;
        UUID owner = new UUID(controllers.uuidMost(attackerArmy), controllers.uuidLeast(attackerArmy));
        if (targetVillage.isControlledBy(owner)) return CaptureResult.CAPTURED;
        if (!capturePolicy.canCapture(owner)) return CaptureResult.BLOCKED;
        if (attacker.distanceToSqr(
                        targetVillage.getCenter().getX() + 0.5D,
                        targetVillage.getCenter().getY(),
                        targetVillage.getCenter().getZ() + 0.5D)
                > CAPTURE_DISTANCE_SQ) {
            return CaptureResult.ACTIVE;
        }
        if (hasHostileAtVillage(attackerArmy, targetVillage, level, packedTarget)) {
            targetVillage.setUnderAttack(true);
            targetVillage.markDirty();
            return CaptureResult.ACTIVE;
        }

        String ownerName = owner.toString().substring(0, 8);
        ServerPlayer online = server.getPlayerList().getPlayer(owner);
        if (online != null) ownerName = online.getGameProfile().getName();
        UUID previousOwner = targetVillage.getOwnerUUID();
        targetVillage.setOwner(owner, ownerName);
        targetVillage.setUnderAttack(false);
        targetVillage.markDirty();
        VillageSavedData.get(level).setDirty();
        capturePolicy.captured(owner, targetVillage);
        captures++;
        LOGGER.info(
                "[BANNEROK_PHYSICAL_BATTLE] village_captured army={} village={} previous_owner={} new_owner={} deaths={} damage_events={}",
                Integer.toUnsignedString(attackerArmy),
                targetVillage.getId().uuid(),
                previousOwner,
                owner,
                deaths,
                damageEvents);
        return CaptureResult.CAPTURED;
    }

    public void attackPerformed(int armyHandle, int unitHandle, MillVillager target) {
        attackActions++;
        LOGGER.debug(
                "[BANNEROK_PHYSICAL_BATTLE] attack_action army={} unit={} target={} target_health={}",
                Integer.toUnsignedString(armyHandle),
                Integer.toUnsignedString(unitHandle),
                target.getUUID(),
                target.getHealth());
    }

    /** Called after Minecraft has applied real damage. */
    public void damaged(MillVillager victim, LivingEntity source, float healthDamage) {
        if (healthDamage <= 0.0F || !(source instanceof MillVillager attacker)) return;
        int attackerArmy = armyHandle(attacker);
        int victimArmy = armyHandle(victim);
        if (attackerArmy == PackedArmyEcs.NO_ARMY && victimArmy == PackedArmyEcs.NO_ARMY) return;
        damageEvents++;
        damageMilliHearts += Math.max(1L, Math.round(healthDamage * 1_000.0F));
        LOGGER.debug(
                "[BANNEROK_PHYSICAL_BATTLE] real_damage attacker_army={} victim_army={} attacker={} victim={} damage={} health_after={}",
                Integer.toUnsignedString(attackerArmy),
                Integer.toUnsignedString(victimArmy),
                attacker.getUUID(),
                victim.getUUID(),
                healthDamage,
                victim.getHealth());
    }

    /** Removes a dead physical army member from the persistent roster. */
    public boolean died(MillVillager villager) {
        int unit = memberships.unitHandleForUuid(
                villager.getUUID().getMostSignificantBits(), villager.getUUID().getLeastSignificantBits());
        if (unit == 0) return false;
        int army = ecs.isUnitAlive(unit) ? ecs.unitArmy(unit) : PackedArmyEcs.NO_ARMY;
        memberships.unbindUnit(unit);
        if (ecs.isUnitAlive(unit)) ecs.removeUnit(unit);
        dirtyMarker.markDirty();
        deaths++;
        LOGGER.info(
                "[BANNEROK_PHYSICAL_BATTLE] physical_death army={} unit={} villager={} total_deaths={}",
                Integer.toUnsignedString(army),
                Integer.toUnsignedString(unit),
                villager.getUUID(),
                deaths);
        return true;
    }

    public int armyHandle(MillVillager villager) {
        int unit = memberships.unitHandleForUuid(
                villager.getUUID().getMostSignificantBits(), villager.getUUID().getLeastSignificantBits());
        return unit != 0 && ecs.isUnitAlive(unit) ? ecs.unitArmy(unit) : PackedArmyEcs.NO_ARMY;
    }

    public long targetAcquisitions() { return targetAcquisitions; }
    public long attackActions() { return attackActions; }
    public long damageEvents() { return damageEvents; }
    public long damageMilliHearts() { return damageMilliHearts; }
    public long deaths() { return deaths; }
    public long captures() { return captures; }

    private boolean isHostile(
            MillVillager attacker, int attackerArmy, MillVillager candidate, long packedTarget) {
        if (candidate == null
                || candidate == attacker
                || candidate.isRemoved()
                || !candidate.isAlive()
                || candidate.isChild()
                || candidate.level() != attacker.level()) {
            return false;
        }
        double attackerDistance = attacker.distanceToSqr(candidate);
        if (attackerDistance > ACQUIRE_DISTANCE_SQ
                || distanceSquared(candidate, packedTarget) > TARGET_AREA_DISTANCE_SQ) {
            return false;
        }
        int candidateArmy = armyHandle(candidate);
        if (candidateArmy != PackedArmyEcs.NO_ARMY) {
            return candidateArmy != attackerArmy
                    && ecs.isArmyAlive(candidateArmy)
                    && !controllers.sameController(attackerArmy, candidateArmy);
        }
        if (!(attacker.level() instanceof ServerLevel level)) return false;
        Village targetVillage = targetVillage(level, attackerArmy, packedTarget);
        Village candidateVillage = entities.villageFor(candidate);
        if (targetVillage == null || candidateVillage != targetVillage || candidate.getAttackStrength() <= 0) {
            return false;
        }
        if (!controllers.hasController(attackerArmy)) return true;
        UUID owner = new UUID(controllers.uuidMost(attackerArmy), controllers.uuidLeast(attackerArmy));
        return !targetVillage.isControlledBy(owner);
    }

    private boolean hasHostileAtVillage(
            int attackerArmy, Village targetVillage, ServerLevel level, long packedTarget) {
        int count = entities.size();
        for (int row = 0; row < count; row++) {
            MillVillager candidate = entities.loadedVillagerAt(row);
            if (candidate == null
                    || candidate.isRemoved()
                    || !candidate.isAlive()
                    || candidate.isChild()
                    || candidate.level() != level
                    || distanceSquared(candidate, packedTarget) > TARGET_AREA_DISTANCE_SQ) {
                continue;
            }
            int candidateArmy = armyHandle(candidate);
            if (candidateArmy != PackedArmyEcs.NO_ARMY) {
                if (candidateArmy != attackerArmy
                        && ecs.isArmyAlive(candidateArmy)
                        && !controllers.sameController(attackerArmy, candidateArmy)) {
                    return true;
                }
            } else if (entities.loadedVillageAt(row) == targetVillage
                    && candidate.getAttackStrength() > 0) {
                return true;
            }
        }
        return false;
    }

    private Village targetVillage(ServerLevel level, int armyHandle, long packedTarget) {
        long persistedMost = ecs.armyTargetVillageMost(armyHandle);
        long persistedLeast = ecs.armyTargetVillageLeast(armyHandle);
        if ((persistedMost | persistedLeast) != 0L) {
            Village persisted = villages.find(persistedMost, persistedLeast);
            if (persisted != null && villages.level(persisted.getId()) == level) {
                return persisted;
            }
            ecs.clearArmyTargetVillage(armyHandle);
            dirtyMarker.markDirty();
        }
        Village best = null;
        long bestDistance = Long.MAX_VALUE;
        int x = PackedArmyEcs.unpackBlockX(packedTarget);
        int y = PackedArmyEcs.unpackBlockY(packedTarget);
        int z = PackedArmyEcs.unpackBlockZ(packedTarget);
        for (villageCursor.reset(); villageCursor.advance(); ) {
            if (villageCursor.level() != level) continue;
            Village candidate = villageCursor.village();
            long dx = (long) candidate.getCenter().getX() - x;
            long dy = (long) candidate.getCenter().getY() - y;
            long dz = (long) candidate.getCenter().getZ() - z;
            long distance = dx * dx + dy * dy + dz * dz;
            if (distance <= (long) VILLAGE_MATCH_DISTANCE_SQ && distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        if (best != null) {
            UUID id = best.getId().uuid();
            ecs.armyTargetVillage(armyHandle, id.getMostSignificantBits(), id.getLeastSignificantBits());
            dirtyMarker.markDirty();
        }
        return best;
    }

    private static double distanceSquared(MillVillager villager, long packedTarget) {
        double dx = villager.getX() - (PackedArmyEcs.unpackBlockX(packedTarget) + 0.5D);
        double dy = villager.getY() - PackedArmyEcs.unpackBlockY(packedTarget);
        double dz = villager.getZ() - (PackedArmyEcs.unpackBlockZ(packedTarget) + 0.5D);
        return dx * dx + dy * dy + dz * dz;
    }

    public enum CaptureResult {
        ACTIVE,
        FIELD_CLEARED,
        CAPTURED,
        BLOCKED
    }
}
