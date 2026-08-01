package ru.kaiserroman.millenairearmies.client.state;

import ru.kaiserroman.millenairearmies.network.ArmyRosterSnapshotPayload;

/** Client-owned immutable-by-replacement roster projection. */
public final class ClientArmyRosterState {
    public static final ClientArmyRosterState INSTANCE = new ClientArmyRosterState();
    private static final int[] EMPTY_INTS = new int[0];
    private static final long[] EMPTY_LONGS = new long[0];
    private static final String[] EMPTY_STRINGS = new String[0];

    private long stateRevision;
    private int acknowledgementActionId;
    private byte acknowledgementAction;
    private int acknowledgementResult;
    private int acknowledgementAffected;
    private int settlementCount;
    private int recruitCount;
    private int[] settlementInts = EMPTY_INTS;
    private long[] settlementLongs = EMPTY_LONGS;
    private String[] settlementStrings = EMPTY_STRINGS;
    private int[] recruitInts = EMPTY_INTS;
    private long[] recruitLongs = EMPTY_LONGS;
    private String[] recruitStrings = EMPTY_STRINGS;
    private Runnable listener = () -> {};

    public void listener(Runnable replacement) {
        listener = replacement == null ? () -> {} : replacement;
        listener.run();
    }

    public boolean apply(ArmyRosterSnapshotPayload payload) {
        if (payload.stateRevision() < stateRevision
                || payload.stateRevision() == stateRevision
                        && payload.acknowledgementActionId() < acknowledgementActionId) {
            return false;
        }
        stateRevision = payload.stateRevision();
        acknowledgementActionId = payload.acknowledgementActionId();
        acknowledgementAction = payload.acknowledgementAction();
        acknowledgementResult = payload.acknowledgementResult();
        acknowledgementAffected = payload.acknowledgementAffected();
        settlementCount = payload.settlementCount();
        recruitCount = payload.recruitCount();
        settlementInts = payload.settlementInts();
        settlementLongs = payload.settlementLongs();
        settlementStrings = payload.settlementStrings();
        recruitInts = payload.recruitInts();
        recruitLongs = payload.recruitLongs();
        recruitStrings = payload.recruitStrings();
        listener.run();
        return true;
    }

    public void reset() {
        stateRevision = 0L;
        acknowledgementActionId = 0;
        acknowledgementAction = 0;
        acknowledgementResult = 0;
        acknowledgementAffected = 0;
        settlementCount = 0;
        recruitCount = 0;
        settlementInts = EMPTY_INTS;
        settlementLongs = EMPTY_LONGS;
        settlementStrings = EMPTY_STRINGS;
        recruitInts = EMPTY_INTS;
        recruitLongs = EMPTY_LONGS;
        recruitStrings = EMPTY_STRINGS;
        listener.run();
    }

    public long stateRevision() { return stateRevision; }
    public int acknowledgementActionId() { return acknowledgementActionId; }
    public byte acknowledgementAction() { return acknowledgementAction; }
    public int acknowledgementResult() { return acknowledgementResult; }
    public int acknowledgementAffected() { return acknowledgementAffected; }
    public int settlementCount() { return settlementCount; }
    public int recruitCount() { return recruitCount; }

    public int settlementInt(int row, int column) {
        check(row, settlementCount, column, ArmyRosterSnapshotPayload.SETTLEMENT_INT_COLUMNS);
        return settlementInts[row * ArmyRosterSnapshotPayload.SETTLEMENT_INT_COLUMNS + column];
    }

    public long settlementLong(int row, int column) {
        check(row, settlementCount, column, ArmyRosterSnapshotPayload.SETTLEMENT_LONG_COLUMNS);
        return settlementLongs[row * ArmyRosterSnapshotPayload.SETTLEMENT_LONG_COLUMNS + column];
    }

    public String settlementString(int row, int column) {
        check(row, settlementCount, column, ArmyRosterSnapshotPayload.SETTLEMENT_STRING_COLUMNS);
        return settlementStrings[row * ArmyRosterSnapshotPayload.SETTLEMENT_STRING_COLUMNS + column];
    }

    public int recruitInt(int row, int column) {
        check(row, recruitCount, column, ArmyRosterSnapshotPayload.RECRUIT_INT_COLUMNS);
        return recruitInts[row * ArmyRosterSnapshotPayload.RECRUIT_INT_COLUMNS + column];
    }

    public long recruitLong(int row, int column) {
        check(row, recruitCount, column, ArmyRosterSnapshotPayload.RECRUIT_LONG_COLUMNS);
        return recruitLongs[row * ArmyRosterSnapshotPayload.RECRUIT_LONG_COLUMNS + column];
    }

    public String recruitString(int row, int column) {
        check(row, recruitCount, column, ArmyRosterSnapshotPayload.RECRUIT_STRING_COLUMNS);
        return recruitStrings[row * ArmyRosterSnapshotPayload.RECRUIT_STRING_COLUMNS + column];
    }

    private static void check(int row, int rows, int column, int columns) {
        if (row < 0 || row >= rows || column < 0 || column >= columns) {
            throw new IllegalArgumentException("Roster cell outside active snapshot");
        }
    }
}
