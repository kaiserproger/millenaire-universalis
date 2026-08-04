package ru.kaiserroman.millenairearmies.server.execution;

/** Deterministic ring-buffer, cursor-gap and primitive-column checks. */
public final class PhysicalBattleEventLogSelfTest {
    private PhysicalBattleEventLogSelfTest() {}

    public static void main(String[] args) {
        PhysicalBattleEventLog log = new PhysicalBattleEventLog(3);
        check(log.capacity() == 3, "capacity retained");
        check(log.estimatedPrimitiveBytes() == 171L, "primitive footprint stable");

        log.append(PhysicalBattleEventLog.CONTACT, 10L, 101, 202, 11, 22, 1, 2, 0, 1_000L, 0);
        log.append(PhysicalBattleEventLog.MELEE_HIT, 11L, 101, 202, 11, 22, 1, 2, 0, 1_001L, 325);
        log.append(PhysicalBattleEventLog.RANGED_SHOT, 12L, 101, 202, 12, 23, 1, 2, 0, 1_002L, 0);

        PhysicalBattleEventLog.Cursor cursor = log.cursorAfter(0L);
        check(cursor.advance(), "first row visible");
        check(cursor.sequence() == 1L
                        && cursor.kind() == PhysicalBattleEventLog.CONTACT
                        && cursor.sourceArmy() == 101
                        && cursor.targetArmy() == 202,
                "contact columns retained");

        log.append(PhysicalBattleEventLog.UNIT_DEFEATED, 13L, 101, 202, 11, 22, 1, 2, 0, 1_003L, 0);
        log.append(PhysicalBattleEventLog.MELEE_HIT, 14L, 101, 203, 11, 24, 1, 3, 0, 1_004L, 100);

        check(cursor.advance(), "slow cursor re-anchors after overwrite");
        check(cursor.droppedCount() == 1L && cursor.sequence() == 3L,
                "overwritten event count reported");
        check(cursor.kind() == PhysicalBattleEventLog.RANGED_SHOT
                        && cursor.targetUnit() == 23
                        && cursor.packedPosition() == 1_002L,
                "re-anchored row is exact");
        check(cursor.advance()
                        && cursor.kind() == PhysicalBattleEventLog.UNIT_DEFEATED
                        && cursor.gameTime() == 13L,
                "defeat row retained");
        check(cursor.advance()
                        && cursor.sequence() == 5L
                        && cursor.amount() == 100
                        && cursor.targetFaction() == 3,
                "latest row retained");
        check(!cursor.advance(), "cursor stops at current tail");

        expectIllegal(() -> log.append((byte) 99, 1L, 1, 2, 3, 4, 5, 6, 0, 7L, 0),
                "unknown event kind rejected");
        expectIllegal(() -> log.cursorAfter(Long.MAX_VALUE), "overflowing cursor rejected");

        log.clear();
        check(log.size() == 0 && log.latestSequence() == 0L && !log.cursor().advance(),
                "clear resets journal");
        System.out.println("PhysicalBattleEventLogSelfTest: OK");
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
