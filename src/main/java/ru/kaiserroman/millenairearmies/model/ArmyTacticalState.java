package ru.kaiserroman.millenairearmies.model;

/** Stable tactical policy bits stored above the formation bits in the packed army state column. */
public final class ArmyTacticalState {
    public static final int SHIELD_WALL = 1 << 3;
    public static final int FIRE_AT_WILL = 1 << 4;
    public static final int KNOWN_FLAGS = SHIELD_WALL | FIRE_AT_WILL;

    private ArmyTacticalState() {}

    public static boolean shieldWall(int armyState) {
        return (armyState & SHIELD_WALL) != 0;
    }

    public static boolean fireAtWill(int armyState) {
        return (armyState & FIRE_AT_WILL) != 0;
    }

    public static int setShieldWall(int armyState, boolean enabled) {
        int updated = setFlag(armyState, SHIELD_WALL, enabled);
        return enabled ? ArmyFormation.LINE.applyToState(updated) : updated;
    }

    public static int setFireAtWill(int armyState, boolean enabled) {
        return setFlag(armyState, FIRE_AT_WILL, enabled);
    }

    public static int setFlag(int armyState, int flag, boolean enabled) {
        if ((flag & ~KNOWN_FLAGS) != 0 || Integer.bitCount(flag) != 1) {
            throw new IllegalArgumentException("Unknown tactical flag: " + flag);
        }
        return enabled ? armyState | flag : armyState & ~flag;
    }

    public static int flags(int armyState) {
        return armyState & KNOWN_FLAGS;
    }
}
