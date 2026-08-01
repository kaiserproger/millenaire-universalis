package ru.kaiserroman.millenairearmies.server.execution;

import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;

/** Exact changed-only mutation boundary for the persisted per-unit order projection. */
final class UnitOrderProjection {
    private UnitOrderProjection() {}

    static boolean update(PackedArmyEcs ecs, int unitHandle, int orderCode) {
        if (ecs.unitOrder(unitHandle) == orderCode) {
            return false;
        }
        ecs.unitOrder(unitHandle, orderCode);
        return true;
    }
}
