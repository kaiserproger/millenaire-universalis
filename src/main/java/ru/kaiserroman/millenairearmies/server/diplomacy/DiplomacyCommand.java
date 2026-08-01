package ru.kaiserroman.millenairearmies.server.diplomacy;

/** Stable command codes for the allocation-free diplomacy command heap. */
public final class DiplomacyCommand {
    public static final byte SET_REPUTATION = 0;
    public static final byte ADJUST_REPUTATION = 1;
    public static final byte DECLARE_WAR = 2;
    public static final byte MAKE_PEACE = 3;
    public static final byte FORM_ALLIANCE = 4;
    public static final byte BREAK_ALLIANCE = 5;
    public static final byte BECOME_VASSAL = 6;
    public static final byte RELEASE_VASSAL = 7;
    public static final byte DRIFT_REPUTATION = 8;

    private DiplomacyCommand() {}

    public static boolean isValid(byte code) {
        return code >= SET_REPUTATION && code <= DRIFT_REPUTATION;
    }
}
