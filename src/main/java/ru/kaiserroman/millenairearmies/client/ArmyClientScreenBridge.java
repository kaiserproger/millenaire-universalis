package ru.kaiserroman.millenairearmies.client;

import java.util.Objects;

/**
 * Dist-neutral signal used by common payload registration. The physical client installs the GUI
 * callback; a dedicated server never resolves Minecraft client or Screen classes through it.
 */
public final class ArmyClientScreenBridge {
    private static final Runnable NOOP = () -> {};
    private static volatile Runnable opener = NOOP;

    private ArmyClientScreenBridge() {}

    public static void install(Runnable replacement) {
        opener = Objects.requireNonNull(replacement, "replacement");
    }

    public static void clear() {
        opener = NOOP;
    }

    public static void open() {
        opener.run();
    }
}
