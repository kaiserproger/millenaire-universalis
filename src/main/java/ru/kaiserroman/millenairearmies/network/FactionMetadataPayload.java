package ru.kaiserroman.millenairearmies.network;

import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenairearmies.UniversalisIds;

/**
 * Bounded, visibility-filtered presentation metadata for faction rows.
 *
 * <p>Rows contain four primitive metrics ({@code faction, settlements, population, influence}),
 * the packed capital position, and three UTF-8 strings ({@code culture id, display name, capital
 * name}). Strings are materialized only while a snapshot is encoded/decoded. The aggregate wire
 * budget prevents a datapack-provided name from turning a strategic sync into an unbounded packet.
 */
public record FactionMetadataPayload(
        long stateRevision,
        long projectionRevision,
        int count,
        int[] intColumns,
        long[] capitalPositions,
        String[] stringColumns)
        implements CustomPacketPayload {
    public static final int INT_COLUMNS = 4;
    public static final int STRING_COLUMNS = 3;
    public static final int COLUMN_FACTION_ID = 0;
    public static final int COLUMN_SETTLEMENTS = 1;
    public static final int COLUMN_POPULATION = 2;
    public static final int COLUMN_INFLUENCE = 3;
    public static final int STRING_CULTURE_ID = 0;
    public static final int STRING_DISPLAY_NAME = 1;
    public static final int STRING_CAPITAL_NAME = 2;
    public static final int MAX_STRING_UTF8_BYTES = 160;
    public static final int MAX_TOTAL_UTF8_BYTES = 256 * 1024;

    public static final Type<FactionMetadataPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            UniversalisIds.MOD_ID, "faction_metadata"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FactionMetadataPayload> STREAM_CODEC = StreamCodec.of(
            FactionMetadataPayload::encode, FactionMetadataPayload::decode);

    public FactionMetadataPayload {
        if (stateRevision < 0 || projectionRevision < 0) {
            throw new IllegalArgumentException("Metadata revisions must be non-negative");
        }
        if (count < 0 || count > ArmiesProtocol.MAX_FACTIONS_PER_SNAPSHOT) {
            throw new IllegalArgumentException("Invalid faction metadata row count: " + count);
        }
        if (intColumns == null
                || capitalPositions == null
                || stringColumns == null
                || intColumns.length != BoundedCodecs.checkedLength(count, INT_COLUMNS)
                || capitalPositions.length != count
                || stringColumns.length != BoundedCodecs.checkedLength(count, STRING_COLUMNS)) {
            throw new IllegalArgumentException("Faction metadata columns do not match row count");
        }
        int utf8Bytes = 0;
        for (int index = 0; index < stringColumns.length; index++) {
            utf8Bytes = checkedUtf8Total(
                    utf8Bytes,
                    BoundedCodecs.utf8Length(
                            stringColumns[index], MAX_STRING_UTF8_BYTES, "metadata string " + index));
        }
    }

    public int intValue(int row, int column) {
        checkRow(row);
        if (column < 0 || column >= INT_COLUMNS) {
            throw new IllegalArgumentException("Unknown metadata int column " + column);
        }
        return intColumns[row * INT_COLUMNS + column];
    }

    public String stringValue(int row, int column) {
        checkRow(row);
        if (column < 0 || column >= STRING_COLUMNS) {
            throw new IllegalArgumentException("Unknown metadata string column " + column);
        }
        return stringColumns[row * STRING_COLUMNS + column];
    }

    private static void encode(RegistryFriendlyByteBuf buffer, FactionMetadataPayload payload) {
        BoundedCodecs.writeRevision(buffer, payload.stateRevision, "state revision");
        BoundedCodecs.writeRevision(buffer, payload.projectionRevision, "projection revision");
        BoundedCodecs.writeCount(
                buffer, payload.count, ArmiesProtocol.MAX_FACTIONS_PER_SNAPSHOT, "faction metadata rows");
        BoundedCodecs.writeSignedInts(buffer, payload.intColumns);
        BoundedCodecs.writeLongs(buffer, payload.capitalPositions);
        int utf8Bytes = 0;
        for (int index = 0; index < payload.stringColumns.length; index++) {
            utf8Bytes = checkedEncodeTotal(
                    utf8Bytes,
                    BoundedCodecs.writeUtf8(
                            buffer,
                            payload.stringColumns[index],
                            MAX_STRING_UTF8_BYTES,
                            "metadata string " + index));
        }
    }

    private static FactionMetadataPayload decode(RegistryFriendlyByteBuf buffer) {
        long stateRevision = BoundedCodecs.readRevision(buffer, "state revision");
        long projectionRevision = BoundedCodecs.readRevision(buffer, "projection revision");
        int count = BoundedCodecs.readCount(
                buffer, ArmiesProtocol.MAX_FACTIONS_PER_SNAPSHOT, "faction metadata rows");
        int[] ints = BoundedCodecs.readSignedInts(buffer, count, INT_COLUMNS);
        long[] positions = BoundedCodecs.readLongs(buffer, count, 1);
        String[] strings = new String[BoundedCodecs.checkedLength(count, STRING_COLUMNS)];
        int utf8Bytes = 0;
        for (int index = 0; index < strings.length; index++) {
            String value = BoundedCodecs.readUtf8(
                    buffer, MAX_STRING_UTF8_BYTES, "metadata string " + index);
            utf8Bytes = checkedDecodeTotal(
                    utf8Bytes,
                    BoundedCodecs.utf8Length(value, MAX_STRING_UTF8_BYTES, "metadata string " + index));
            strings[index] = value;
        }
        return new FactionMetadataPayload(stateRevision, projectionRevision, count, ints, positions, strings);
    }

    private static int checkedUtf8Total(int current, int addition) {
        long total = (long) current + addition;
        if (total > MAX_TOTAL_UTF8_BYTES) {
            throw new IllegalArgumentException(
                    "Faction metadata exceeds aggregate UTF-8 budget: " + total);
        }
        return (int) total;
    }

    private static int checkedEncodeTotal(int current, int addition) {
        try {
            return checkedUtf8Total(current, addition);
        } catch (IllegalArgumentException exception) {
            throw new EncoderException(exception.getMessage(), exception);
        }
    }

    private static int checkedDecodeTotal(int current, int addition) {
        try {
            return checkedUtf8Total(current, addition);
        } catch (IllegalArgumentException exception) {
            throw new DecoderException(exception.getMessage(), exception);
        }
    }

    private void checkRow(int row) {
        if (row < 0 || row >= count) {
            throw new IllegalArgumentException("Unknown faction metadata row " + row);
        }
    }

    @Override
    public Type<FactionMetadataPayload> type() {
        return TYPE;
    }
}
