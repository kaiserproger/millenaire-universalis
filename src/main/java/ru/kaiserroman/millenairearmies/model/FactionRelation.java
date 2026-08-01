package ru.kaiserroman.millenairearmies.model;

/** Cold relation descriptor; hot systems store the same fields in primitive columns. */
public record FactionRelation(
        int sourceFactionId, int targetFactionId, byte allegianceCode, short reputation, long revision) {
    public FactionRelation {
        if (sourceFactionId < 0 || targetFactionId < 0) {
            throw new IllegalArgumentException("Faction ids must be non-negative");
        }
        if (sourceFactionId == targetFactionId) {
            throw new IllegalArgumentException("A faction relation must target another faction");
        }
        if (!FactionAllegiance.isValidCode(allegianceCode)) {
            throw new IllegalArgumentException("Unknown faction allegiance code: " + allegianceCode);
        }
        if (revision < 0) {
            throw new IllegalArgumentException("Revision must be non-negative");
        }
    }

    public FactionAllegiance allegiance() {
        return FactionAllegiance.fromCode(allegianceCode);
    }
}
