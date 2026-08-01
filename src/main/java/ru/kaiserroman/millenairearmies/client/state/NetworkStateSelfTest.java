package ru.kaiserroman.millenairearmies.client.state;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import ru.kaiserroman.millenairearmies.network.ArmiesProtocol;
import ru.kaiserroman.millenairearmies.network.ArmyStateDeltaPayload;
import ru.kaiserroman.millenairearmies.network.ArmyStateSnapshotPayload;
import ru.kaiserroman.millenairearmies.network.FactionMetadataPayload;
import ru.kaiserroman.millenairearmies.network.IssueOrderIntent;

/** Run directly with the NeoForge development classpath; no test framework required. */
public final class NetworkStateSelfTest {
    private NetworkStateSelfTest() {}

    public static void main(String[] args) {
        opaqueSignedHandlesRoundTrip();
        snapshotCodecAndMirrorRoundTrip();
        factionMetadataCodecAndMirrorRoundTrip();
        deltasUpdateWithoutObjectRowsAndDetectGaps();
        protocolBoundsAreEnforced();
        System.out.println("Armies network/client-state self-test passed");
    }

    private static void factionMetadataCodecAndMirrorRoundTrip() {
        int[] ints = {
            7, 3, 84, 1_275,
            9, 1, 16, 245
        };
        FactionMetadataPayload source = new FactionMetadataPayload(
                12L,
                4L,
                2,
                ints,
                new long[] {123L, 456L},
                new String[] {
                    "millenaire:norman", "Norman Kingdom", "Caen",
                    "millenaire:byzantine", "Византийская империя", "Константинополь"
                });
        FactionMetadataPayload decoded = roundTrip(FactionMetadataPayload.STREAM_CODEC, source);
        check(decoded.count() == 2 && decoded.projectionRevision() == 4L,
                "metadata header round-trip");
        check("Константинополь".equals(decoded.stringValue(1, FactionMetadataPayload.STRING_CAPITAL_NAME)),
                "bounded UTF-8 metadata round-trip");

        ClientFactionMetadataState state = new ClientFactionMetadataState();
        check(state.apply(decoded), "metadata applied");
        int norman = state.findFactionRow(7);
        check(norman == 0, "faction metadata indexed");
        check(state.intValue(norman, FactionMetadataPayload.COLUMN_POPULATION) == 84,
                "projection population mirrored");
        check("Caen".equals(state.stringValue(norman, FactionMetadataPayload.STRING_CAPITAL_NAME)),
                "capital name mirrored without render-time formatting");

        String oversized = "я".repeat(FactionMetadataPayload.MAX_STRING_UTF8_BYTES / 2 + 1);
        expectIllegal(() -> new FactionMetadataPayload(
                1L,
                1L,
                1,
                new int[FactionMetadataPayload.INT_COLUMNS],
                new long[1],
                new String[] {oversized, "name", "capital"}),
                "oversized UTF-8 metadata rejected");
    }

    private static void opaqueSignedHandlesRoundTrip() {
        int highGenerationHandle = 0x8000_002a;
        IssueOrderIntent source = new IssueOrderIntent(
                17,
                highGenerationHandle,
                ArmiesProtocol.ORDER_MOVE,
                123L,
                456L,
                91,
                77L,
                (byte) (ArmiesProtocol.ORDER_FLAG_SECONDARY_POSITION
                        | ArmiesProtocol.ORDER_FLAG_SUBJECT_ENTITY));
        IssueOrderIntent decoded = roundTrip(IssueOrderIntent.STREAM_CODEC, source);
        check(decoded.armyHandle() == highGenerationHandle, "opaque signed army handle round-trip");
        check(decoded.secondaryPosition() == 456L && decoded.subjectEntityId() == 91, "optional targets round-trip");
    }

    private static void snapshotCodecAndMirrorRoundTrip() {
        int signedArmyHandle = 0x9000_0003;
        int[] ints = new int[2 * ArmiesProtocol.INT_COLUMNS];
        ints[0] = 7;
        ints[1] = 2;
        ints[ArmiesProtocol.INT_COLUMNS] = signedArmyHandle;
        ints[ArmiesProtocol.INT_COLUMNS + 1] = 2_049;
        ints[ArmiesProtocol.INT_COLUMNS + 2] = 7;
        long[] longs = new long[2 * ArmiesProtocol.LONG_COLUMNS];
        longs[2] = 0x1234_5678_9abc_def0L;
        byte[] bytes = new byte[2 * ArmiesProtocol.BYTE_COLUMNS];
        bytes[2] = ArmiesProtocol.ORDER_HOLD;

        ArmyStateSnapshotPayload source = new ArmyStateSnapshotPayload(
                5L,
                7,
                (byte) (ArmiesProtocol.SECTION_FACTIONS | ArmiesProtocol.SECTION_ARMIES),
                1,
                1,
                0,
                0,
                0,
                0,
                ints,
                longs,
                bytes);
        ArmyStateSnapshotPayload decoded = roundTrip(ArmyStateSnapshotPayload.STREAM_CODEC, source);
        check(decoded.revision() == 5L && decoded.rowCount() == 2, "snapshot header round-trip");
        check(decoded.intColumns()[ArmiesProtocol.INT_COLUMNS] == signedArmyHandle, "snapshot raw handle round-trip");

        ClientArmyState state = new ClientArmyState();
        check(state.applySnapshot(decoded), "snapshot applied");
        check(state.playerFactionId() == 7, "player faction mirrored");
        check(state.table(ArmiesProtocol.KIND_FACTION).size() == 1, "faction row mirrored");
        PackedMirrorTable armies = state.table(ArmiesProtocol.KIND_ARMY);
        check(armies.findRow(signedArmyHandle) == 0, "signed army indexed as opaque bits");
        check(armies.unsignedHandle(0) == Integer.toUnsignedLong(signedArmyHandle), "unsigned UI identity exposed");
    }

    private static void deltasUpdateWithoutObjectRowsAndDetectGaps() {
        int army = 0x8000_0001;
        int[] initialInts = new int[ArmiesProtocol.INT_COLUMNS];
        initialInts[0] = army;
        ClientArmyState state = new ClientArmyState();
        state.applySnapshot(new ArmyStateSnapshotPayload(
                10,
                -1,
                ArmiesProtocol.SECTION_ARMIES,
                0,
                1,
                0,
                0,
                0,
                0,
                initialInts,
                new long[ArmiesProtocol.LONG_COLUMNS],
                new byte[ArmiesProtocol.BYTE_COLUMNS]));

        int[] changedInts = initialInts.clone();
        changedInts[ArmiesProtocol.COLUMN_VALUE_0] = 64;
        ArmyStateDeltaPayload update = new ArmyStateDeltaPayload(
                10,
                11,
                new byte[] {ArmiesProtocol.KIND_ARMY},
                new byte[] {ArmiesProtocol.DELTA_UPSERT},
                changedInts,
                new long[ArmiesProtocol.LONG_COLUMNS],
                new byte[ArmiesProtocol.BYTE_COLUMNS]);
        check(state.applyDelta(roundTrip(ArmyStateDeltaPayload.STREAM_CODEC, update)), "matching delta applied");
        check(state.table(ArmiesProtocol.KIND_ARMY).intValue(0, ArmiesProtocol.COLUMN_VALUE_0) == 64,
                "delta replaced primitive row");

        ArmyStateDeltaPayload remove = new ArmyStateDeltaPayload(
                11,
                12,
                new byte[] {ArmiesProtocol.KIND_ARMY},
                new byte[] {ArmiesProtocol.DELTA_REMOVE},
                changedInts,
                new long[ArmiesProtocol.LONG_COLUMNS],
                new byte[ArmiesProtocol.BYTE_COLUMNS]);
        check(state.applyDelta(remove), "remove delta applied");
        check(state.table(ArmiesProtocol.KIND_ARMY).size() == 0, "remove delta removed row");

        ArmyStateDeltaPayload gap = new ArmyStateDeltaPayload(
                14,
                15,
                new byte[] {ArmiesProtocol.KIND_FACTION},
                new byte[] {ArmiesProtocol.DELTA_UPSERT},
                new int[ArmiesProtocol.INT_COLUMNS],
                new long[ArmiesProtocol.LONG_COLUMNS],
                new byte[ArmiesProtocol.BYTE_COLUMNS]);
        check(!state.applyDelta(gap) && state.requiresResync(), "revision gap requests resync");

        state.applySnapshot(new ArmyStateSnapshotPayload(
                15,
                -1,
                ArmiesProtocol.SECTION_ALL,
                0,
                0,
                0,
                0,
                0,
                0,
                new int[0],
                new long[0],
                new byte[0]));
        check(!state.requiresResync(), "full snapshot clears stale sections");
    }

    private static void protocolBoundsAreEnforced() {
        expectIllegal(() -> new ArmyStateDeltaPayload(
                0,
                1,
                new byte[ArmiesProtocol.MAX_DELTA_ROWS + 1],
                new byte[ArmiesProtocol.MAX_DELTA_ROWS + 1],
                new int[(ArmiesProtocol.MAX_DELTA_ROWS + 1) * ArmiesProtocol.INT_COLUMNS],
                new long[(ArmiesProtocol.MAX_DELTA_ROWS + 1) * ArmiesProtocol.LONG_COLUMNS],
                new byte[(ArmiesProtocol.MAX_DELTA_ROWS + 1) * ArmiesProtocol.BYTE_COLUMNS]),
                "oversized delta rejected");
        expectIllegal(() -> new IssueOrderIntent(1, -99, (byte) 99, 0, 0, -1, 0, (byte) 0),
                "unknown order rejected without rejecting signed handle");
    }

    private static <T> T roundTrip(StreamCodec<RegistryFriendlyByteBuf, T> codec, T source) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            codec.encode(buffer, source);
            buffer.readerIndex(0);
            return codec.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static void expectIllegal(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
