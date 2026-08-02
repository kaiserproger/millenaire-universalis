package ru.kaiserroman.millenairearmies.model;

/** Stable formation codes stored in the low bits of the packed army state column. */
public enum ArmyFormation {
    LINE(0),
    COLUMN(1),
    WEDGE(2),
    SQUARE(3),
    SKIRMISH(4);

    public static final int STATE_MASK = 0x7;

    private final int code;

    ArmyFormation(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public int applyToState(int armyState) {
        return armyState & ~STATE_MASK | code;
    }

    public static boolean isValidCode(int code) {
        return code >= LINE.code && code <= SKIRMISH.code;
    }

    public static ArmyFormation fromCode(int code) {
        return switch (code) {
            case 0 -> LINE;
            case 1 -> COLUMN;
            case 2 -> WEDGE;
            case 3 -> SQUARE;
            case 4 -> SKIRMISH;
            default -> throw new IllegalArgumentException("Unknown army formation code: " + code);
        };
    }

    public static ArmyFormation fromState(int armyState) {
        int code = armyState & STATE_MASK;
        return isValidCode(code) ? fromCode(code) : LINE;
    }

    public static String displayName(int code) {
        return switch (code) {
            case 0 -> "line";
            case 1 -> "column";
            case 2 -> "wedge";
            case 3 -> "square";
            case 4 -> "skirmish";
            default -> "unknown(" + code + ')';
        };
    }
}
