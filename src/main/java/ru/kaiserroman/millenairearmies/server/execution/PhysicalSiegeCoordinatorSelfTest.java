package ru.kaiserroman.millenairearmies.server.execution;

import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;

/** Deterministic siege-start/progress/secured, clear-confirmation and revision-reset checks. */
public final class PhysicalSiegeCoordinatorSelfTest {
    private PhysicalSiegeCoordinatorSelfTest() {}

    public static void main(String[] args) {
        PhysicalBattleEventLog events = new PhysicalBattleEventLog(24);
        PhysicalSiegeCoordinator sieges = new PhysicalSiegeCoordinator();
        int army = 0x8000_0042;

        sieges.report(army, 1L, 7, 0, 123L, 45, true, 10L, events);
        PhysicalBattleEventLog.Cursor cursor = events.cursor();
        check(cursor.advance() && cursor.kind() == PhysicalBattleEventLog.SIEGE_STARTED,
                "siege start emitted");
        check(cursor.advance()
                        && cursor.kind() == PhysicalBattleEventLog.SIEGE_PROGRESS
                        && cursor.amount() == 45,
                "initial progress emitted");
        check(!cursor.advance(), "engaged defenders prevent secured event");

        sieges.report(army, 1L, 7, 0, 123L, 82, false, 20L, events);
        cursor = events.cursorAfter(2L);
        check(cursor.advance()
                        && cursor.kind() == PhysicalBattleEventLog.SIEGE_PROGRESS
                        && cursor.amount() == 82,
                "meaningful progress delta emitted");
        check(!cursor.advance(), "one clear scan cannot secure a siege");

        sieges.report(army, 1L, 7, 0, 123L, 82, false, 119L, events);
        check(!events.cursorAfter(3L).advance(), "clear confirmation requires full interval");
        sieges.report(army, 1L, 7, 0, 123L, 82, false, 120L, events);
        cursor = events.cursorAfter(3L);
        check(cursor.advance() && cursor.kind() == PhysicalBattleEventLog.SIEGE_PROGRESS,
                "periodic progress remains observable");
        check(cursor.advance()
                        && cursor.kind() == PhysicalBattleEventLog.SIEGE_SECURED
                        && cursor.sourceArmy() == army,
                "continuous clear perimeter becomes physically secured");
        check(!cursor.advance(), "secured emitted once");

        sieges.report(army, 1L, 7, 0, 123L, 90, false, 220L, events);
        cursor = events.cursorAfter(5L);
        check(cursor.advance() && cursor.kind() == PhysicalBattleEventLog.SIEGE_PROGRESS,
                "secured siege keeps publishing periodic progress");
        check(!cursor.advance(), "secured is not duplicated for same revision");

        sieges.report(army, 2L, 7, 0, 456L, 85, false, 300L, events);
        cursor = events.cursorAfter(6L);
        check(cursor.advance()
                        && cursor.kind() == PhysicalBattleEventLog.SIEGE_STARTED
                        && cursor.packedPosition() == 456L,
                "new order revision starts a new physical siege");
        check(cursor.advance() && cursor.kind() == PhysicalBattleEventLog.SIEGE_PROGRESS,
                "new revision publishes progress");
        check(!cursor.advance(), "new revision also waits for clear confirmation");
        sieges.report(army, 2L, 7, 0, 456L, 85, false, 400L, events);
        cursor = events.cursorAfter(8L);
        check(cursor.advance() && cursor.kind() == PhysicalBattleEventLog.SIEGE_PROGRESS,
                "new revision periodic progress emitted");
        check(cursor.advance() && cursor.kind() == PhysicalBattleEventLog.SIEGE_SECURED,
                "new revision secures independently after confirmation");

        long indexedObjective = PackedArmyEcs.packBlockPos(100, 64, 100);
        sieges.report(army, 3L, 7, 2, indexedObjective, 50, true, 500L, events);
        check(sieges.activeNear(2, PackedArmyEcs.packBlockPos(112, 64, 108), 32, 500L),
                "fresh physical siege is visible near its objective");
        check(!sieges.activeNear(1, indexedObjective, 32, 500L),
                "dimension mismatch cannot open a breach");
        check(!sieges.activeNear(2, PackedArmyEcs.packBlockPos(500, 64, 500), 32, 500L),
                "distant settlement cannot borrow another siege");
        check(!sieges.activeNear(2, indexedObjective, 32, 701L),
                "stale physical siege report expires");

        sieges.clear();
        expectIllegal(() -> sieges.report(0, 1L, 1, 0, 1L, 10, false, 1L, events),
                "missing army rejected");
        System.out.println("PhysicalSiegeCoordinatorSelfTest: OK");
    }

    private static void expectIllegal(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
