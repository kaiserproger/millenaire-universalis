package ru.kaiserroman.millenairearmies.server.execution;

import net.minecraft.server.level.ServerLevel;
import org.millenaire.entity.MillVillager;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireEntityBridge;
import ru.kaiserroman.millenairearmies.persistence.PackedUnitMembership;

/**
 * Bounded changed-only capture of loaded physical fighter positions into persistent army state.
 *
 * <p>The projection never loads a chunk and never scans the world entity list. It advances a
 * fixed membership stripe and resolves only entities already tracked by {@link
 * MillenaireEntityBridge}. Persisting the latest block position lets a restarted server load the
 * correct entity chunk instead of routing players and recovery logic to the unit's recruitment
 * position.</p>
 */
public final class LoadedUnitPositionProjection {
    private int nextMembershipRow;

    /** Returns true when at least one persisted unit position changed. */
    public boolean capture(
            PackedArmyEcs ecs,
            PackedUnitMembership memberships,
            MillenaireEntityBridge entities,
            int maxRows) {
        if (maxRows <= 0) {
            throw new IllegalArgumentException("maxRows must be positive");
        }
        int membershipCount = memberships.size();
        if (membershipCount == 0) {
            nextMembershipRow = 0;
            return false;
        }
        if (nextMembershipRow >= membershipCount) {
            nextMembershipRow = 0;
        }

        int work = Math.min(maxRows, membershipCount);
        boolean changed = false;
        for (int processed = 0; processed < work; processed++) {
            if (nextMembershipRow >= membershipCount) {
                nextMembershipRow = 0;
            }
            int row = nextMembershipRow++;
            int unitHandle = memberships.unitHandleAt(row);
            if (!ecs.isUnitAlive(unitHandle)) {
                continue;
            }
            MillVillager villager = entities.findLoaded(
                    memberships.uuidMostAt(row), memberships.uuidLeastAt(row));
            if (villager == null
                    || villager.isRemoved()
                    || !(villager.level() instanceof ServerLevel level)
                    || !level.isPositionEntityTicking(villager.blockPosition())) {
                continue;
            }
            long packedPosition = PackedArmyEcs.packBlockPos(
                    villager.getBlockX(), villager.getBlockY(), villager.getBlockZ());
            changed |= updatePosition(ecs, unitHandle, packedPosition);
        }
        return changed;
    }

    public void reset() {
        nextMembershipRow = 0;
    }

    static boolean updatePosition(PackedArmyEcs ecs, int unitHandle, long packedPosition) {
        if (ecs.unitPackedPos(unitHandle) == packedPosition) {
            return false;
        }
        ecs.unitPackedPos(unitHandle, packedPosition);
        return true;
    }
}
