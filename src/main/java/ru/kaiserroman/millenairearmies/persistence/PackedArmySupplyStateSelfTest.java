package ru.kaiserroman.millenairearmies.persistence;

/** Dependency-free validation of persistent army-to-container bindings. */
public final class PackedArmySupplyStateSelfTest {
    private PackedArmySupplyStateSelfTest() {}

    public static void main(String[] args) {
        PackedArmySupplyState state = new PackedArmySupplyState();
        check(state.assign(11, 2, 100L), "first binding");
        check(!state.assign(11, 2, 100L), "idempotent binding");
        check(state.assign(11, 3, 200L), "binding update");
        check(state.findArmy(11) == 0, "lookup");
        check(state.dimensionIdAt(0) == 3 && state.chestPositionAt(0) == 200L, "payload");
        check(state.remove(11) && state.size() == 0, "remove");

        PackedArmySupplyState restored = new PackedArmySupplyState();
        restored.restoreRow(21, 4, 300L);
        restored.restoreRow(22, 5, 400L);
        expectIllegalArgument(() -> restored.restoreRow(21, 6, 500L), "duplicate rejected");
        expectIllegalArgument(() -> restored.restoreRevision(1L), "revision below rows rejected");
        restored.restoreRevision(8L);
        check(restored.revision() == 8L && restored.size() == 2, "revision restored");
        expectIllegalState(() -> restored.restoreRevision(9L), "second revision rejected");
        expectIllegalState(() -> restored.restoreRow(23, 6, 500L), "late row rejected");
        System.out.println("PackedArmySupplyStateSelfTest passed");
    }

    private static void expectIllegalArgument(Runnable action, String label) {
        try { action.run(); throw new AssertionError(label); }
        catch (IllegalArgumentException expected) { }
    }

    private static void expectIllegalState(Runnable action, String label) {
        try { action.run(); throw new AssertionError(label); }
        catch (IllegalStateException expected) { }
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
