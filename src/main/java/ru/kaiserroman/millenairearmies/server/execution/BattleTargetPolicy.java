package ru.kaiserroman.millenairearmies.server.execution;

import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;

/** Pure membership/hostility gate preventing strategic combat from selecting civilian residents. */
final class BattleTargetPolicy {
    private BattleTargetPolicy() {}

    static boolean valid(
            int sourceArmy,
            int targetArmy,
            boolean targetArmyAlive,
            boolean hostile) {
        return sourceArmy != PackedArmyEcs.NO_ARMY
                && targetArmy != PackedArmyEcs.NO_ARMY
                && sourceArmy != targetArmy
                && targetArmyAlive
                && hostile;
    }
}
