package ru.kaiserroman.millenairearmies.network;

import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenairearmies.SarvarMillenaireArmies;

/**
 * Server-authoritative projection of player-controlled settlements and currently recruitable NPCs.
 * Every array has a fixed stride and explicit row/string budgets.
 */
public record ArmyRosterSnapshotPayload(
        long stateRevision,
        int acknowledgementActionId,
        byte acknowledgementAction,
        int acknowledgementResult,
        int acknowledgementAffected,
        int settlementCount,
        int recruitCount,
        int[] settlementInts,
        long[] settlementLongs,
        String[] settlementStrings,
        int[] recruitInts,
        long[] recruitLongs,
        String[] recruitStrings)
        implements CustomPacketPayload {
    public static final int SETTLEMENT_INT_COLUMNS = 3;
    public static final int SETTLEMENT_LONG_COLUMNS = 3;
    public static final int SETTLEMENT_STRING_COLUMNS = 2;
    public static final int SETTLEMENT_FACTION = 0;
    public static final int SETTLEMENT_POPULATION = 1;
    public static final int SETTLEMENT_AVAILABLE = 2;
    public static final int SETTLEMENT_UUID_MOST = 0;
    public static final int SETTLEMENT_UUID_LEAST = 1;
    public static final int SETTLEMENT_POSITION = 2;
    public static final int SETTLEMENT_NAME = 0;
    public static final int SETTLEMENT_CULTURE = 1;

    public static final int RECRUIT_INT_COLUMNS = 1;
    public static final int RECRUIT_LONG_COLUMNS = 4;
    public static final int RECRUIT_STRING_COLUMNS = 2;
    public static final int RECRUIT_STRENGTH = 0;
    public static final int RECRUIT_UUID_MOST = 0;
    public static final int RECRUIT_UUID_LEAST = 1;
    public static final int RECRUIT_VILLAGE_MOST = 2;
    public static final int RECRUIT_VILLAGE_LEAST = 3;
    public static final int RECRUIT_NAME = 0;
    public static final int RECRUIT_ROLE = 1;

    public static final int MAX_STRING_UTF8_BYTES = 160;
    public static final int MAX_TOTAL_UTF8_BYTES = 512 * 1024;

    public static final Type<ArmyRosterSnapshotPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            SarvarMillenaireArmies.MOD_ID, "roster_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ArmyRosterSnapshotPayload> STREAM_CODEC = StreamCodec.of(
            ArmyRosterSnapshotPayload::encode, ArmyRosterSnapshotPayload::decode);

    public ArmyRosterSnapshotPayload {
        if (stateRevision < 0 || acknowledgementActionId < 0 || acknowledgementAffected < 0) {
            throw new IllegalArgumentException("Roster snapshot header values must be non-negative");
        }
        if (acknowledgementAction < ArmiesProtocol.ACTION_NONE
                || acknowledgementAction > ArmiesProtocol.ACTION_ISSUE_ORDER
                || acknowledgementResult < ArmiesProtocol.RESULT_NONE
                || acknowledgementResult > ArmiesProtocol.RESULT_PARTIAL) {
            throw new IllegalArgumentException("Unknown roster acknowledgement code");
        }
        checkCount(settlementCount, ArmiesProtocol.MAX_CONTROLLED_SETTLEMENTS, "settlements");
        checkCount(recruitCount, ArmiesProtocol.MAX_AVAILABLE_RECRUITS, "recruits");
        checkLength(settlementInts, settlementCount, SETTLEMENT_INT_COLUMNS, "settlement ints");
        checkLength(settlementLongs, settlementCount, SETTLEMENT_LONG_COLUMNS, "settlement longs");
        checkLength(settlementStrings, settlementCount, SETTLEMENT_STRING_COLUMNS, "settlement strings");
        checkLength(recruitInts, recruitCount, RECRUIT_INT_COLUMNS, "recruit ints");
        checkLength(recruitLongs, recruitCount, RECRUIT_LONG_COLUMNS, "recruit longs");
        checkLength(recruitStrings, recruitCount, RECRUIT_STRING_COLUMNS, "recruit strings");
        validateStrings(settlementStrings, recruitStrings);
    }

    public int settlementInt(int row, int column) {
        checkCell(row, settlementCount, column, SETTLEMENT_INT_COLUMNS, "settlement");
        return settlementInts[row * SETTLEMENT_INT_COLUMNS + column];
    }

    public long settlementLong(int row, int column) {
        checkCell(row, settlementCount, column, SETTLEMENT_LONG_COLUMNS, "settlement");
        return settlementLongs[row * SETTLEMENT_LONG_COLUMNS + column];
    }

    public String settlementString(int row, int column) {
        checkCell(row, settlementCount, column, SETTLEMENT_STRING_COLUMNS, "settlement");
        return settlementStrings[row * SETTLEMENT_STRING_COLUMNS + column];
    }

    public int recruitInt(int row, int column) {
        checkCell(row, recruitCount, column, RECRUIT_INT_COLUMNS, "recruit");
        return recruitInts[row * RECRUIT_INT_COLUMNS + column];
    }

    public long recruitLong(int row, int column) {
        checkCell(row, recruitCount, column, RECRUIT_LONG_COLUMNS, "recruit");
        return recruitLongs[row * RECRUIT_LONG_COLUMNS + column];
    }

    public String recruitString(int row, int column) {
        checkCell(row, recruitCount, column, RECRUIT_STRING_COLUMNS, "recruit");
        return recruitStrings[row * RECRUIT_STRING_COLUMNS + column];
    }

    private static void encode(RegistryFriendlyByteBuf buffer, ArmyRosterSnapshotPayload payload) {
        BoundedCodecs.writeRevision(buffer, payload.stateRevision, "stateRevision");
        BoundedCodecs.writeCount(buffer, payload.acknowledgementActionId, Integer.MAX_VALUE, "ack action id");
        buffer.writeByte(payload.acknowledgementAction);
        BoundedCodecs.writeCount(buffer, payload.acknowledgementResult, ArmiesProtocol.RESULT_PARTIAL, "ack result");
        BoundedCodecs.writeCount(buffer, payload.acknowledgementAffected,
                ArmiesProtocol.MAX_RECRUITS_PER_INTENT, "ack affected");
        BoundedCodecs.writeCount(buffer, payload.settlementCount,
                ArmiesProtocol.MAX_CONTROLLED_SETTLEMENTS, "settlements");
        BoundedCodecs.writeCount(buffer, payload.recruitCount,
                ArmiesProtocol.MAX_AVAILABLE_RECRUITS, "recruits");
        BoundedCodecs.writeSignedInts(buffer, payload.settlementInts);
        BoundedCodecs.writeLongs(buffer, payload.settlementLongs);
        writeStrings(buffer, payload.settlementStrings, "settlement string");
        BoundedCodecs.writeSignedInts(buffer, payload.recruitInts);
        BoundedCodecs.writeLongs(buffer, payload.recruitLongs);
        writeStrings(buffer, payload.recruitStrings, "recruit string");
    }

    private static ArmyRosterSnapshotPayload decode(RegistryFriendlyByteBuf buffer) {
        long revision = BoundedCodecs.readRevision(buffer, "stateRevision");
        int actionId = BoundedCodecs.readCount(buffer, Integer.MAX_VALUE, "ack action id");
        byte action = buffer.readByte();
        int result = BoundedCodecs.readCount(buffer, ArmiesProtocol.RESULT_PARTIAL, "ack result");
        int affected = BoundedCodecs.readCount(
                buffer, ArmiesProtocol.MAX_RECRUITS_PER_INTENT, "ack affected");
        int settlements = BoundedCodecs.readCount(
                buffer, ArmiesProtocol.MAX_CONTROLLED_SETTLEMENTS, "settlements");
        int recruits = BoundedCodecs.readCount(
                buffer, ArmiesProtocol.MAX_AVAILABLE_RECRUITS, "recruits");
        return new ArmyRosterSnapshotPayload(
                revision,
                actionId,
                action,
                result,
                affected,
                settlements,
                recruits,
                BoundedCodecs.readSignedInts(buffer, settlements, SETTLEMENT_INT_COLUMNS),
                BoundedCodecs.readLongs(buffer, settlements, SETTLEMENT_LONG_COLUMNS),
                readStrings(buffer, settlements, SETTLEMENT_STRING_COLUMNS, "settlement string"),
                BoundedCodecs.readSignedInts(buffer, recruits, RECRUIT_INT_COLUMNS),
                BoundedCodecs.readLongs(buffer, recruits, RECRUIT_LONG_COLUMNS),
                readStrings(buffer, recruits, RECRUIT_STRING_COLUMNS, "recruit string"));
    }

    private static String[] readStrings(RegistryFriendlyByteBuf buffer, int rows, int stride, String field) {
        String[] values = new String[BoundedCodecs.checkedLength(rows, stride)];
        int total = 0;
        for (int index = 0; index < values.length; index++) {
            values[index] = BoundedCodecs.readUtf8(buffer, MAX_STRING_UTF8_BYTES, field + ' ' + index);
            total = checkedTotal(total, BoundedCodecs.utf8Length(values[index], MAX_STRING_UTF8_BYTES, field));
        }
        return values;
    }

    private static void writeStrings(RegistryFriendlyByteBuf buffer, String[] values, String field) {
        int total = 0;
        for (int index = 0; index < values.length; index++) {
            total = checkedTotal(total,
                    BoundedCodecs.writeUtf8(buffer, values[index], MAX_STRING_UTF8_BYTES, field + ' ' + index));
        }
    }

    private static void validateStrings(String[] first, String[] second) {
        int total = 0;
        for (String value : first) {
            total = checkedTotal(total, BoundedCodecs.utf8Length(value, MAX_STRING_UTF8_BYTES, "settlement string"));
        }
        for (String value : second) {
            total = checkedTotal(total, BoundedCodecs.utf8Length(value, MAX_STRING_UTF8_BYTES, "recruit string"));
        }
    }

    private static int checkedTotal(int current, int addition) {
        long total = (long) current + addition;
        if (total > MAX_TOTAL_UTF8_BYTES) {
            throw new EncoderException("Roster snapshot exceeds aggregate UTF-8 budget: " + total);
        }
        return (int) total;
    }

    private static void checkCount(int count, int maximum, String field) {
        if (count < 0 || count > maximum) {
            throw new IllegalArgumentException(field + " outside protocol bounds: " + count);
        }
    }

    private static void checkLength(Object array, int rows, int stride, String field) {
        int actual = array instanceof int[] ints ? ints.length
                : array instanceof long[] longs ? longs.length
                : array instanceof String[] strings ? strings.length : -1;
        if (actual != BoundedCodecs.checkedLength(rows, stride)) {
            throw new IllegalArgumentException(field + " length does not match row count");
        }
    }

    private static void checkCell(int row, int rows, int column, int columns, String field) {
        if (row < 0 || row >= rows || column < 0 || column >= columns) {
            throw new IllegalArgumentException("Unknown " + field + " cell " + row + ':' + column);
        }
    }

    @Override
    public Type<ArmyRosterSnapshotPayload> type() {
        return TYPE;
    }
}
