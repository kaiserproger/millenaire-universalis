package ru.kaiserroman.millenairearmies.integration.millenaire;

import java.util.Objects;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.persistence.PackedUnitMembership;
import ru.kaiserroman.millenairearmies.persistence.PackedCommandState;
import ru.kaiserroman.millenairearmies.persistence.PackedLogisticsState;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandAuthority;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandService;
import ru.kaiserroman.millenairearmies.server.service.PackedArmyControllers;

/**
 * World-independent atomic owner of recruitment membership and controller mutations.
 *
 * <p>World/API eligibility is deliberately checked by {@link MillenaireRecruitmentService} first.
 * This final boundary rechecks controller, duplicate membership and capacity against the actual
 * persisted stores so two commands on the same server tick cannot double recruit a villager.</p>
 */
final class RecruitmentRoster {
    private final PackedArmyEcs ecs;
    private final PackedUnitMembership memberships;
    private final PackedArmyControllers controllers;
    private final PackedCommandState commands;
    private final PackedLogisticsState logistics;
    private final int maximumUnitsPerArmy;
    private final ArmyCommandService.DirtyMarker dirtyMarker;
    private RecruitmentUnitReleaseListener releaseListener;

    RecruitmentRoster(
            PackedArmyEcs ecs,
            PackedUnitMembership memberships,
            PackedArmyControllers controllers,
            PackedCommandState commands,
            PackedLogisticsState logistics,
            int maximumUnitsPerArmy,
            ArmyCommandService.DirtyMarker dirtyMarker,
            RecruitmentUnitReleaseListener releaseListener) {
        this.ecs = Objects.requireNonNull(ecs, "ecs");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.controllers = Objects.requireNonNull(controllers, "controllers");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.logistics = Objects.requireNonNull(logistics, "logistics");
        if (maximumUnitsPerArmy <= 0) {
            throw new IllegalArgumentException("Recruitment capacities must be positive");
        }
        this.maximumUnitsPerArmy = maximumUnitsPerArmy;
        this.dirtyMarker = Objects.requireNonNull(dirtyMarker, "dirtyMarker");
        this.releaseListener = Objects.requireNonNull(releaseListener, "releaseListener");
    }

    void releaseListener(RecruitmentUnitReleaseListener replacement) {
        releaseListener = Objects.requireNonNull(replacement, "replacement");
    }

    long recruit(
            ArmyCommandAuthority authority,
            int armyHandle,
            long villagerUuidMost,
            long villagerUuidLeast,
            long packedPosition) {
        long authorityFailure = authorityFailure(authority, armyHandle);
        if (authorityFailure != 0L) {
            return authorityFailure;
        }
        int existing = memberships.unitHandleForUuid(villagerUuidMost, villagerUuidLeast);
        if (existing != 0) {
            if (ecs.isUnitAlive(existing)) {
                return MillenaireRecruitmentService.ALREADY_RECRUITED;
            }
            memberships.unbindUnit(existing);
            dirtyMarker.markDirty();
        }
        if (ecs.armyUnitCount(armyHandle) >= maximumUnitsPerArmy) {
            return MillenaireRecruitmentService.ARMY_FULL;
        }
        int unitHandle = ecs.createUnit(
                armyHandle, ecs.armyOrder(armyHandle), 0, packedPosition);
        try {
            memberships.bind(unitHandle, villagerUuidMost, villagerUuidLeast);
        } catch (RuntimeException failure) {
            ecs.removeUnit(unitHandle);
            throw failure;
        }
        dirtyMarker.markDirty();
        return Integer.toUnsignedLong(unitHandle);
    }

    long release(
            ArmyCommandAuthority authority,
            int armyHandle,
            long villagerUuidMost,
            long villagerUuidLeast) {
        long authorityFailure = authorityFailure(authority, armyHandle);
        if (authorityFailure != 0L) {
            return authorityFailure;
        }
        int unitHandle = memberships.unitHandleForUuid(villagerUuidMost, villagerUuidLeast);
        if (unitHandle == 0 || !ecs.isUnitAlive(unitHandle) || ecs.unitArmy(unitHandle) != armyHandle) {
            return MillenaireRecruitmentService.NOT_RECRUITED;
        }
        releaseListener.releasing(unitHandle, villagerUuidMost, villagerUuidLeast);
        memberships.unbindUnit(unitHandle);
        ecs.removeUnit(unitHandle);
        dirtyMarker.markDirty();
        return 1L;
    }

    /** Returns released unit count + 1 so an empty-army disband is still a positive success. */
    long disband(ArmyCommandAuthority authority, int armyHandle) {
        long authorityFailure = authorityFailure(authority, armyHandle);
        if (authorityFailure != 0L) {
            // Idempotence for a handle which is already gone: no state is recreated or released.
            return authorityFailure == MillenaireRecruitmentService.ARMY_NOT_FOUND
                    ? 1L
                    : authorityFailure;
        }

        int released = 0;
        int row = 0;
        while (row < memberships.size()) {
            int unitHandle = memberships.unitHandleAt(row);
            if (!ecs.isUnitAlive(unitHandle)) {
                // A membership can outlive its packed row after an interrupted legacy mutation.
                // It cannot belong to any live army, so remove it while we already own the only
                // mutation boundary instead of persisting an unrecruitable UUID forever.
                memberships.unbindUnit(unitHandle);
                continue;
            }
            if (ecs.unitArmy(unitHandle) != armyHandle) {
                row++;
                continue;
            }
            long most = memberships.uuidMostAt(row);
            long least = memberships.uuidLeastAt(row);
            releaseListener.releasing(unitHandle, most, least);
            memberships.unbindUnit(unitHandle);
            ecs.removeUnit(unitHandle);
            released++;
            // Membership swap-remove puts an unvisited row at this same index.
        }

        // Legacy/anonymous rows are not valid recruited villagers, but they must not survive an
        // owned army as unassigned duplicates after disband.
        PackedArmyEcs.UnitCursor units = ecs.newUnitCursor();
        boolean removedAnonymous;
        do {
            removedAnonymous = false;
            for (units.reset(); units.advance(); ) {
                if (units.army() == armyHandle) {
                    ecs.removeUnit(units.handle());
                    released++;
                    removedAnonymous = true;
                    break;
                }
            }
        } while (removedAnonymous);

        controllers.remove(armyHandle);
        for (int commandRow = commands.size() - 1; commandRow >= 0; commandRow--) {
            if (commands.armyHandleAt(commandRow) == armyHandle) {
                commands.removeAt(commandRow);
            }
        }
        for (int logisticsRow = logistics.size() - 1; logisticsRow >= 0; logisticsRow--) {
            if (logistics.requesterArmyHandleAt(logisticsRow) == armyHandle) {
                logistics.removeAt(logisticsRow);
            }
        }
        ecs.removeArmy(armyHandle);
        dirtyMarker.markDirty();
        return (long) released + 1L;
    }

    private long authorityFailure(ArmyCommandAuthority authority, int armyHandle) {
        if (!ecs.isArmyAlive(armyHandle)) {
            return MillenaireRecruitmentService.ARMY_NOT_FOUND;
        }
        if (authority == null || !authority.hasIdentity()
                || !controllers.matches(armyHandle, authority.uuidMost(), authority.uuidLeast())) {
            return MillenaireRecruitmentService.PERMISSION_DENIED;
        }
        return 0L;
    }
}
