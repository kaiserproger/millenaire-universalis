package ru.kaiserroman.millenairearmies.server.diplomacy;

/** Bit flags and compact state codes derived from the persisted directed relation pair. */
public final class DiplomacyRelation {
    public static final int WAR = 1;
    public static final int ALLY = 1 << 1;
    public static final int VASSAL_OF_TARGET = 1 << 2;
    public static final int OVERLORD_OF_TARGET = 1 << 3;
    public static final int FRIENDLY = 1 << 4;

    public static final byte STATE_NEUTRAL = 0;
    public static final byte STATE_FRIENDLY = 1;
    public static final byte STATE_WAR = 2;
    public static final byte STATE_ALLY = 3;
    public static final byte STATE_VASSAL = 4;
    public static final byte STATE_OVERLORD = 5;

    public static final short MIN_REPUTATION = -1_000;
    public static final short MAX_REPUTATION = 1_000;

    private DiplomacyRelation() {}
}
