package ru.kaiserroman.millenairearmies.client.state;

import ru.kaiserroman.millenairearmies.network.FactionMetadataPayload;

/**
 * Client-owned bounded faction dictionary. Arrays are replaced only when a metadata snapshot is
 * accepted; render code performs primitive lookups and returns already decoded strings.
 */
public final class ClientFactionMetadataState {
    public static final ClientFactionMetadataState INSTANCE = new ClientFactionMetadataState();
    private static final int[] EMPTY_INTS = new int[0];
    private static final long[] EMPTY_LONGS = new long[0];
    private static final String[] EMPTY_STRINGS = new String[0];

    private long stateRevision;
    private long projectionRevision;
    private int count;
    private int[] ints = EMPTY_INTS;
    private long[] positions = EMPTY_LONGS;
    private String[] strings = EMPTY_STRINGS;
    private FactionMetadataListener listener = FactionMetadataListener.NOOP;

    public void listener(FactionMetadataListener replacement) {
        listener = replacement == null ? FactionMetadataListener.NOOP : replacement;
        listener.metadataChanged(this);
    }

    public boolean apply(FactionMetadataPayload payload) {
        if (payload.stateRevision() < stateRevision
                || payload.stateRevision() == stateRevision
                        && payload.projectionRevision() < projectionRevision) {
            return false;
        }
        stateRevision = payload.stateRevision();
        projectionRevision = payload.projectionRevision();
        count = payload.count();
        ints = payload.intColumns();
        positions = payload.capitalPositions();
        strings = payload.stringColumns();
        listener.metadataChanged(this);
        return true;
    }

    public void reset() {
        stateRevision = 0L;
        projectionRevision = 0L;
        count = 0;
        ints = EMPTY_INTS;
        positions = EMPTY_LONGS;
        strings = EMPTY_STRINGS;
        listener.metadataChanged(this);
    }

    public long stateRevision() {
        return stateRevision;
    }

    public long projectionRevision() {
        return projectionRevision;
    }

    public int size() {
        return count;
    }

    public int findFactionRow(int factionId) {
        for (int row = 0; row < count; row++) {
            if (intValue(row, FactionMetadataPayload.COLUMN_FACTION_ID) == factionId) {
                return row;
            }
        }
        return -1;
    }

    public int intValue(int row, int column) {
        checkRow(row);
        if (column < 0 || column >= FactionMetadataPayload.INT_COLUMNS) {
            throw new IllegalArgumentException("Unknown faction metadata int column " + column);
        }
        return ints[row * FactionMetadataPayload.INT_COLUMNS + column];
    }

    public long capitalPosition(int row) {
        checkRow(row);
        return positions[row];
    }

    public String stringValue(int row, int column) {
        checkRow(row);
        if (column < 0 || column >= FactionMetadataPayload.STRING_COLUMNS) {
            throw new IllegalArgumentException("Unknown faction metadata string column " + column);
        }
        return strings[row * FactionMetadataPayload.STRING_COLUMNS + column];
    }

    private void checkRow(int row) {
        if (row < 0 || row >= count) {
            throw new IllegalArgumentException("Unknown faction metadata row " + row);
        }
    }
}
