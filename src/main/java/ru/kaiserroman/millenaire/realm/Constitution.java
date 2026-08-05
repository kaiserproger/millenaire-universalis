package ru.kaiserroman.millenaire.realm;

/** Institutional axes are fixed-point values in the inclusive range 0..1000. */
public record Constitution(
        GovernmentForm government,
        int centralization,
        int bureaucracy,
        int noblePower,
        int merchantPower,
        int citizenPower,
        int marketFreedom,
        int landConcentration,
        int militarization,
        int legitimacy) {

    public Constitution {
        if (government == null) {
            throw new NullPointerException("government");
        }
        requireIndex(centralization, "centralization");
        requireIndex(bureaucracy, "bureaucracy");
        requireIndex(noblePower, "noblePower");
        requireIndex(merchantPower, "merchantPower");
        requireIndex(citizenPower, "citizenPower");
        requireIndex(marketFreedom, "marketFreedom");
        requireIndex(landConcentration, "landConcentration");
        requireIndex(militarization, "militarization");
        requireIndex(legitimacy, "legitimacy");
    }

    public static Constitution archetype(GovernmentForm form, int legitimacy) {
        return new Constitution(
                form,
                form.centralization(),
                form.bureaucracy(),
                form.noblePower(),
                form.merchantPower(),
                form.citizenPower(),
                form.marketFreedom(),
                form.landConcentration(),
                form.militarization(),
                legitimacy);
    }

    /** Moves every institution by at most {@code maximumStep}; government changes immediately. */
    public Constitution towards(GovernmentForm target, int maximumStep) {
        if (target == null) {
            throw new NullPointerException("target");
        }
        if (maximumStep <= 0 || maximumStep > 1000) {
            throw new IllegalArgumentException("maximumStep outside 1..1000");
        }
        return new Constitution(
                target,
                move(centralization, target.centralization(), maximumStep),
                move(bureaucracy, target.bureaucracy(), maximumStep),
                move(noblePower, target.noblePower(), maximumStep),
                move(merchantPower, target.merchantPower(), maximumStep),
                move(citizenPower, target.citizenPower(), maximumStep),
                move(marketFreedom, target.marketFreedom(), maximumStep),
                move(landConcentration, target.landConcentration(), maximumStep),
                move(militarization, target.militarization(), maximumStep),
                legitimacy);
    }

    public Constitution withLegitimacy(int value) {
        return new Constitution(
                government,
                centralization,
                bureaucracy,
                noblePower,
                merchantPower,
                citizenPower,
                marketFreedom,
                landConcentration,
                militarization,
                clamp(value));
    }

    private static int move(int value, int target, int maximumStep) {
        if (value < target) return Math.min(target, value + maximumStep);
        if (value > target) return Math.max(target, value - maximumStep);
        return value;
    }

    private static void requireIndex(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " outside 0..1000");
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(1000, value));
    }
}
