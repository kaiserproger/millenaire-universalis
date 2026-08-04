package ru.kaiserroman.millenairearmies.server.unit;

/** Dependency-free churn test runnable with the compiled main classes. */
public final class PackedUnitRoleStateSelfTest {
    private PackedUnitRoleStateSelfTest() {}

    public static void main(String[] arguments) {
        PackedUnitRoleState state = new PackedUnitRoleState();
        state.reserve(4);
        PackedUnitRoleState.View view = state.newView();

        for (int index = 1; index <= 20_000; index++) {
            int handle = index % 2 == 0 ? index : Integer.MIN_VALUE | index;
            check(state.assign(handle, index * 17, index * 31, index * 43), "insert " + index);
        }
        check(state.size() == 20_000, "size after inserts");

        for (int index = 1; index <= 20_000; index++) {
            int handle = index % 2 == 0 ? index : Integer.MIN_VALUE | index;
            check(state.read(handle, view), "read " + index);
            check(view.unitHandle() == handle, "handle " + index);
            check(view.roleToken() == index * 17, "role " + index);
            check(state.isEquipmentDirty(handle), "initial dirty " + index);
            check(state.markEquipmentProjected(handle), "mark projected " + index);
        }

        for (int index = 3; index <= 20_000; index += 3) {
            int handle = index % 2 == 0 ? index : Integer.MIN_VALUE | index;
            check(state.remove(handle), "remove " + index);
            check(!state.read(handle, view), "removed lookup " + index);
        }
        int expected = 20_000 - (20_000 / 3);
        check(state.size() == expected, "size after removals");

        PackedUnitRoleState.Cursor cursor = state.newCursor();
        int visited = 0;
        while (cursor.advance()) {
            visited++;
            check(cursor.unitHandle() != 0, "cursor zero handle");
        }
        check(visited == expected, "cursor count");

        state.markAllEquipmentDirty();
        cursor.reset();
        while (cursor.advance()) {
            check((cursor.flags() & PackedUnitRoleState.FLAG_EQUIPMENT_DIRTY) != 0, "mark all dirty");
        }

        PackedUnitRoleState focused = new PackedUnitRoleState();
        PackedUnitRoleState.View focusedView = focused.newView();
        check(focused.assign(11, 701, 1301, 2401), "focused assign with role/rank/loadout");
        check(focused.read(11, focusedView), "focused row readable");
        check(focusedView.roleToken() == 701 && focusedView.rankToken() == 1301
                        && focusedView.loadoutToken() == 2401,
                "focused state preserves role and rank during loadout updates");
        check(focused.markEquipmentProjected(11), "focused initial dirty can be cleared");
        check(!focused.isEquipmentDirty(11), "focused row clean after projection");
        check(!focused.assignLoadoutOnly(11, 2401), "focused loadout unchanged no-op ignored");
        check(!focused.isEquipmentDirty(11), "focused idempotent loadout assignment keeps clean");
        check(focused.assignLoadoutOnly(11, 2501), "focused loadout-only mutation");
        check(focused.isEquipmentDirty(11), "focused loadout-only mutation marks dirty");
        check(focused.read(11, focusedView)
                        && focusedView.roleToken() == 701
                        && focusedView.rankToken() == 1301
                        && focusedView.loadoutToken() == 2501,
                "focused loadout-only mutation keeps role/rank");
        check(!focused.assignLoadoutOnly(12, 2501), "missing focused unit is ignored");

        PackedUnitRoleState restored = new PackedUnitRoleState();
        restored.restoreRow(701, 7010, 7020, 7030, (byte) 0);
        restored.restoreRow(702, 8010, 8020, 8030, (byte) PackedUnitRoleState.FLAG_EQUIPMENT_DIRTY);
        expectIllegalArgument(() -> restored.restoreRow(701, 0, 0, 0, (byte) 0),
                "restoreRow duplicate unit handle rejected");
        expectIllegalArgument(() -> restored.restoreRow(703, 0, 0, 0, (byte) 0x7F),
                "restoreRow rejects invalid flag bits");
        restored.restoreRevision(77L);
        PackedUnitRoleState.View restoredView = restored.newView();
        check(restored.size() == 2, "restore row populates rows");
        check(restored.revision() == 77L, "restore revision is set");
        check(restored.read(701, restoredView), "restore row readable");
        check(restoredView.unitHandle() == 701
                        && restoredView.roleToken() == 7010
                        && restoredView.rankToken() == 7020
                        && restoredView.loadoutToken() == 7030,
                "restore row preserves token payload");
        check(!restored.isEquipmentDirty(701), "restore row preserves clean flag");
        check(restored.read(702, restoredView)
                        && (restoredView.flags() & PackedUnitRoleState.FLAG_EQUIPMENT_DIRTY) != 0,
                "restore row preserves dirty flag");
        PackedUnitRoleState invalidRevision = new PackedUnitRoleState();
        invalidRevision.restoreRow(801, 1, 2, 3, (byte) 0);
        invalidRevision.restoreRow(802, 4, 5, 6, (byte) 0);
        expectIllegalArgument(() -> invalidRevision.restoreRevision(-1L),
                "restoreRevision rejects negative revision");
        expectIllegalArgument(() -> invalidRevision.restoreRevision(1L),
                "restoreRevision rejects revision below restored row count");
        invalidRevision.restoreRevision(2L);
        expectIllegalState(() -> invalidRevision.restoreRevision(3L),
                "restoreRevision cannot rewind or repeat after cold load");
        expectIllegalState(() -> invalidRevision.restoreRow(803, 7, 8, 9, (byte) 0),
                "restoreRow cannot bypass revision after cold load");

        System.out.println("PackedUnitRoleStateSelfTest passed: rows=" + state.size()
                + ", revision=" + state.revision());
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

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
