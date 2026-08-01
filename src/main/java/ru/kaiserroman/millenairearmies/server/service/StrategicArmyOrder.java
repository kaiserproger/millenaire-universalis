package ru.kaiserroman.millenairearmies.server.service;

/** Stable primitive codes stored in the packed ECS. None of these performs combat or pathfinding. */
public enum StrategicArmyOrder {
    HOLD(0, false),
    MOVE(1, true),
    RALLY(2, true),
    LOGISTICS(3, true);

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
            default -> "unknown(" + code + ')';
        };
    }
}
