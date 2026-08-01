package ru.kaiserroman.millenairearmies.presentation.client;

/** Stable client presentation entry point for the future sync layer and render hooks. */
public final class ClientPresentationState {
    private static final ClientUnitPresentationIndex UNITS = new ClientUnitPresentationIndex();

    private ClientPresentationState() {}

    public static ClientPresentationCatalog catalog() {
        return ClientPresentationCatalog.INSTANCE;
    }

    public static ClientUnitPresentationIndex units() {
        return UNITS;
    }
}
