package ru.kaiserroman.millenaire.simulation;

/**
 * A pure strategic event. The Millenaire adapter may approve a founding/abandonment candidate only
 * after checking terrain, loaded chunks, protected areas and the concrete village API.
 */
public record SimulationEvent(
        SimulationEventType type,
        long settlementId,
        long sourceSettlementId,
        int cultureKey,
        long realmId,
        long regionKey,
        int score,
        int reasonMask,
        long cycle) {

    public SimulationEvent {
        if (type == null) throw new NullPointerException("type");
        if (settlementId <= 0L || sourceSettlementId < 0L || cultureKey < 0
                || realmId < 0L || score < 0 || cycle < 0L) {
            throw new IllegalArgumentException("Invalid simulation event");
        }
    }
}
