package ru.kaiserroman.millenairearmies.server.service;

/** Stable primitive codes stored in the packed ECS. */
public enum StrategicArmyOrder {
    HOLD(0, false),
    MOVE(1, true),
    RALLY(2, true),
    LOGISTICS(3, true),
    /** Physical entity combat at the selected settlement or field position. */
    ATTACK(4, true),
    /** Persistent defensive service around a real Millenaire settlement muster post. */
    GARRISON(5, true),
    /** Physical perimeter assault; strategic siege ownership remains Simulation-owned. */
    SIEGE(6, true),
    /** Retained escort of the army controller's current loaded player entity. */
    FOLLOW(7, false),
    /** Persistent defensive service around an arbitrary selected field position. */
    GUARD(8, true);

    private final int code;
    private final boolean requiresTarget;

    StrategicArmyOrder(int code, boolean requiresTarget) {
        this.code = code;
        this.requiresTarget = requiresTarget;
    }

    public int code() {
        return code;
    }

    public boolean requiresTarget() {
        return requiresTarget;
    }

    public static String displayName(int code) {
        return switch (code) {
            case 0 -> "hold";
            case 1 -> "move";
            case 2 -> "rally";
            case 3 -> "logistics";
            case 4 -> "attack";
            case 5 -> "garrison";
            case 6 -> "siege";
            case 7 -> "follow";
            case 8 -> "guard";
            default -> "unknown(" + code + ')';
        };
    }
}
