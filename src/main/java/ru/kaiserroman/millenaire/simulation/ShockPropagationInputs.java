package ru.kaiserroman.millenaire.simulation;

/** Adapter-supplied route and containment inputs for deterministic shock spread. */
public record ShockPropagationInputs(
        int contactIntensity,
        int distancePenalty,
        int borderControl,
        int elapsedCycles) {

    public ShockPropagationInputs {
        requireIndex(contactIntensity, "contactIntensity");
        requireIndex(distancePenalty, "distancePenalty");
        requireIndex(borderControl, "borderControl");
        if (elapsedCycles < 0) throw new IllegalArgumentException("Negative elapsedCycles");
    }

    private static void requireIndex(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " outside 0..1000");
        }
    }
}
