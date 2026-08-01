package ru.kaiserroman.millenairearmies.server.diplomacy;

/** Authenticated server-side faction context. A negative faction id is valid only for operators. */
public record DiplomacyAuthority(int factionId, boolean operator) {
    public DiplomacyAuthority {
        if (factionId < 0 && !operator) {
            throw new IllegalArgumentException("A non-operator diplomacy authority needs a faction");
        }
    }
}
