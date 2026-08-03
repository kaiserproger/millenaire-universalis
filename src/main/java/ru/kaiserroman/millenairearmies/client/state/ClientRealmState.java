package ru.kaiserroman.millenairearmies.client.state;

import ru.kaiserroman.millenairearmies.network.RealmStatePayload;

/** Immutable-by-replacement client realm snapshot. */
public final class ClientRealmState {
    public static final ClientRealmState INSTANCE = new ClientRealmState();

    private RealmStatePayload payload = empty();
    private Runnable listener = () -> {};

    public void listener(Runnable replacement) {
        listener = replacement == null ? () -> {} : replacement;
        listener.run();
    }

    public boolean apply(RealmStatePayload replacement) {
        if (replacement.realmRevision() < payload.realmRevision()
                || replacement.realmRevision() == payload.realmRevision()
                        && replacement.acknowledgementActionId() < payload.acknowledgementActionId()) {
            return false;
        }
        payload = replacement;
        listener.run();
        return true;
    }

    public void reset() {
        payload = empty();
        listener.run();
    }

    public RealmStatePayload payload() { return payload; }

    private static RealmStatePayload empty() {
        return new RealmStatePayload(
                0L,
                0,
                (byte) 0,
                0,
                false,
                (byte) 0,
                (byte) 0,
                "",
                "",
                "",
                0,
                0L,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0);
    }
}
