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
        System.out.println("PackedUnitRoleStateSelfTest passed: rows=" + state.size()
                + ", revision=" + state.revision());
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
