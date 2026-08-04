package ru.kaiserroman.millenairearmies.server.realm;

/** Deterministic feudal loyalty, levy-size and rebellion policy. */
public final class FeudalLevyPolicy {
    public enum Response {
        ANSWER,
        REFUSE,
        REBEL
    }

    public record Decision(
            Response response,
            int loyalty,
            int separatism,
            int availableLevy,
            int reasonMask) {}

    public static final int REASON_LOW_LEGITIMACY = 1;
    public static final int REASON_HIGH_CENTRALIZATION = 1 << 1;
    public static final int REASON_POWERFUL_NOBLES = 1 << 2;
    public static final int REASON_LAND_CONCENTRATION = 1 << 3;
    public static final int REASON_MILITARIZED = 1 << 4;

    private final int refusalThreshold;
    private final int rebellionThreshold;
    private final int maximumLevy;

    public FeudalLevyPolicy(int refusalThreshold, int rebellionThreshold, int maximumLevy) {
        if (rebellionThreshold < 0 || refusalThreshold <= rebellionThreshold
                || refusalThreshold > 1_000 || maximumLevy < 1) {
            throw new IllegalArgumentException("Invalid feudal levy policy");
        }
        this.refusalThreshold = refusalThreshold;
        this.rebellionThreshold = rebellionThreshold;
        this.maximumLevy = maximumLevy;
    }

    public Decision evaluate(
            int legitimacy,
            int centralization,
            int noblePower,
            int landConcentration,
            int militarization,
            long population,
            int requestedUnits) {
        legitimacy = clamp(legitimacy);
        centralization = clamp(centralization);
        noblePower = clamp(noblePower);
        landConcentration = clamp(landConcentration);
        militarization = clamp(militarization);
        if (population < 0L || requestedUnits < 1) {
            throw new IllegalArgumentException("Population and requested levy must be valid");
        }

        int separatism = clamp(
                (1_000 - legitimacy) * 40 / 100
                        + centralization * 18 / 100
                        + noblePower * 20 / 100
                        + landConcentration * 12 / 100
                        + militarization * 10 / 100);
        int loyalty = clamp(1_000 - separatism + legitimacy / 5);
        int reasons = 0;
        if (legitimacy < 400) reasons |= REASON_LOW_LEGITIMACY;
        if (centralization > 650) reasons |= REASON_HIGH_CENTRALIZATION;
        if (noblePower > 650) reasons |= REASON_POWERFUL_NOBLES;
        if (landConcentration > 700) reasons |= REASON_LAND_CONCENTRATION;
        if (militarization > 700) reasons |= REASON_MILITARIZED;

        long demographicCap = Math.max(1L, population / 20L);
        int politicalCap = 1 + militarization * maximumLevy / 1_000;
        int available = (int) Math.min(
                Math.min((long) maximumLevy, demographicCap),
                Math.max(1, politicalCap));
        available = Math.min(available, requestedUnits);

        Response response;
        if (loyalty <= rebellionThreshold
                && legitimacy < 400
                && noblePower + landConcentration >= 1_300) {
            response = Response.REBEL;
            available = 0;
        } else if (loyalty < refusalThreshold) {
            response = Response.REFUSE;
            available = 0;
        } else {
            response = Response.ANSWER;
        }
        return new Decision(response, loyalty, separatism, available, reasons);
    }

    public int refusalThreshold() { return refusalThreshold; }
    public int rebellionThreshold() { return rebellionThreshold; }
    public int maximumLevy() { return maximumLevy; }

    private static int clamp(int value) {
        return Math.max(0, Math.min(1_000, value));
    }
}
