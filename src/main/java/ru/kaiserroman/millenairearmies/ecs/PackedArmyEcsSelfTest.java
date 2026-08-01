package ru.kaiserroman.millenairearmies.ecs;

/** Run with {@code java -ea ...PackedArmyEcsSelfTest}; no test framework required. */
public final class PackedArmyEcsSelfTest {
    private PackedArmyEcsSelfTest() {
    }

    public static void main(String[] args) {
        blockPosPackingRoundTrips();
        handlesAndSwapRemoveStayConsistent();
        cursorsAndSnapshotsAreReusable();
        clearInvalidatesHandles();
        System.out.println("PackedArmyEcs self-test passed");
    }

    private static void blockPosPackingRoundTrips() {
        assertPos(0, 0, 0);
        assertPos(33_554_431, 2_047, 33_554_431);
        assertPos(-33_554_432, -2_048, -33_554_432);
        assertPos(-12_345, -64, 98_765);
    }

    private static void assertPos(int x, int y, int z) {
        long packed = PackedArmyEcs.packBlockPos(x, y, z);
        check(PackedArmyEcs.unpackBlockX(packed) == x, "x round-trip");
        check(PackedArmyEcs.unpackBlockY(packed) == y, "y round-trip");
        check(PackedArmyEcs.unpackBlockZ(packed) == z, "z round-trip");
    }

    private static void handlesAndSwapRemoveStayConsistent() {
        PackedArmyEcs ecs = new PackedArmyEcs(4, 8);
        int red = ecs.createArmy(10, 1, 2, PackedArmyEcs.packBlockPos(1, 64, 2));
        int blue = ecs.createArmy(20, 3, 4, PackedArmyEcs.packBlockPos(3, 65, 4));
        int first = ecs.createUnit(red, 11, 21, PackedArmyEcs.packBlockPos(5, 66, 6));
        int middle = ecs.createUnit(red, 12, 22, PackedArmyEcs.packBlockPos(7, 67, 8));
        int last = ecs.createUnit(blue, 13, 23, PackedArmyEcs.packBlockPos(9, 68, 10));

        check(ecs.armyUnitCount(red) == 2, "red count after create");
        check(ecs.armyUnitCount(blue) == 1, "blue count after create");
        check(ecs.removeUnit(middle), "middle unit removed");
        check(!ecs.isUnitAlive(middle), "removed handle is stale");
        check(ecs.isUnitAlive(first) && ecs.isUnitAlive(last), "swap kept surviving handles valid");
        check(ecs.unitState(last) == 23, "swapped row retained state");

        int replacement = ecs.createUnit(blue, 14, 24, PackedArmyEcs.packBlockPos(11, 69, 12));
        check(replacement != middle, "reused slot received another generation");
        check(!ecs.isUnitAlive(middle), "old generation stayed invalid");
        check(ecs.isUnitAlive(replacement), "replacement is live");

        ecs.unitArmy(first, blue);
        check(ecs.armyUnitCount(red) == 0, "assignment decremented old army");
        check(ecs.armyUnitCount(blue) == 3, "assignment incremented new army");
        check(ecs.removeArmy(blue), "army removed");
        check(!ecs.isArmyAlive(blue), "army handle invalidated");
        check(ecs.unitArmy(first) == PackedArmyEcs.NO_ARMY, "first unit unassigned");
        check(ecs.unitArmy(last) == PackedArmyEcs.NO_ARMY, "last unit unassigned");
        check(ecs.unitArmy(replacement) == PackedArmyEcs.NO_ARMY, "replacement unit unassigned");
        ecs.checkInvariants();
    }

    private static void cursorsAndSnapshotsAreReusable() {
        PackedArmyEcs ecs = new PackedArmyEcs(2, 4);
        int army = ecs.createArmy(7, 30, 40, PackedArmyEcs.packBlockPos(20, 70, 21));
        int unitA = ecs.createUnit(army, 50, 60, PackedArmyEcs.packBlockPos(22, 71, 23));
        int unitB = ecs.createUnit(army, 51, 61, PackedArmyEcs.packBlockPos(24, 72, 25));

        PackedArmyEcs.UnitCursor cursor = ecs.newUnitCursor();
        int visits = 0;
        for (cursor.reset(); cursor.advance(); ) {
            cursor.state(cursor.state() + 100);
            visits++;
        }
        check(visits == 2, "cursor visited packed rows");
        check(ecs.unitState(unitA) == 160, "cursor updated unit A");
        check(ecs.unitState(unitB) == 161, "cursor updated unit B");

        PackedArmyEcs.UnitSnapshot snapshot = ecs.newUnitSnapshot();
        check(ecs.readUnit(unitA, snapshot), "snapshot read A");
        check(snapshot.handle() == unitA && snapshot.army() == army, "snapshot A contents");
        check(ecs.readUnit(unitB, snapshot), "same snapshot read B");
        check(snapshot.handle() == unitB && snapshot.state() == 161, "snapshot overwritten with B");
        ecs.checkInvariants();
    }

    private static void clearInvalidatesHandles() {
        PackedArmyEcs ecs = new PackedArmyEcs(1, 1);
        int army = ecs.createArmy(1, 2, 3, 4L);
        int unit = ecs.createUnit(army, 5, 6, 7L);
        ecs.clear();
        check(ecs.armySize() == 0 && ecs.unitSize() == 0, "clear removed rows");
        check(!ecs.isArmyAlive(army) && !ecs.isUnitAlive(unit), "clear invalidated handles");
        ecs.checkInvariants();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
