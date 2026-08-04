package ru.kaiserroman.millenairearmies.client.state;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import ru.kaiserroman.millenaire.realm.DiplomaticStatus;
import ru.kaiserroman.millenaire.realm.WarGoal;
import ru.kaiserroman.millenairearmies.network.ArmiesProtocol;
import ru.kaiserroman.millenairearmies.network.ArmyStateDeltaPayload;
import ru.kaiserroman.millenairearmies.network.ArmyStateSnapshotPayload;
import ru.kaiserroman.millenairearmies.network.ArmyRosterSnapshotPayload;
import ru.kaiserroman.millenairearmies.network.FactionMetadataPayload;
import ru.kaiserroman.millenairearmies.network.GarrisonStatePayload;
import ru.kaiserroman.millenairearmies.network.IssueOrderIntent;
import ru.kaiserroman.millenairearmies.network.RealmDiplomacySnapshotPayload;
import ru.kaiserroman.millenairearmies.network.RecruitUnitsIntent;
import ru.kaiserroman.millenairearmies.network.SetGarrisonIntent;
import ru.kaiserroman.millenairearmies.network.SetSupplyChestIntent;
import ru.kaiserroman.millenairearmies.network.SetTacticalIntent;
import ru.kaiserroman.millenairearmies.network.SetUnitLoadoutIntent;

/** Run directly with the NeoForge development classpath; no test framework required. */
public final class NetworkStateSelfTest {
    private NetworkStateSelfTest() {}

    public static void main(String[] args) {
        opaqueSignedHandlesRoundTrip();
        snapshotCodecAndMirrorRoundTrip();
        factionMetadataCodecAndMirrorRoundTrip();
        rosterAndRecruitmentCodecRoundTrip();
        loadoutIntentCodecRoundTrip();
        tacticalAndSupplyIntentCodecRoundTrip();
        garrisonCodecRoundTrip();
        realmDiplomacyCodecAndStaleRevision();
        deltasUpdateWithoutObjectRowsAndDetectGaps();
        protocolBoundsAreEnforced();
        System.out.println("Armies network/client-state self-test passed");
    }

    private static void rosterAndRecruitmentCodecRoundTrip() {
        ArmyRosterSnapshotPayload roster = new ArmyRosterSnapshotPayload(
                14L,
                3,
                ArmiesProtocol.ACTION_RECRUIT,
                ArmiesProtocol.RESULT_ACCEPTED,
                1,
                1,
                1,
                new int[] {
                    7, 25, 1, ArmiesProtocol.SETTLEMENT_ACCESS_CONTROLLED
                },
                new long[] {11L, 12L, 99L},
                new String[] {"Caen", "millenaire:norman"},
                new int[] {
                    18,
                    ArmiesProtocol.RECRUIT_OPTION_ENLIST,
                    0,
                    4096,
                    4096
                },
                new long[] {21L, 22L, 11L, 12L},
                new String[] {"Agnès Martin", "Guard"});
        ArmyRosterSnapshotPayload decoded = roundTrip(ArmyRosterSnapshotPayload.STREAM_CODEC, roster);
        check(decoded.recruitCount() == 1
                        && "Agnès Martin".equals(decoded.recruitString(0, ArmyRosterSnapshotPayload.RECRUIT_NAME)),
                "bounded roster UTF-8 round-trip");

        RecruitUnitsIntent intent = new RecruitUnitsIntent(
                4, 0x8000_002a, 11L, 12L, 14L, 1, new long[] {21L, 22L});
        RecruitUnitsIntent decodedIntent = roundTrip(RecruitUnitsIntent.STREAM_CODEC, intent);
        check(decodedIntent.armyHandle() == 0x8000_002a
                        && decodedIntent.villagerUuidBits()[1] == 22L,
                "bounded selected recruit round-trip");
        expectIllegal(() -> new RecruitUnitsIntent(
                        1, 1, 1L, 2L, 0L,
                        ArmiesProtocol.MAX_RECRUITS_PER_INTENT + 1,
                        new long[(ArmiesProtocol.MAX_RECRUITS_PER_INTENT + 1) * 2]),
                "oversized recruit selection rejected");
    }

    private static void loadoutIntentCodecRoundTrip() {
        int signedArmy = 0xaabb_ccdd;
        int unit = 0x5aa5_007f;
        SetUnitLoadoutIntent tokenIntent = new SetUnitLoadoutIntent(
                12,
                signedArmy,
                unit,
                SetUnitLoadoutIntent.LOADOUT_BY_TOKEN,
                -401,
                null,
                17L);
        SetUnitLoadoutIntent tokenDecoded = roundTrip(
                SetUnitLoadoutIntent.STREAM_CODEC, tokenIntent);
        check(tokenDecoded.loadoutSelector() == SetUnitLoadoutIntent.LOADOUT_BY_TOKEN
                        && tokenDecoded.loadoutToken() == -401
                        && tokenDecoded.armyHandle() == signedArmy,
                "unit loadout signed token intent preserves selector/values");

        ResourceLocation loadoutKey = ResourceLocation.fromNamespaceAndPath("millenairearmies", "test/loadout");
        SetUnitLoadoutIntent keyIntent = SetUnitLoadoutIntent.forKey(
                13, signedArmy, unit, loadoutKey, 18L);
        SetUnitLoadoutIntent keyDecoded = roundTrip(
                SetUnitLoadoutIntent.STREAM_CODEC, keyIntent);
        check(keyDecoded.loadoutSelector() == SetUnitLoadoutIntent.LOADOUT_BY_KEY
                        && loadoutKey.equals(keyDecoded.loadoutKey())
                        && keyDecoded.armyHandle() == signedArmy,
                "unit loadout key intent preserves registry selector");

        SetUnitLoadoutIntent clearIntent = SetUnitLoadoutIntent.clearOverride(
                14, signedArmy, unit, 19L);
        SetUnitLoadoutIntent clearDecoded = roundTrip(
                SetUnitLoadoutIntent.STREAM_CODEC, clearIntent);
        check(clearDecoded.loadoutSelector() == SetUnitLoadoutIntent.LOADOUT_DEFAULT
                        && clearDecoded.loadoutToken() == 0
                        && clearDecoded.loadoutKey() == null,
                "unit loadout clear intent round-trip");

        expectIllegal(() -> new SetUnitLoadoutIntent(
                        1, 1, 1,
                        (byte) 9,
                        0,
                        ResourceLocation.withDefaultNamespace("invalid"),
                        0L),
                "invalid loadout selector rejected");
        expectIllegal(() -> new SetUnitLoadoutIntent(
                        1, 1, 1,
                        SetUnitLoadoutIntent.LOADOUT_BY_TOKEN,
                        0,
                        ResourceLocation.withDefaultNamespace("ignored"),
                        0L),
                "token selector requires null key");
        expectIllegal(() -> SetUnitLoadoutIntent.forToken(1, 1, 1, 0, 0L),
                "zero token must use the default selector");
    }

    private static void tacticalAndSupplyIntentCodecRoundTrip() {
        SetTacticalIntent tactical = new SetTacticalIntent(
                21, 0x9000_0011, ArmiesProtocol.TACTIC_SHIELD_WALL, true, 44L);
        SetTacticalIntent tacticalDecoded = roundTrip(SetTacticalIntent.STREAM_CODEC, tactical);
        check(tacticalDecoded.armyHandle() == 0x9000_0011
                        && tacticalDecoded.enabled()
                        && tacticalDecoded.tacticalCode() == ArmiesProtocol.TACTIC_SHIELD_WALL,
                "tactical intent round-trip");
        expectIllegal(() -> new SetTacticalIntent(1, 1, (byte) 7, true, 0L),
                "unknown tactical code rejected");

        ResourceLocation dimension = ResourceLocation.withDefaultNamespace("overworld");
        SetSupplyChestIntent supply = new SetSupplyChestIntent(
                22, SetSupplyChestIntent.OP_SET, 0x8000_0022, dimension, 123456L, 45L);
        SetSupplyChestIntent supplyDecoded = roundTrip(SetSupplyChestIntent.STREAM_CODEC, supply);
        check(supplyDecoded.operation() == SetSupplyChestIntent.OP_SET
                        && supplyDecoded.armyHandle() == 0x8000_0022
                        && supplyDecoded.dimension().equals(dimension)
                        && supplyDecoded.chestPosition() == 123456L,
                "supply chest intent round-trip");
        SetSupplyChestIntent clearDecoded = roundTrip(
                SetSupplyChestIntent.STREAM_CODEC,
                SetSupplyChestIntent.clear(23, 0x8000_0022, dimension, 46L));
        check(clearDecoded.operation() == SetSupplyChestIntent.OP_CLEAR,
                "supply chest clear intent round-trip");
    }

    private static void garrisonCodecRoundTrip() {
        int signedArmyHandle = 0x9000_002a;
        SetGarrisonIntent intent = new SetGarrisonIntent(
                31,
                SetGarrisonIntent.OP_SET,
                signedArmyHandle,
                11L,
                12L,
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
                12345L,
                32,
                77L);
        SetGarrisonIntent decodedIntent = roundTrip(SetGarrisonIntent.STREAM_CODEC, intent);
        check(decodedIntent.armyHandle() == signedArmyHandle
                        && decodedIntent.guardRadius() == 32
                        && decodedIntent.musterPosition() == 12345L,
                "garrison intent preserves opaque handle and bounded post");

        int[] ints = new int[GarrisonStatePayload.INT_COLUMNS];
        ints[GarrisonStatePayload.COLUMN_ARMY_HANDLE] = signedArmyHandle;
        ints[GarrisonStatePayload.COLUMN_DIMENSION_ID] = 0;
        ints[GarrisonStatePayload.COLUMN_GUARD_RADIUS] = 32;
        ints[GarrisonStatePayload.COLUMN_SUPPLY] = 82;
        ints[GarrisonStatePayload.COLUMN_READINESS] = 90;
        ints[GarrisonStatePayload.COLUMN_MORALE] = 93;
        long[] longs = new long[GarrisonStatePayload.LONG_COLUMNS];
        longs[GarrisonStatePayload.LONG_VILLAGE_MOST] = 11L;
        longs[GarrisonStatePayload.LONG_VILLAGE_LEAST] = 12L;
        longs[GarrisonStatePayload.LONG_MUSTER_POSITION] = 12345L;
        longs[GarrisonStatePayload.LONG_NEXT_UPKEEP_TICK] = 1200L;
        longs[GarrisonStatePayload.LONG_REVISION] = 4L;
        GarrisonStatePayload payload = new GarrisonStatePayload(77L, 1, ints, longs,
                new byte[] {1}, new String[] {"Caen"});
        GarrisonStatePayload decoded = roundTrip(GarrisonStatePayload.STREAM_CODEC, payload);
        check(decoded.intValue(0, GarrisonStatePayload.COLUMN_ARMY_HANDLE) == signedArmyHandle
                        && "Caen".equals(decoded.settlementNames()[0])
                        && decoded.longValue(0, GarrisonStatePayload.LONG_REVISION) == 4L,
                "garrison projection round-trip");
        ClientGarrisonState.INSTANCE.clear();
        check(ClientGarrisonState.INSTANCE.apply(decoded), "client garrison projection applied");
        int clientRow = ClientGarrisonState.INSTANCE.findArmy(signedArmyHandle);
        check(clientRow == 0
                        && ClientGarrisonState.INSTANCE.intValue(
                                clientRow, GarrisonStatePayload.COLUMN_READINESS) == 90
                        && "Caen".equals(ClientGarrisonState.INSTANCE.settlementName(clientRow)),
                "client cache exposes bounded readiness and settlement name");
        SetGarrisonIntent clear = roundTrip(
                SetGarrisonIntent.STREAM_CODEC,
                SetGarrisonIntent.clear(32, signedArmyHandle, 77L));
        check(clear.operation() == SetGarrisonIntent.OP_CLEAR
                        && clear.armyHandle() == signedArmyHandle,
                "clear-garrison intent round-trip");

        expectIllegal(() -> new GarrisonStatePayload(0L, 1,
                        new int[GarrisonStatePayload.INT_COLUMNS],
                        new long[GarrisonStatePayload.LONG_COLUMNS],
                        new byte[] {0}, new String[] {"bad"}),
                "zero army garrison row rejected");
        expectIllegal(() -> new SetGarrisonIntent(1, SetGarrisonIntent.OP_SET, 1,
                        0L, 0L, ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
                        0L, 0, 0L),
                "out-of-range garrison radius rejected");
    }

    private static void realmDiplomacyCodecAndStaleRevision() {
        RealmDiplomacySnapshotPayload source = new RealmDiplomacySnapshotPayload(
                9L,
                11L,
                2,
                new long[] {22L, 33L},
                new int[] {
                    -75, 820, 700, 100,
                    0, 100, 80, 850
                },
                new byte[] {
                    (byte) DiplomaticStatus.WAR.ordinal(),
                    (byte) WarGoal.BORDER_CLAIM.ordinal(),
                    (byte) DiplomaticStatus.ALLIANCE.ordinal(),
                    (byte) WarGoal.NONE.ordinal()
                },
                new String[] {"River Council", "Harbour League"});
        RealmDiplomacySnapshotPayload decoded = roundTrip(
                RealmDiplomacySnapshotPayload.STREAM_CODEC, source);
        check(decoded.count() == 2
                        && decoded.otherRealmIds()[0] == 22L
                        && decoded.intValue(
                                0, RealmDiplomacySnapshotPayload.COLUMN_WAR_SCORE) == -75
                        && Byte.toUnsignedInt(decoded.byteValue(
                                0, RealmDiplomacySnapshotPayload.BYTE_STATUS))
                                == DiplomaticStatus.WAR.ordinal()
                        && "Harbour League".equals(decoded.realmNames()[1]),
                "canonical Realm diplomacy codec round-trip");

        ClientRealmDiplomacyState state = ClientRealmDiplomacyState.INSTANCE;
        state.reset();
        check(state.apply(decoded), "canonical Realm diplomacy state applied");
        check(state.size() == 2
                        && state.realmId() == 11L
                        && state.intValue(
                                1, RealmDiplomacySnapshotPayload.COLUMN_TRUST) == 850,
                "canonical Realm diplomacy client mirror parity");
        RealmDiplomacySnapshotPayload stale = new RealmDiplomacySnapshotPayload(
                8L,
                11L,
                0,
                new long[0],
                new int[0],
                new byte[0],
                new String[0]);
        check(!state.apply(stale), "stale Realm diplomacy revision rejected");
        expectIllegal(() -> new RealmDiplomacySnapshotPayload(
                        10L,
                        11L,
                        1,
                        new long[] {22L},
                        new int[] {0, 0, 0, 0},
                        new byte[] {(byte) 127, 0},
                        new String[] {"Invalid"}),
                "invalid Realm diplomacy enum rejected");
        expectIllegal(() -> new RealmDiplomacySnapshotPayload(
                        10L,
                        11L,
                        ArmiesProtocol.MAX_REALM_RELATIONS_PER_SNAPSHOT + 1,
                        new long[0],
                        new int[0],
                        new byte[0],
                        new String[0]),
                "oversized Realm diplomacy snapshot rejected");
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
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
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
        expectIllegal(() -> new IssueOrderIntent(1, -99, (byte) 99,
                        ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
                        0, 0, -1, 0, (byte) 0),
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
