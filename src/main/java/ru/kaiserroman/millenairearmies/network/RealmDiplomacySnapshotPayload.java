package ru.kaiserroman.millenairearmies.network;

import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenaire.realm.DiplomaticStatus;
import ru.kaiserroman.millenaire.realm.WarGoal;
import ru.kaiserroman.millenairearmies.SarvarMillenaireArmies;

/** Bounded directed canonical Realm relations visible to one player Realm. */
public record RealmDiplomacySnapshotPayload(
        long realmRevision,
        long realmId,
        int count,
        long[] otherRealmIds,
        int[] intColumns,
        byte[] byteColumns,
        String[] realmNames)
        implements CustomPacketPayload {
    public static final int INT_COLUMNS = 4;
    public static final int BYTE_COLUMNS = 2;
    public static final int COLUMN_WAR_SCORE = 0;
    public static final int COLUMN_EXHAUSTION = 1;
    public static final int COLUMN_GRIEVANCES = 2;
    public static final int COLUMN_TRUST = 3;
    public static final int BYTE_STATUS = 0;
    public static final int BYTE_WAR_GOAL = 1;
    public static final int MAX_NAME_UTF8_BYTES = 160;
    public static final int MAX_TOTAL_UTF8_BYTES = 32 * 1024;

    public static final Type<RealmDiplomacySnapshotPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(
                    SarvarMillenaireArmies.MOD_ID, "realm_diplomacy_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RealmDiplomacySnapshotPayload> STREAM_CODEC =
            StreamCodec.of(
                    RealmDiplomacySnapshotPayload::encode,
                    RealmDiplomacySnapshotPayload::decode);

    public RealmDiplomacySnapshotPayload {
        if (realmRevision < 0L || realmId < 0L) {
            throw new IllegalArgumentException("Realm diplomacy identity must be non-negative");
        }
        if (count < 0 || count > ArmiesProtocol.MAX_REALM_RELATIONS_PER_SNAPSHOT) {
            throw new IllegalArgumentException("Invalid Realm diplomacy row count: " + count);
        }
        if (realmId == 0L && count != 0) {
            throw new IllegalArgumentException("Relations require a non-zero player Realm id");
        }
        if (otherRealmIds == null
                || intColumns == null
                || byteColumns == null
                || realmNames == null
                || otherRealmIds.length != count
                || intColumns.length != BoundedCodecs.checkedLength(count, INT_COLUMNS)
                || byteColumns.length != BoundedCodecs.checkedLength(count, BYTE_COLUMNS)
                || realmNames.length != count) {
            throw new IllegalArgumentException("Realm diplomacy columns do not match row count");
        }
        int utf8Bytes = 0;
        for (int row = 0; row < count; row++) {
            long other = otherRealmIds[row];
            if (other <= 0L || other == realmId) {
                throw new IllegalArgumentException("Invalid other Realm id at row " + row);
            }
            int baseInt = row * INT_COLUMNS;
            requireIndex(intColumns[baseInt + COLUMN_EXHAUSTION], "exhaustion", row);
            requireIndex(intColumns[baseInt + COLUMN_GRIEVANCES], "grievances", row);
            requireIndex(intColumns[baseInt + COLUMN_TRUST], "trust", row);
            int baseByte = row * BYTE_COLUMNS;
            int status = Byte.toUnsignedInt(byteColumns[baseByte + BYTE_STATUS]);
            int goal = Byte.toUnsignedInt(byteColumns[baseByte + BYTE_WAR_GOAL]);
            if (status >= DiplomaticStatus.values().length || goal >= WarGoal.values().length) {
                throw new IllegalArgumentException("Invalid Realm diplomacy enum at row " + row);
            }
            utf8Bytes = checkedUtf8Total(
                    utf8Bytes,
                    BoundedCodecs.utf8Length(
                            realmNames[row], MAX_NAME_UTF8_BYTES, "Realm name " + row));
        }
    }

    public int intValue(int row, int column) {
        checkRow(row);
        if (column < 0 || column >= INT_COLUMNS) {
            throw new IllegalArgumentException("Unknown Realm diplomacy int column " + column);
        }
        return intColumns[row * INT_COLUMNS + column];
    }

    public byte byteValue(int row, int column) {
        checkRow(row);
        if (column < 0 || column >= BYTE_COLUMNS) {
            throw new IllegalArgumentException("Unknown Realm diplomacy byte column " + column);
        }
        return byteColumns[row * BYTE_COLUMNS + column];
    }

    private static void encode(
            RegistryFriendlyByteBuf buffer,
            RealmDiplomacySnapshotPayload payload) {
        BoundedCodecs.writeRevision(buffer, payload.realmRevision, "Realm revision");
        buffer.writeVarLong(payload.realmId);
        BoundedCodecs.writeCount(
                buffer,
                payload.count,
                ArmiesProtocol.MAX_REALM_RELATIONS_PER_SNAPSHOT,
                "Realm diplomacy rows");
        BoundedCodecs.writeLongs(buffer, payload.otherRealmIds);
        BoundedCodecs.writeSignedInts(buffer, payload.intColumns);
        BoundedCodecs.writeBytes(buffer, payload.byteColumns);
        int utf8Bytes = 0;
        for (int row = 0; row < payload.count; row++) {
            utf8Bytes = checkedEncodeTotal(
                    utf8Bytes,
                    BoundedCodecs.writeUtf8(
                            buffer,
                            payload.realmNames[row],
                            MAX_NAME_UTF8_BYTES,
                            "Realm name " + row));
        }
    }

    private static RealmDiplomacySnapshotPayload decode(RegistryFriendlyByteBuf buffer) {
        long revision = BoundedCodecs.readRevision(buffer, "Realm revision");
        long realmId = buffer.readVarLong();
        if (realmId < 0L) throw new DecoderException("Realm id must be non-negative");
        int count = BoundedCodecs.readCount(
                buffer,
                ArmiesProtocol.MAX_REALM_RELATIONS_PER_SNAPSHOT,
                "Realm diplomacy rows");
        long[] otherRealms = BoundedCodecs.readLongs(buffer, count, 1);
        int[] ints = BoundedCodecs.readSignedInts(buffer, count, INT_COLUMNS);
        byte[] bytes = BoundedCodecs.readBytes(buffer, count, BYTE_COLUMNS);
        String[] names = new String[count];
        int utf8Bytes = 0;
        for (int row = 0; row < count; row++) {
            String name = BoundedCodecs.readUtf8(
                    buffer, MAX_NAME_UTF8_BYTES, "Realm name " + row);
            utf8Bytes = checkedDecodeTotal(
                    utf8Bytes,
                    BoundedCodecs.utf8Length(name, MAX_NAME_UTF8_BYTES, "Realm name " + row));
            names[row] = name;
        }
        return new RealmDiplomacySnapshotPayload(
                revision, realmId, count, otherRealms, ints, bytes, names);
    }

    private static void requireIndex(int value, String field, int row) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(field + " outside 0..1000 at row " + row);
        }
    }

    private static int checkedUtf8Total(int current, int addition) {
        long total = (long) current + addition;
        if (total > MAX_TOTAL_UTF8_BYTES) {
            throw new IllegalArgumentException(
                    "Realm diplomacy names exceed aggregate UTF-8 budget: " + total);
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
            throw new IllegalArgumentException("Unknown Realm diplomacy row " + row);
        }
    }

    @Override
    public Type<RealmDiplomacySnapshotPayload> type() {
        return TYPE;
    }
}
