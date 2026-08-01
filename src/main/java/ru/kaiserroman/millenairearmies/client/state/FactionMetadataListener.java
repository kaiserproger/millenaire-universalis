package ru.kaiserroman.millenairearmies.client.state;

@FunctionalInterface
public interface FactionMetadataListener {
    FactionMetadataListener NOOP = state -> {};

    void metadataChanged(ClientFactionMetadataState state);
}
