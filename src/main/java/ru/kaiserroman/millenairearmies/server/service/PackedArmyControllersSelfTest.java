package ru.kaiserroman.millenairearmies.server.service;

/** Lightweight no-framework regression test for primitive controller storage. */
public final class PackedArmyControllersSelfTest {
    private PackedArmyControllersSelfTest() {}

    public static void main(String[] args) {
        PackedArmyControllers controllers = new PackedArmyControllers(2);
        int ordinaryHandle = 0x0010_0001;
        int signedHandle = 0x8010_0002;

        controllers.put(ordinaryHandle, 11L, 12L, true);
        controllers.put(signedHandle, 21L, 22L, true);
        check(controllers.size() == 2, "two rows");
        check(controllers.matches(ordinaryHandle, 11L, 12L), "ordinary handle owner");
        check(controllers.matches(signedHandle, 21L, 22L), "signed raw handle owner");

        controllers.put(signedHandle, 31L, 32L, false);
        check(controllers.size() == 2, "replacement does not append");
        check(!controllers.matches(signedHandle, 31L, 32L), "controller-less army");

        PackedArmyControllers.Cursor cursor = controllers.newCursor();
        int seen = 0;
        while (cursor.advance()) {
            check(cursor.armyHandle() != 0, "nonzero cursor handle");
            seen++;
        }
        check(seen == 2, "cursor row count");

        check(controllers.remove(ordinaryHandle), "remove existing");
        check(!controllers.remove(ordinaryHandle), "remove missing");
        check(controllers.size() == 1, "swap removal size");
        controllers.clear();
        check(controllers.size() == 0, "clear");

        System.out.println("PackedArmyControllers self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
