package ru.kaiserroman.millenairearmies.client;

import java.util.Objects;

/** Client-only hand-off point between the network snapshot owner and the strategic UI. */
public final class ArmyClientState {
    private static volatile ArmyClientMirror current = ArmyClientMirror.EMPTY;

    private ArmyClientState() {
    }

    public static ArmyClientMirror current() {
        return current;
    }

    /** Called by the client network layer after publishing a complete snapshot. */
    public static void install(ArmyClientMirror mirror) {
        current = Objects.requireNonNull(mirror, "mirror");
    }

    /** Called on disconnect so a stale world can never bleed into the next connection. */
    public static void clear() {
        current = ArmyClientMirror.EMPTY;
    }
}
