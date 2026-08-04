package ru.kaiserroman.millenairearmies.persistence;

import net.minecraft.core.BlockPos;

/** Deterministic packed-state checks for assignment, upkeep, cursor invalidation and cleanup. */
public final class PackedGarrisonStateSelfTest {
    private PackedGarrisonStateSelfTest() {
    }

    public static void main(String[] args) {
        PackedGarrisonState state = new PackedGarrisonState(2);
        int army = 0x12345;
        long muster = BlockPos.asLong(10, 64, -20);
        check(state.assign(army, 11L, 22L, 3, muster, 32, 1_200L), "binding created");
        check(state.size() == 1 && state.findArmy(army) == 0, "binding indexed");
        check(state.findArmy(army ^ (1 << 20)) < 0,
                "same slot with a stale generation cannot address the live binding");
        check(!state.assign(army, 11L, 22L, 3, muster, 32, 1_200L), "identical binding stable");
        check(!state.assign(army, 11L, 22L, 3, muster, 32, 9_999L),
                "reconfirming the same post cannot postpone upkeep");

        PackedGarrisonState.View view = state.newView();
        check(state.readArmy(army, view), "binding readable");
        long initialRevision = view.revision();
        check(view.supplyPercent() == 100 && view.readinessPercent() == 100 && view.moralePercent() == 100,
                "new garrison begins ready");

        check(state.recordUpkeep(army, false, 2_400L), "failed upkeep recorded");
        state.readArmy(army, view);
        check(view.supplyPercent() == 82 && view.readinessPercent() == 90 && view.moralePercent() == 93,
                "failed upkeep degrades gradually");
        check(view.status() == PackedGarrisonState.STATUS_SUPPLIED,
                "single missed interval is not instant starvation");
        check(view.revision() > initialRevision, "upkeep changes revision");

        for (int index = 0; index < 5; index++) {
            state.recordUpkeep(army, false, 3_600L + index * 1_200L);
        }
        state.readArmy(army, view);
        check(view.status() == PackedGarrisonState.STATUS_STARVING,
                "repeated failures reach starving state");
        int degraded = view.supplyPercent();
        check(state.assign(army, 33L, 44L, 3, BlockPos.asLong(12, 64, -18), 24, 10_000L),
                "garrison can move to another controlled post");
        state.readArmy(army, view);
        check(view.supplyPercent() == degraded, "reassignment cannot refill upkeep state");
        check(state.recordUpkeep(army, true, 11_200L), "successful upkeep recorded");
        state.readArmy(army, view);
        check(view.supplyPercent() > degraded, "successful upkeep recovers gradually");

        int secondArmy = 0x22346;
        check(state.assign(secondArmy, 55L, 66L, 3, BlockPos.asLong(14, 64, -16), 20, 11_500L),
                "second binding created");
        PackedGarrisonState.Cursor cursor = state.newCursor();
        check(cursor.advance(), "cursor visits row");
        check(state.removeArmy(army), "binding removed");
        expectIllegalState(cursor::armyHandle, "structural mutation invalidates cursor");
        check(state.findArmy(secondArmy) == 0,
                "swap removal repairs the slot-to-dense index");
        check(state.removeArmy(secondArmy), "second binding removed");
        check(state.size() == 0 && !state.readArmy(army, view), "removed binding is absent");

        expectIllegalArgument(() -> state.assign(0, 1L, 2L, 0, 0L, 32, 0L), "zero army rejected");
        expectIllegalArgument(() -> state.assign(1, 0L, 0L, 0, 0L, 32, 0L), "absent village rejected");
        expectIllegalArgument(() -> state.restore(
                1, 1L, 2L, 0, 0L, 32, 101, 50, 50,
                PackedGarrisonState.STATUS_LOW, 0L, 1L), "invalid percentage rejected");
        System.out.println("Packed garrison state self-test passed");
    }

    private static void expectIllegalArgument(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void expectIllegalState(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalStateException expected) {
            // Expected.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
