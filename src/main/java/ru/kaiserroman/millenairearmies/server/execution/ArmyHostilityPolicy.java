package ru.kaiserroman.millenairearmies.server.execution;

/** Read-only hostility decision injected by Realm; Armies never mutates war state through it. */
@FunctionalInterface
public interface ArmyHostilityPolicy {
    ArmyHostilityPolicy DENY_ALL = (sourceArmy, targetArmy, sourceFaction, targetFaction) -> false;

    boolean hostile(int sourceArmy, int targetArmy, int sourceFaction, int targetFaction);
}
