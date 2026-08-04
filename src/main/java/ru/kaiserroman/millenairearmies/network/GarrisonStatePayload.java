package ru.kaiserroman.millenairearmies.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenairearmies.SarvarMillenaireArmies;

/** Bounded visible-garrison projection sent alongside the ordinary army snapshot. */
public record GarrisonStatePayload(
        long armyRevision,
        int count,
        int[] ints,
        long[] longs,
        byte[] statuses,
        String[] settlementNames)
        implements CustomPacketPayload {
    public static final int INT_COLUMNS = 6;
    public static final int LONG_COLUMNS = 5;
    public static final int COLUMN_ARMY_HANDLE = 0;
    public static final int COLUMN_DIMENSION_ID = 1;
    public static final int COLUMN_GUARD_RADIUS = 2;
    public static final int COLUMN_SUPPLY = 3;
    public static final int COLUMN_READINESS = 4;
    public static final int COLUMN_MORALE = 5;
    public static final int LONG_VILLAGE_MOST = 0;
    public static final int LONG_VILLAGE_LEAST = 1;
    public static final int LONG_MUSTER_POSITION = 2;
    public static final int LONG_NEXT_UPKEEP_TICK = 3;
    public static final int LONG_REVISION = 4;
    public static final int MAX_NAME_UTF8_BYTES = 256;

    public static final Type<GarrisonStatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            SarvarMillenaireArmies.MOD_ID, "garrison_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, GarrisonStatePayload> STREAM_CODEC = StreamCodec.of(
            GarrisonStatePayload::encode, GarrisonStatePayload::decode);

    public GarrisonStatePayload {
        if (armyRevision < 0L || count < 0 || count > ArmiesProtocol.MAX_ARMIES_PER_SNAPSHOT) {
            throw new IllegalArgumentException("Invalid garrison snapshot header");
        }
        if (ints.length != count * INT_COLUMNS
                || longs.length != count * LONG_COLUMNS
                || statuses.length != count
                || settlementNames.length != count) {
            throw new IllegalArgumentException("Garrison snapshot column length mismatch");
        }
        for (int row = 0; row < count; row++) {
            int base = row * INT_COLUMNS;
            if (ints[base + COLUMN_ARMY_HANDLE] == 0
                    || ints[base + COLUMN_DIMENSION_ID] < 0
                    || ints[base + COLUMN_GUARD_RADIUS] <= 0
                    || !percent(ints[base + COLUMN_SUPPLY])
                    || !percent(ints[base + COLUMN_READINESS])
                    || !percent(ints[base + COLUMN_MORALE])
                    || statuses[row] < 0 || statuses[row] > 2
                    || settlementNames[row] == null) {
                throw new IllegalArgumentException("Invalid garrison snapshot row " + row);
            }
        }
    }

    public int intValue(int row, int column) {
        return ints[row * INT_COLUMNS + column];
    }

    public long longValue(int row, int column) {
        return longs[row * LONG_COLUMNS + column];
    }

    private static void encode(RegistryFriendlyByteBuf buffer, GarrisonStatePayload payload) {
        BoundedCodecs.writeRevision(buffer, payload.armyRevision, "armyRevision");
        BoundedCodecs.writeCount(buffer, payload.count, ArmiesProtocol.MAX_ARMIES_PER_SNAPSHOT, "count");
        for (int value : payload.ints) buffer.writeVarInt(value);
        for (long value : payload.longs) buffer.writeLong(value);
        buffer.writeBytes(payload.statuses);
        for (String name : payload.settlementNames) {
            BoundedCodecs.writeUtf8(buffer, name, MAX_NAME_UTF8_BYTES, "settlementName");
        }
    }

    private static GarrisonStatePayload decode(RegistryFriendlyByteBuf buffer) {
        long revision = BoundedCodecs.readRevision(buffer, "armyRevision");
        int count = BoundedCodecs.readCount(buffer, ArmiesProtocol.MAX_ARMIES_PER_SNAPSHOT, "count");
        int[] ints = new int[count * INT_COLUMNS];
        long[] longs = new long[count * LONG_COLUMNS];
        byte[] statuses = new byte[count];
        String[] names = new String[count];
        for (int index = 0; index < ints.length; index++) ints[index] = buffer.readVarInt();
        for (int index = 0; index < longs.length; index++) longs[index] = buffer.readLong();
        buffer.readBytes(statuses);
        for (int row = 0; row < count; row++) {
            names[row] = BoundedCodecs.readUtf8(buffer, MAX_NAME_UTF8_BYTES, "settlementName");
        }
        return new GarrisonStatePayload(revision, count, ints, longs, statuses, names);
    }

    private static boolean percent(int value) {
        return value >= 0 && value <= 100;
    }

    @Override
    public Type<GarrisonStatePayload> type() {
        return TYPE;
    }
}
