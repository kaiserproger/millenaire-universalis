package ru.kaiserroman.millenairearmies.client.state;

@FunctionalInterface
public interface ClientStateListener {
    ClientStateListener NOOP = state -> {};

    void stateChanged(ClientArmyState state);
}
