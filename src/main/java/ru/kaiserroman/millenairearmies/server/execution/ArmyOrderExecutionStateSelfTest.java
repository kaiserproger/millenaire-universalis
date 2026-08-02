package ru.kaiserroman.millenairearmies.server.execution;

import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;

/** Deterministic pure-Java checks for revision acknowledgement and retry semantics. */
public final class ArmyOrderExecutionStateSelfTest {
    private ArmyOrderExecutionStateSelfTest() {}

    public static void main(String[] args) {
        check(!OrderExecutionPolicy.shouldStart(false), "production-default gate is dormant");
        check(OrderExecutionPolicy.shouldStart(true), "explicit opt-in enables experimental bridge");
        long minimumHeight = PackedArmyEcs.packBlockPos(0, -64, 0);
        long maximumHeight = PackedArmyEcs.packBlockPos(0, 320, 0);
        check(
                OrderExecutionPolicy.targetWithinBuildHeight(minimumHeight, -64, 320),
                "minimum build height is valid");
        check(
                !OrderExecutionPolicy.targetWithinBuildHeight(maximumHeight, -64, 320),
                "exclusive maximum build height is rejected");

        PackedArmyOrderRevisions orders = new PackedArmyOrderRevisions();
        PackedUnitExecutionState units = new PackedUnitExecutionState();
        orders.reserve(2);
        units.reserve(3);

        int army = 0x0010_0042;
        int wrappedArmy = 0x8010_0043;
        long targetA = 0x1234_5678_9ABC_DEF0L;
        long targetB = 0x2234_5678_9ABC_DEF0L;

        check(orders.observe(army, 1, 0, targetA) == 1L, "initial revision");
        check(orders.observe(army, 1, 0, targetA) == 1L, "identical commit is stable");
        check(orders.observe(army, 1, 0, targetB) == 2L, "target change bumps revision");
        check(orders.observe(army, 1, 1, targetB) == 3L, "dimension change bumps revision");
        check(orders.observe(army, 2, 1, targetB) == 4L, "order change bumps revision");
        check(orders.targetDimensionId(army) == 1, "current dimension is retained");
        check(orders.observe(wrappedArmy, 0, 0L) == 1L, "signed raw handle is supported");
        check(orders.size() == 2, "two projected armies");

        int unit = 0x0010_0101;
        check(units.needsApply(unit, army, 4L), "new unit needs current order");
        units.markRunning(unit, army, 4L);
        check(!units.needsApply(unit, army, 4L), "running revision is acknowledged");
        check(!units.markRetry(unit, army, 3L), "stale task cannot rewind newer state");
        check(!units.needsApply(unit, army, 4L), "stale retry had no effect");
        check(units.markRetry(unit, army, 4L), "current task may request retry");
        check(units.needsApply(unit, army, 4L), "retry replays same revision");
        units.markTerminal(unit, army, 4L);
        check(!units.needsApply(unit, army, 4L), "terminal revision is stable");
        check(units.needsApply(unit, army, 5L), "new revision invalidates terminal state");
        units.markRunning(unit, army, 5L);
        check(units.invalidate(unit), "reload invalidates known unit");
        check(units.needsApply(unit, army, 5L), "reload replays current revision");

        int reassignedArmy = 0x0010_0050;
        check(units.needsApply(unit, reassignedArmy, 1L), "army reassignment invalidates state");
        units.markTerminal(unit, reassignedArmy, 1L);
        check(!units.markRetry(unit, army, 5L), "old army task cannot affect reassigned unit");

        int cancelledUnit = 0x0010_0110;
        units.markRunning(cancelledUnit, army, 10L);
        units.markPending(cancelledUnit, army, 11L);
        check(!units.markRetry(cancelledUnit, army, 10L), "cancelled task cannot retry stale revision");
        check(units.needsApply(cancelledUnit, army, 11L), "successor remains pending after cancel");
        units.markTerminal(cancelledUnit, army, 11L);
        check(!units.needsApply(cancelledUnit, army, 11L), "successor can complete normally");

        int nextGenerationUnit = unit + (1 << 20);
        check(units.needsApply(nextGenerationUnit, army, 3L), "new slot generation is not stale state");
        units.markRunning(nextGenerationUnit, army, 3L);
        check(!units.needsApply(nextGenerationUnit, army, 3L), "new generation is acknowledged");
        check(units.size() == 2, "slot reuse does not leak dense execution rows");

        PackedArmyEcs ecs = new PackedArmyEcs(1, 1);
        int projectedArmy = ecs.createArmy(7, 1, 0, targetA);
        int projectedUnit = ecs.createUnit(projectedArmy, 1, 0, 0L);
        check(!UnitOrderProjection.update(ecs, projectedUnit, 1), "identical unit projection is clean");
        check(UnitOrderProjection.update(ecs, projectedUnit, 2), "changed unit projection is dirty");
        check(ecs.unitOrder(projectedUnit) == 2, "changed projection is stored");
        check(!UnitOrderProjection.update(ecs, projectedUnit, 2), "repeated projection stays clean");

        long initialPosition = PackedArmyEcs.packBlockPos(1, 64, 2);
        long movedPosition = PackedArmyEcs.packBlockPos(18, 65, -9);
        ecs.unitPackedPos(projectedUnit, initialPosition);
        check(
                !LoadedUnitPositionProjection.updatePosition(ecs, projectedUnit, initialPosition),
                "identical physical position is clean");
        check(
                LoadedUnitPositionProjection.updatePosition(ecs, projectedUnit, movedPosition),
                "changed physical position is dirty");
        check(ecs.unitPackedPos(projectedUnit) == movedPosition, "physical position is stored");
        check(
                !LoadedUnitPositionProjection.updatePosition(ecs, projectedUnit, movedPosition),
                "repeated physical position stays clean");

        System.out.println("ArmyOrderExecutionStateSelfTest: OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
