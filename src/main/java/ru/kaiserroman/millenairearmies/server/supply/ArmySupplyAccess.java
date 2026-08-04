package ru.kaiserroman.millenairearmies.server.supply;

import org.millenaire.entity.MillVillager;

/** Physical ammunition access used by retained combat tasks. */
public interface ArmySupplyAccess {
    ArmySupplyAccess NONE = new ArmySupplyAccess() {
        @Override public boolean hasAssignment(int armyHandle) { return false; }
        @Override public boolean hasArrow(int armyHandle, MillVillager unit) { return true; }
        @Override public boolean consumeArrow(int armyHandle, MillVillager unit) { return true; }
    };

    boolean hasAssignment(int armyHandle);

    /** Checks whether a ranged attempt may start without mutating the selected container. */
    boolean hasArrow(int armyHandle, MillVillager unit);

    /** Consumes one real arrow after a successful physical ranged attack. */
    boolean consumeArrow(int armyHandle, MillVillager unit);
}
