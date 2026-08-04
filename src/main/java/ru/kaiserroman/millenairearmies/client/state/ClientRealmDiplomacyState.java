package ru.kaiserroman.millenairearmies.client.state;

import ru.kaiserroman.millenairearmies.network.RealmDiplomacySnapshotPayload;

/** Client-owned immutable-column snapshot of directed canonical Realm relations. */
public final class ClientRealmDiplomacyState {
    public static final ClientRealmDiplomacyState INSTANCE = new ClientRealmDiplomacyState();
    private static final long[] EMPTY_LONGS = new long[0];
    private static final int[] EMPTY_INTS = new int[0];
    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final String[] EMPTY_STRINGS = new String[0];

    private long revision;
    private long realmId;
    private int count;
    private long[] otherRealms = EMPTY_LONGS;
    private int[] ints = EMPTY_INTS;
    private byte[] bytes = EMPTY_BYTES;
    private String[] names = EMPTY_STRINGS;

    public boolean apply(RealmDiplomacySnapshotPayload payload) {
        if (payload.realmRevision() < revision) return false;
        revision = payload.realmRevision();
        realmId = payload.realmId();
        count = payload.count();
        otherRealms = payload.otherRealmIds();
        ints = payload.intColumns();
        bytes = payload.byteColumns();
        names = payload.realmNames();
        NetworkArmyClientMirror.INSTANCE.realmDiplomacyChanged();
        return true;
    }

    public void reset() {
        revision = 0L;
        realmId = 0L;
        count = 0;
        otherRealms = EMPTY_LONGS;
        ints = EMPTY_INTS;
        bytes = EMPTY_BYTES;
        names = EMPTY_STRINGS;
        NetworkArmyClientMirror.INSTANCE.realmDiplomacyChanged();
    }

    public long revision() { return revision; }
    public long realmId() { return realmId; }
    public int size() { return count; }

    public long otherRealmId(int row) {
        checkRow(row);
        return otherRealms[row];
    }

    public int intValue(int row, int column) {
        checkRow(row);
        if (column < 0 || column >= RealmDiplomacySnapshotPayload.INT_COLUMNS) {
            throw new IllegalArgumentException("Unknown Realm diplomacy int column " + column);
        }
        return ints[row * RealmDiplomacySnapshotPayload.INT_COLUMNS + column];
    }

    public byte byteValue(int row, int column) {
        checkRow(row);
        if (column < 0 || column >= RealmDiplomacySnapshotPayload.BYTE_COLUMNS) {
            throw new IllegalArgumentException("Unknown Realm diplomacy byte column " + column);
        }
        return bytes[row * RealmDiplomacySnapshotPayload.BYTE_COLUMNS + column];
    }

    public String name(int row) {
        checkRow(row);
        return names[row];
    }

    private void checkRow(int row) {
        if (row < 0 || row >= count) {
            throw new IllegalArgumentException("Unknown Realm diplomacy row " + row);
        }
    }
}
