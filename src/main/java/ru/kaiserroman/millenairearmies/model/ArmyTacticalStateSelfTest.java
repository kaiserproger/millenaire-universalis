package ru.kaiserroman.millenairearmies.model;

/** Verifies stable formation/tactical bit composition. */
public final class ArmyTacticalStateSelfTest {
    private ArmyTacticalStateSelfTest() {}

    public static void main(String[] args) {
        int state = ArmyFormation.WEDGE.applyToState(0);
        state = ArmyTacticalState.setFireAtWill(state, true);
        check(ArmyFormation.fromState(state) == ArmyFormation.WEDGE, "fire keeps formation");
        check(ArmyTacticalState.fireAtWill(state), "fire enabled");
        state = ArmyTacticalState.setShieldWall(state, true);
        check(ArmyFormation.fromState(state) == ArmyFormation.LINE, "shield wall forces line");
        check(ArmyTacticalState.shieldWall(state) && ArmyTacticalState.fireAtWill(state), "flags compose");
        state = ArmyTacticalState.setShieldWall(state, false);
        check(!ArmyTacticalState.shieldWall(state) && ArmyTacticalState.fireAtWill(state), "independent clear");
        try {
            ArmyTacticalState.setFlag(state, 1 << 20, true);
            throw new AssertionError("unknown flag accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("ArmyTacticalStateSelfTest passed");
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
