package ru.kaiserroman.millenairearmies.client.ui;

import ru.kaiserroman.millenairearmies.client.state.ClientArmyRosterState;
import ru.kaiserroman.millenairearmies.network.ArmiesProtocol;
import ru.kaiserroman.millenairearmies.network.ArmyRosterSnapshotPayload;

/** Deterministic roster/ack state checks used by the build without launching a client. */
public final class ArmyUiStateSelfTest {
    private ArmyUiStateSelfTest() {}

    public static void main(String[] args) {
        ClientArmyRosterState state = new ClientArmyRosterState();
        ArmyRosterSnapshotPayload initial = snapshot(7L, 0, ArmiesProtocol.RESULT_NONE, 2);
        check(state.apply(initial), "initial roster applied");
        check(state.settlementCount() == 1 && state.recruitCount() == 2, "bounded rows visible");
        check("Caen".equals(state.settlementString(0, ArmyRosterSnapshotPayload.SETTLEMENT_NAME)),
                "server settlement name retained");

        ArmyRosterSnapshotPayload accepted = snapshot(7L, 9, ArmiesProtocol.RESULT_ACCEPTED, 1);
        check(state.apply(accepted), "same-revision acknowledgement applied");
        check(state.acknowledgementActionId() == 9
                        && state.acknowledgementAffected() == 1,
                "server acknowledgement visible");
        check(!state.apply(snapshot(7L, 8, ArmiesProtocol.RESULT_INVALID, 2)),
                "older acknowledgement rejected");
        check(!state.apply(snapshot(6L, 10, ArmiesProtocol.RESULT_ACCEPTED, 2)),
                "older state revision rejected");
        System.out.println("Army command UI state self-test passed");
    }

    private static ArmyRosterSnapshotPayload snapshot(long revision, int actionId, int result, int recruits) {
        int[] recruitInts = new int[recruits * ArmyRosterSnapshotPayload.RECRUIT_INT_COLUMNS];
        long[] recruitLongs = new long[recruits * ArmyRosterSnapshotPayload.RECRUIT_LONG_COLUMNS];
        String[] recruitStrings = new String[recruits * ArmyRosterSnapshotPayload.RECRUIT_STRING_COLUMNS];
        for (int row = 0; row < recruits; row++) {
            int ints = row * ArmyRosterSnapshotPayload.RECRUIT_INT_COLUMNS;
            recruitInts[ints + ArmyRosterSnapshotPayload.RECRUIT_STRENGTH] = 10 + row;
            recruitInts[ints + ArmyRosterSnapshotPayload.RECRUIT_MODE] =
                    ArmyRosterSnapshotPayload.RECRUIT_MODE_CONTROLLED;
            recruitLongs[row * 4] = 100 + row;
            recruitLongs[row * 4 + 1] = 200 + row;
            recruitLongs[row * 4 + 2] = 1L;
            recruitLongs[row * 4 + 3] = 2L;
            recruitStrings[row * 2] = "Resident " + row;
            recruitStrings[row * 2 + 1] = "Guard";
        }
        return new ArmyRosterSnapshotPayload(
                revision,
                actionId,
                actionId == 0 ? ArmiesProtocol.ACTION_NONE : ArmiesProtocol.ACTION_RECRUIT,
                result,
                actionId == 0 ? 0 : 1,
                1,
                recruits,
                new int[] {3, 20, recruits, 1},
                new long[] {1L, 2L, 123L},
                new String[] {"Caen", "millenaire:norman"},
                recruitInts,
                recruitLongs,
                recruitStrings);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
