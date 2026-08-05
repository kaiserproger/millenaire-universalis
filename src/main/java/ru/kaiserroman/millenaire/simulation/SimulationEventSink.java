package ru.kaiserroman.millenaire.simulation;

@FunctionalInterface
public interface SimulationEventSink {
    SimulationEventSink IGNORE = event -> {};

    void accept(SimulationEvent event);
}
