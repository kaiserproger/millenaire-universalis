package ru.kaiserroman.millenairearmies.server.execution;

import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.model.ArmyFormation;

/** Deterministic checks for compact slots, assembly gating, cohesion and revision reset. */
public final class ArmyFormationCoordinatorSelfTest {
    private ArmyFormationCoordinatorSelfTest() {}

    public static void main(String[] args) {
        ArmyFormationCoordinator formations = new ArmyFormationCoordinator();
        formations.reserve(4, 64);
        ArmyFormationCoordinator.Plan first = new ArmyFormationCoordinator.Plan();
        ArmyFormationCoordinator.Plan second = new ArmyFormationCoordinator.Plan();
        long target = PackedArmyEcs.packBlockPos(100, 64, 0);
        int army = 0x0010_0002;
        int unitA = 0x0010_0010;
        int unitB = 0x0010_0011;

        formations.plan(army, unitA, 1L, ArmyFormation.LINE.code(), 2,
                target, 80.0D, 64.0D, -1.1D, 0L, first);
        formations.plan(army, unitB, 1L, ArmyFormation.LINE.code(), 2,
                target, 80.0D, 64.0D, 1.1D, 0L, second);
        check(first.packedPosition() != second.packedPosition(), "line slots are distinct");
        check(!first.canEngage() && first.phase() == ArmyFormationCoordinator.ASSEMBLING,
                "units cannot engage before assembly");

        double ax = PackedArmyEcs.unpackBlockX(first.packedPosition()) + 0.5D;
        double az = PackedArmyEcs.unpackBlockZ(first.packedPosition()) + 0.5D;
        double bx = PackedArmyEcs.unpackBlockX(second.packedPosition()) + 0.5D;
        double bz = PackedArmyEcs.unpackBlockZ(second.packedPosition()) + 0.5D;
        formations.plan(army, unitA, 1L, ArmyFormation.LINE.code(), 2,
                target, ax, 64.0D, az, 31L, first);
        formations.plan(army, unitB, 1L, ArmyFormation.LINE.code(), 2,
                target, bx, 64.0D, bz, 31L, second);
        check(second.phase() == ArmyFormationCoordinator.ADVANCING,
                "cohesive formation begins advance after grace");
        check(formations.formationAdvances() == 1L, "advance transition counted once");

        long before = first.packedPosition();
        for (long tick = 32L; tick < 160L; tick++) {
            formations.tick(tick);
            formations.plan(army, unitA, 1L, ArmyFormation.LINE.code(), 2,
                    target, ax, 64.0D, az, tick, first);
            formations.plan(army, unitB, 1L, ArmyFormation.LINE.code(), 2,
                    target, bx, 64.0D, bz, tick, second);
            ax = PackedArmyEcs.unpackBlockX(first.packedPosition()) + 0.5D;
            az = PackedArmyEcs.unpackBlockZ(first.packedPosition()) + 0.5D;
            bx = PackedArmyEcs.unpackBlockX(second.packedPosition()) + 0.5D;
            bz = PackedArmyEcs.unpackBlockZ(second.packedPosition()) + 0.5D;
        }
        check(first.packedPosition() != before, "formation anchor advances toward target");

        formations.plan(army, unitA, 2L, ArmyFormation.WEDGE.code(), 2,
                target, ax, 64.0D, az, 200L, first);
        check(first.phase() == ArmyFormationCoordinator.ASSEMBLING && !first.canEngage(),
                "new revision returns formation to assembly");
        check(formations.removeUnit(unitA), "active unit removal updates cohesion");
        check(!formations.removeUnit(unitA), "duplicate removal is a no-op");
        check(formations.trackedArmies() == 1 && formations.trackedUnits() == 2,
                "runtime rows are bounded and reused");

        System.out.println("ArmyFormationCoordinatorSelfTest: OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
