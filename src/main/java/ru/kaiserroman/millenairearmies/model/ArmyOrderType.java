package ru.kaiserroman.millenairearmies.model;

/** Non-combat army orders available to the command layer. */
public enum ArmyOrderType {
    HOLD((byte) 0),
    MOVE((byte) 1),
    PATROL((byte) 2),
    FOLLOW((byte) 3),
    ESCORT((byte) 4),
    RESUPPLY((byte) 5),
    RETURN_HOME((byte) 6),
    DISBAND((byte) 7);

    private final byte code;

    ArmyOrderType(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static boolean isValidCode(byte code) {
        return code >= HOLD.code && code <= DISBAND.code;
    }

    public static ArmyOrderType fromCode(byte code) {
        return switch (code) {
            case 0 -> HOLD;
            case 1 -> MOVE;
            case 2 -> PATROL;
            case 3 -> FOLLOW;
            case 4 -> ESCORT;
            case 5 -> RESUPPLY;
            case 6 -> RETURN_HOME;
            case 7 -> DISBAND;
            default -> throw new IllegalArgumentException("Unknown army order code: " + code);
        };
    }
}
