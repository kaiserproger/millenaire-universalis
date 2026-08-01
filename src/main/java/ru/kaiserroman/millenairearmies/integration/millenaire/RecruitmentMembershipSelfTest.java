package ru.kaiserroman.millenairearmies.integration.millenaire;

import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.persistence.PackedUnitMembership;

/** Standalone invariant checks for packed recruitment membership. */
public final class RecruitmentMembershipSelfTest {
    private RecruitmentMembershipSelfTest() {}

    public static void main(String[] arguments) {
        PackedArmyEcs ecs = new PackedArmyEcs(2, 4);
        PackedUnitMembership memberships = new PackedUnitMembership();
        int firstArmy = ecs.createArmy(3, 0, 0, PackedArmyEcs.packBlockPos(10, 64, 10));
        int secondArmy = ecs.createArmy(3, 0, 0, PackedArmyEcs.packBlockPos(20, 64, 20));

        int firstUnit = ecs.createUnit(firstArmy, 0, 0, PackedArmyEcs.packBlockPos(11, 64, 11));
        int secondUnit = ecs.createUnit(firstArmy, 0, 0, PackedArmyEcs.packBlockPos(12, 64, 12));
        memberships.bind(firstUnit, 101L, 201L);
        memberships.bind(secondUnit, 102L, 202L);

        check(memberships.unitHandleForUuid(101L, 201L) == firstUnit, "UUID resolves to unit");
        check(memberships.unitHandleForUuid(999L, 999L) == 0, "missing UUID resolves to zero");
        check(ecs.armyUnitCount(firstArmy) == 2, "initial packed army count");

        ecs.unitArmy(firstUnit, secondArmy);
        check(ecs.unitArmy(firstUnit) == secondArmy, "existing unit reassigned");
        check(ecs.armyUnitCount(firstArmy) == 1 && ecs.armyUnitCount(secondArmy) == 1,
                "reassignment updates both packed counts");

        check(memberships.unbindUnit(firstUnit), "unit unbound");
        check(memberships.size() == 1, "swap-remove shrinks membership");
        check(memberships.unitHandleForUuid(102L, 202L) == secondUnit,
                "swap-remove preserves moved UUID association");
        check(memberships.unbindUuid(102L, 202L), "UUID unbound");
        check(memberships.size() == 0, "all membership removed");

        memberships.bind(firstUnit, 101L, 201L);
        boolean duplicateRejected = false;
        try {
            memberships.bind(secondUnit, 101L, 201L);
        } catch (IllegalArgumentException expected) {
            duplicateRejected = true;
        }
        check(duplicateRejected, "one villager cannot back two unit rows");

        check(!RecruitmentFactionPolicy.DENY_ALL.villageBelongsToFaction(3, 7L, 8L),
                "default faction policy denies");
        RecruitmentFactionPolicy matchingVillage =
                (faction, villageMost, villageLeast) -> faction == 3 && villageMost == 7L && villageLeast == 8L;
        check(matchingVillage.villageBelongsToFaction(3, 7L, 8L),
                "projection policy receives primitive faction/village identity");
        check(!matchingVillage.villageBelongsToFaction(4, 7L, 8L), "projection mismatch denied");

        System.out.println("RecruitmentMembershipSelfTest: all checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
