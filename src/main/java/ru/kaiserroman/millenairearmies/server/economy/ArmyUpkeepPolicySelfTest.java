package ru.kaiserroman.millenairearmies.server.economy;

import ru.kaiserroman.millenairearmies.server.unit.PackedUnitRoleState;

/** Deterministic tariff, class differentiation and unpaid consequence checks. */
public final class ArmyUpkeepPolicySelfTest {
    private ArmyUpkeepPolicySelfTest() {}

    public static void main(String[] args) {
        ArmyUpkeepPolicy policy = new ArmyUpkeepPolicy(1, 5, 12, 2, 3);
        check(policy.unitCost(PackedUnitRoleState.TROOP_CLASS_LEVY) == 1, "levy tariff");
        check(policy.unitCost(PackedUnitRoleState.TROOP_CLASS_REGULAR) == 5,
                "regulars cost materially more than levies");
        check(policy.unitCost(PackedUnitRoleState.TROOP_CLASS_NOBLE) == 12,
                "nobles cost more than regulars");
        check(policy.totalCost(10, 3, 1) == 37, "mixed army cost");
        check(policy.consequence(true, 99) == ArmyUpkeepPolicy.Consequence.PAID,
                "payment clears consequence");
        check(policy.consequence(false, 1) == ArmyUpkeepPolicy.Consequence.WARNING,
                "first missed payment warns");
        check(policy.consequence(false, 2) == ArmyUpkeepPolicy.Consequence.DEMOBILIZE,
                "second missed payment demobilizes");
        check(policy.consequence(false, 3) == ArmyUpkeepPolicy.Consequence.DESERTION,
                "third missed payment deserts");
        System.out.println("ArmyUpkeepPolicySelfTest: OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
