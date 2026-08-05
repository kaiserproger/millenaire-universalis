package ru.kaiserroman.millenairearmies.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenairearmies.UniversalisIds;

/** Sparse, revision-checked replacement/removal rows following the snapshot row schema. */
public record ArmyStateDeltaPayload(
        long baseRevision,
        long revision,
        byte[] kinds,
        byte[] operations,
        int[] intColumns,
        long[] longColumns,
        byte[] byteColumns)
        implements CustomPacketPayload {
    public static final Type<ArmyStateDeltaPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            UniversalisIds.MOD_ID, "state_delta"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ArmyStateDeltaPayload> STREAM_CODEC = StreamCodec.of(
            ArmyStateDeltaPayload::encode, ArmyStateDeltaPayload::decode);

    public ArmyStateDeltaPayload {
        if (baseRevision < 0 || revision <= baseRevision) {
            throw new IllegalArgumentException("Delta revision must advance its non-negative base");
        }
        if (kinds == null || operations == null || intColumns == null || longColumns == null || byteColumns == null) {
            throw new IllegalArgumentException("Delta primitive columns must not be null");
        }
        int rows = kinds.length;
        if (rows > ArmiesProtocol.MAX_DELTA_ROWS || operations.length != rows
                || intColumns.length != BoundedCodecs.checkedLength(rows, ArmiesProtocol.INT_COLUMNS)
                || longColumns.length != BoundedCodecs.checkedLength(rows, ArmiesProtocol.LONG_COLUMNS)
                || byteColumns.length != BoundedCodecs.checkedLength(rows, ArmiesProtocol.BYTE_COLUMNS)) {
            throw new IllegalArgumentException("Delta primitive column lengths do not match bounded row count");
        }
        for (int row = 0; row < rows; row++) {
            if (!ArmiesProtocol.validKind(kinds[row])) {
                throw new IllegalArgumentException("Unknown delta row kind: " + kinds[row]);
            }
            byte operation = operations[row];
            if (operation != ArmiesProtocol.DELTA_REMOVE && operation != ArmiesProtocol.DELTA_UPSERT) {
                throw new IllegalArgumentException("Unknown delta operation: " + operation);
            }
        }
    }

    public int rowCount() {
        return kinds.length;
    }

    public byte touchedSections() {
        byte result = 0;
        for (byte kind : kinds) {
            result |= ArmiesProtocol.sectionForKind(kind);
        }
        return result;
    }

    private static void encode(RegistryFriendlyByteBuf buffer, ArmyStateDeltaPayload payload) {
        BoundedCodecs.writeRevision(buffer, payload.baseRevision, "baseRevision");
        BoundedCodecs.writeRevision(buffer, payload.revision, "revision");
        BoundedCodecs.writeCount(buffer, payload.rowCount(), ArmiesProtocol.MAX_DELTA_ROWS, "deltaRows");
        buffer.writeBytes(payload.kinds);
        buffer.writeBytes(payload.operations);
        BoundedCodecs.writeRawVarInts(buffer, payload.intColumns);
        BoundedCodecs.writeLongs(buffer, payload.longColumns);
        BoundedCodecs.writeBytes(buffer, payload.byteColumns);
    }

    private static ArmyStateDeltaPayload decode(RegistryFriendlyByteBuf buffer) {
        long baseRevision = BoundedCodecs.readRevision(buffer, "baseRevision");
        long revision = BoundedCodecs.readRevision(buffer, "revision");
        int rows = BoundedCodecs.readCount(buffer, ArmiesProtocol.MAX_DELTA_ROWS, "deltaRows");
        return new ArmyStateDeltaPayload(
                baseRevision,
                revision,
                BoundedCodecs.readBytes(buffer, rows, 1),
                BoundedCodecs.readBytes(buffer, rows, 1),
                BoundedCodecs.readRawVarInts(buffer, rows, ArmiesProtocol.INT_COLUMNS),
                BoundedCodecs.readLongs(buffer, rows, ArmiesProtocol.LONG_COLUMNS),
                BoundedCodecs.readBytes(buffer, rows, ArmiesProtocol.BYTE_COLUMNS));
    }

    @Override
    public Type<ArmyStateDeltaPayload> type() {
        return TYPE;
    }
}
