package ru.kaiserroman.millenaire.realm;

/** Result of one constitutional evaluation; {@code reasonMask} is stable for persistence/UI use. */
public record EvolutionDecision(
        GovernmentForm current,
        GovernmentForm proposed,
        int pressure,
        int requiredPressure,
        int reasonMask,
        boolean changesGovernment) {

    public EvolutionDecision {
        if (current == null || proposed == null) {
            throw new NullPointerException("government");
        }
    }
}
