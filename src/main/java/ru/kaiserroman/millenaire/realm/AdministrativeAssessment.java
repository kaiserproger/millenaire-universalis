package ru.kaiserroman.millenaire.realm;

/**
 * Deterministic administrative health snapshot. All indices are fixed-point values in 0..1000;
 * legitimacyDelta is the bounded per-evaluation change recommended by the policy.
 */
public record AdministrativeAssessment(
        int capacity,
        int load,
        int coverage,
        int corruption,
        int taxEfficiency,
        int separatismPressure,
        int legitimacyDelta,
        int reasonMask) {

    public AdministrativeAssessment {
        requireIndex(capacity, "capacity");
        requireIndex(load, "load");
        requireIndex(coverage, "coverage");
        requireIndex(corruption, "corruption");
        requireIndex(taxEfficiency, "taxEfficiency");
        requireIndex(separatismPressure, "separatismPressure");
        if (legitimacyDelta < -100 || legitimacyDelta > 100) {
            throw new IllegalArgumentException("legitimacyDelta outside -100..100");
        }
        if (reasonMask < 0) throw new IllegalArgumentException("Negative reasonMask");
    }

    public boolean overextended() {
        return load > capacity || coverage < 600;
    }

    public boolean secessionRisk() {
        return separatismPressure >= 550;
    }

    /** Applies the assessment without allowing legitimacy outside the constitutional range. */
    public Constitution applyLegitimacy(Constitution constitution) {
        if (constitution == null) throw new NullPointerException("constitution");
        return constitution.withLegitimacy(constitution.legitimacy() + legitimacyDelta);
    }

    /** Returns collectible revenue after administrative leakage, without overflowing. */
    public long collectibleRevenue(long nominalRevenue) {
        if (nominalRevenue < 0L) throw new IllegalArgumentException("Negative nominal revenue");
        long whole = nominalRevenue / 1000L;
        long remainder = nominalRevenue % 1000L;
        return whole * taxEfficiency + remainder * taxEfficiency / 1000L;
    }

    private static void requireIndex(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " outside 0..1000");
        }
    }
}
