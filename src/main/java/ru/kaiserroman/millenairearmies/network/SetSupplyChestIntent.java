package ru.kaiserroman.millenairearmies.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenairearmies.UniversalisIds;

/** Selects or clears the concrete container used by one army for food and ammunition. */
public record SetSupplyChestIntent(
        int actionId,
        byte operation,
        int armyHandle,
        ResourceLocation dimension,
        long chestPosition,
        long expectedRevision)
        implements CustomPacketPayload {
    public static final byte OP_SET = 1;
    public static final byte OP_CLEAR = 2;
    public static final Type<SetSupplyChestIntent> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            UniversalisIds.MOD_ID, "set_supply_chest"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetSupplyChestIntent> STREAM_CODEC = StreamCodec.of(
            SetSupplyChestIntent::encode, SetSupplyChestIntent::decode);

    public SetSupplyChestIntent {
        if (actionId < 0 || expectedRevision < 0L || dimension == null
                || operation != OP_SET && operation != OP_CLEAR) {
            throw new IllegalArgumentException("Supply chest intent is outside protocol bounds");
        }
    }

    public static SetSupplyChestIntent clear(
            int actionId, int armyHandle, ResourceLocation dimension, long expectedRevision) {
        return new SetSupplyChestIntent(actionId, OP_CLEAR, armyHandle, dimension, 0L, expectedRevision);
    }

    private static void encode(RegistryFriendlyByteBuf buffer, SetSupplyChestIntent payload) {
        BoundedCodecs.writeCount(buffer, payload.actionId, Integer.MAX_VALUE, "actionId");
        buffer.writeByte(payload.operation);
        buffer.writeVarInt(payload.armyHandle);
        BoundedCodecs.writeUtf8(buffer, payload.dimension.toString(), 128, "dimension");
        buffer.writeLong(payload.chestPosition);
        BoundedCodecs.writeRevision(buffer, payload.expectedRevision, "expectedRevision");
    }

    private static SetSupplyChestIntent decode(RegistryFriendlyByteBuf buffer) {
        int actionId = BoundedCodecs.readCount(buffer, Integer.MAX_VALUE, "actionId");
        byte operation = buffer.readByte();
        int armyHandle = buffer.readVarInt();
        ResourceLocation dimension = ResourceLocation.tryParse(
                BoundedCodecs.readUtf8(buffer, 128, "dimension"));
        if (dimension == null) throw new IllegalArgumentException("Invalid supply dimension");
        return new SetSupplyChestIntent(
                actionId,
                operation,
                armyHandle,
                dimension,
                buffer.readLong(),
                BoundedCodecs.readRevision(buffer, "expectedRevision"));
    }

    @Override public Type<SetSupplyChestIntent> type() { return TYPE; }
}
