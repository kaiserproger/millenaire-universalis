package ru.kaiserroman.millenairearmies.model;

/** Compact faction-to-faction posture. Persist {@link #code()}, not enum ordinals. */
public enum FactionAllegiance {
    HOSTILE((byte) 0),
    NEUTRAL((byte) 1),
    FRIENDLY((byte) 2),
    ALLIED((byte) 3),
    VASSAL((byte) 4);

    private final byte code;

    FactionAllegiance(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static boolean isValidCode(byte code) {
        return code >= HOSTILE.code && code <= VASSAL.code;
    }

    public static FactionAllegiance fromCode(byte code) {
        return switch (code) {
            case 0 -> HOSTILE;
            case 1 -> NEUTRAL;
            case 2 -> FRIENDLY;
            case 3 -> ALLIED;
            case 4 -> VASSAL;
            default -> throw new IllegalArgumentException("Unknown faction allegiance code: " + code);
        };
    }
}
