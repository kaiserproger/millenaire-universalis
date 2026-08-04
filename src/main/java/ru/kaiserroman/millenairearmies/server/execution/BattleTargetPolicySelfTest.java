package ru.kaiserroman.millenairearmies.server.execution;

import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;

/** Ensures only live hostile army members can be selected by strategic combat. */
public final class BattleTargetPolicySelfTest {
    private BattleTargetPolicySelfTest() {}

    public static void main(String[] args) {
        check(BattleTargetPolicy.valid(101, 202, true, true),
                "live hostile army member accepted");
        check(!BattleTargetPolicy.valid(101, PackedArmyEcs.NO_ARMY, true, true),
                "civilian without army membership rejected");
        check(!BattleTargetPolicy.valid(PackedArmyEcs.NO_ARMY, 202, true, true),
                "unregistered source rejected");
        check(!BattleTargetPolicy.valid(101, 101, true, true),
                "same-army friendly rejected");
        check(!BattleTargetPolicy.valid(101, 202, false, true),
                "dead target army rejected");
        check(!BattleTargetPolicy.valid(101, 202, true, false),
                "non-hostile army rejected");
        System.out.println("BattleTargetPolicySelfTest: OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
