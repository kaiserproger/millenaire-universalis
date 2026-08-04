package ru.kaiserroman.millenairearmies.client.state;

import ru.kaiserroman.millenairearmies.network.GarrisonStatePayload;

/** Immutable-by-replacement client cache for the bounded visible garrison projection. */
public final class ClientGarrisonState {
    public static final ClientGarrisonState INSTANCE = new ClientGarrisonState();

    private volatile GarrisonStatePayload payload = empty(0L);
    private volatile long viewVersion;

    private ClientGarrisonState() {
    }

    public boolean apply(GarrisonStatePayload next) {
        GarrisonStatePayload current = payload;
        if (next.armyRevision() < current.armyRevision()) {
            return false;
        }
        payload = next;
        viewVersion++;
        NetworkArmyClientMirror.INSTANCE.garrisonChanged();
        return true;
    }

    public void clear() {
        payload = empty(0L);
        viewVersion++;
    }

    public long viewVersion() {
        return viewVersion;
    }

    public int count() {
        return payload.count();
    }

    public int findArmy(int armyHandle) {
        GarrisonStatePayload current = payload;
        for (int row = 0; row < current.count(); row++) {
            if (current.intValue(row, GarrisonStatePayload.COLUMN_ARMY_HANDLE) == armyHandle) {
                return row;
            }
        }
        return -1;
    }

    public int intValue(int row, int column) {
        return payload.intValue(row, column);
    }

    public long longValue(int row, int column) {
        return payload.longValue(row, column);
    }

    public byte status(int row) {
        return payload.statuses()[row];
    }

    public String settlementName(int row) {
        return payload.settlementNames()[row];
    }

    private static GarrisonStatePayload empty(long revision) {
        return new GarrisonStatePayload(revision, 0, new int[0], new long[0], new byte[0], new String[0]);
    }
}
