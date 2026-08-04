package ru.kaiserroman.millenairearmies.server.economy;

import ru.kaiserroman.millenairearmies.server.unit.PackedUnitRoleState;

/** Pure deterministic raising/upkeep tariff and non-payment consequence policy. */
public final class ArmyUpkeepPolicy {
    public enum Consequence {
        PAID,
        WARNING,
        DEMOBILIZE,
        DESERTION
    }

    private final int levyUpkeep;
    private final int regularUpkeep;
    private final int nobleUpkeep;
    private final int demobilizeAfterMissedCycles;
    private final int desertAfterMissedCycles;

    public ArmyUpkeepPolicy(
            int levyUpkeep,
            int regularUpkeep,
            int nobleUpkeep,
            int demobilizeAfterMissedCycles,
            int desertAfterMissedCycles) {
        if (levyUpkeep < 0 || regularUpkeep <= levyUpkeep || nobleUpkeep <= regularUpkeep
                || demobilizeAfterMissedCycles < 1
                || desertAfterMissedCycles <= demobilizeAfterMissedCycles) {
            throw new IllegalArgumentException("Invalid army upkeep policy");
        }
        this.levyUpkeep = levyUpkeep;
        this.regularUpkeep = regularUpkeep;
        this.nobleUpkeep = nobleUpkeep;
        this.demobilizeAfterMissedCycles = demobilizeAfterMissedCycles;
        this.desertAfterMissedCycles = desertAfterMissedCycles;
    }

    public int unitCost(byte troopClass) {
        return switch (troopClass) {
            case PackedUnitRoleState.TROOP_CLASS_REGULAR -> regularUpkeep;
            case PackedUnitRoleState.TROOP_CLASS_NOBLE -> nobleUpkeep;
            case PackedUnitRoleState.TROOP_CLASS_UNCLASSIFIED,
                    PackedUnitRoleState.TROOP_CLASS_LEVY -> levyUpkeep;
            default -> throw new IllegalArgumentException("Unknown troop class " + troopClass);
        };
    }

    public int totalCost(int levies, int regulars, int nobles) {
        if (levies < 0 || regulars < 0 || nobles < 0) {
            throw new IllegalArgumentException("Troop counts must be non-negative");
        }
        long result = (long) levies * levyUpkeep
                + (long) regulars * regularUpkeep
                + (long) nobles * nobleUpkeep;
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    public Consequence consequence(boolean paid, int maximumMissedCycles) {
        if (paid) return Consequence.PAID;
        if (maximumMissedCycles >= desertAfterMissedCycles) return Consequence.DESERTION;
        if (maximumMissedCycles >= demobilizeAfterMissedCycles) return Consequence.DEMOBILIZE;
        return Consequence.WARNING;
    }

    public int levyUpkeep() { return levyUpkeep; }
    public int regularUpkeep() { return regularUpkeep; }
    public int nobleUpkeep() { return nobleUpkeep; }
    public int demobilizeAfterMissedCycles() { return demobilizeAfterMissedCycles; }
    public int desertAfterMissedCycles() { return desertAfterMissedCycles; }
}
