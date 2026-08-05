package ru.kaiserroman.millenaire.realm;

/** Deterministic provincial secession assessment. */
public record RealmSecessionDecision(
        int pressure,
        int breakawayCapacity,
        int reasonMask,
        boolean secedes,
        boolean formsBreakawayState) {
    public RealmSecessionDecision {
        requireIndex(pressure, "pressure");
        requireIndex(breakawayCapacity, "breakawayCapacity");
        if (formsBreakawayState && !secedes) {
            throw new IllegalArgumentException("Breakaway state requires secession");
        }
    }

    private static void requireIndex(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " outside 0..1000");
        }
    }
}
