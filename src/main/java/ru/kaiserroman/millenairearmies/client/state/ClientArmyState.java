package ru.kaiserroman.millenairearmies.client.state;

import java.util.Arrays;
import ru.kaiserroman.millenairearmies.network.ArmiesProtocol;
import ru.kaiserroman.millenairearmies.network.ArmyStateDeltaPayload;
import ru.kaiserroman.millenairearmies.network.ArmyStateSnapshotPayload;

/**
 * Client-side, allocation-stable mirror for the strategic UI. It is updated only by main-thread
 * network handlers; render code reads the primitive tables directly by row.
 */
public final class ClientArmyState {
    public static final ClientArmyState INSTANCE = new ClientArmyState();

    private final PackedMirrorTable[] tables = new PackedMirrorTable[ArmiesProtocol.KIND_COUNT];
    private final long[] sectionRevisions = new long[ArmiesProtocol.KIND_COUNT];
    private long revision;
    private int playerFactionId = -1;
    private byte staleSections;
    private boolean initialized;
    private ClientStateListener listener = ClientStateListener.NOOP;

    public ClientArmyState() {
        for (int kind = 0; kind < tables.length; kind++) {
            tables[kind] = new PackedMirrorTable();
        }
    }

    public long revision() {
        return revision;
    }

    public int playerFactionId() {
        return playerFactionId;
    }

    public boolean requiresResync() {
        return staleSections != 0;
    }

    public boolean initialized() {
        return initialized;
    }

    public void listener(ClientStateListener replacement) {
        listener = replacement == null ? ClientStateListener.NOOP : replacement;
        listener.stateChanged(this);
    }

    public byte staleSections() {
        return staleSections;
    }

    public long sectionRevision(byte kind) {
        checkKind(kind);
        return sectionRevisions[kind];
    }

    public long oldestRevision(byte sectionMask) {
        if (!ArmiesProtocol.validSectionMask(sectionMask)) {
            throw new IllegalArgumentException("Invalid section mask: " + sectionMask);
        }
        long oldest = Long.MAX_VALUE;
        for (byte kind = 0; kind < ArmiesProtocol.KIND_COUNT; kind++) {
            if ((sectionMask & ArmiesProtocol.sectionForKind(kind)) != 0) {
                oldest = Math.min(oldest, sectionRevisions[kind]);
            }
        }
        return oldest == Long.MAX_VALUE ? 0 : oldest;
    }

    public PackedMirrorTable table(byte kind) {
        checkKind(kind);
        return tables[kind];
    }

    public boolean applySnapshot(ArmyStateSnapshotPayload payload) {
        if (payload.revision() < revision && allRequestedSectionsCurrent(payload)) {
            return false;
        }
        int rowOffset = 0;
        for (byte kind = 0; kind < ArmiesProtocol.KIND_COUNT; kind++) {
            int count = payload.count(kind);
            byte section = ArmiesProtocol.sectionForKind(kind);
            if ((payload.sectionMask() & section) != 0 && payload.revision() >= sectionRevisions[kind]) {
                tables[kind].replace(
                        payload.intColumns(), payload.longColumns(), payload.byteColumns(), rowOffset, count);
                sectionRevisions[kind] = payload.revision();
                staleSections &= ~section;
            }
            rowOffset += count;
        }
        if (payload.revision() >= revision) {
            revision = payload.revision();
            playerFactionId = payload.playerFactionId();
        }
        initialized = true;
        listener.stateChanged(this);
        return true;
    }

    public boolean applyDelta(ArmyStateDeltaPayload payload) {
        if (payload.revision() <= revision) {
            return false;
        }
        if (payload.baseRevision() != revision) {
            staleSections |= ArmiesProtocol.SECTION_ALL;
            return false;
        }

        int[] ints = payload.intColumns();
        for (int row = 0; row < payload.rowCount(); row++) {
            byte kind = payload.kinds()[row];
            PackedMirrorTable table = tables[kind];
            if (payload.operations()[row] == ArmiesProtocol.DELTA_REMOVE) {
                table.remove(ints[row * ArmiesProtocol.INT_COLUMNS + ArmiesProtocol.COLUMN_HANDLE]);
            } else {
                table.upsert(ints, payload.longColumns(), payload.byteColumns(), row);
            }
            sectionRevisions[kind] = payload.revision();
        }
        revision = payload.revision();
        listener.stateChanged(this);
        return true;
    }

    /** Called on client disconnect/world change so state from another server cannot leak into UI. */
    public void reset() {
        for (PackedMirrorTable table : tables) {
            table.clear();
        }
        Arrays.fill(sectionRevisions, 0L);
        revision = 0;
        playerFactionId = -1;
        staleSections = 0;
        initialized = false;
        listener.stateChanged(this);
    }

    private boolean allRequestedSectionsCurrent(ArmyStateSnapshotPayload payload) {
        for (byte kind = 0; kind < ArmiesProtocol.KIND_COUNT; kind++) {
            if ((payload.sectionMask() & ArmiesProtocol.sectionForKind(kind)) != 0
                    && payload.revision() >= sectionRevisions[kind]) {
                return false;
            }
        }
        return true;
    }

    private static void checkKind(byte kind) {
        if (!ArmiesProtocol.validKind(kind)) {
            throw new IllegalArgumentException("Unknown table kind: " + kind);
        }
    }
}
