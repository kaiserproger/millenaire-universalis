package ru.kaiserroman.millenairearmies.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenairearmies.UniversalisIds;

/** Server-authoritative shield-wall or fire-at-will policy change for one controlled army. */
public record SetTacticalIntent(
        int actionId,
        int armyHandle,
        byte tacticalCode,
        boolean enabled,
        long expectedRevision)
        implements CustomPacketPayload {
    public static final Type<SetTacticalIntent> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            UniversalisIds.MOD_ID, "set_tactical"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetTacticalIntent> STREAM_CODEC = StreamCodec.of(
            SetTacticalIntent::encode, SetTacticalIntent::decode);

    public SetTacticalIntent {
        if (actionId < 0 || expectedRevision < 0L || !ArmiesProtocol.validTacticalCode(tacticalCode)) {
            throw new IllegalArgumentException("Tactical intent is outside protocol bounds");
        }
    }

    private static void encode(RegistryFriendlyByteBuf buffer, SetTacticalIntent payload) {
        BoundedCodecs.writeCount(buffer, payload.actionId, Integer.MAX_VALUE, "actionId");
        buffer.writeVarInt(payload.armyHandle);
        buffer.writeByte(payload.tacticalCode);
        buffer.writeBoolean(payload.enabled);
        BoundedCodecs.writeRevision(buffer, payload.expectedRevision, "expectedRevision");
    }

    private static SetTacticalIntent decode(RegistryFriendlyByteBuf buffer) {
        return new SetTacticalIntent(
                BoundedCodecs.readCount(buffer, Integer.MAX_VALUE, "actionId"),
                buffer.readVarInt(),
                buffer.readByte(),
                buffer.readBoolean(),
                BoundedCodecs.readRevision(buffer, "expectedRevision"));
    }

    @Override
    public Type<SetTacticalIntent> type() {
        return TYPE;
    }
}
