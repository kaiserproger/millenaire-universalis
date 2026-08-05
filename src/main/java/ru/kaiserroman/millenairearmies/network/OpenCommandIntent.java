package ru.kaiserroman.millenairearmies.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenairearmies.UniversalisIds;

/** Client request to open one of the strategic views; the server still decides whether it may. */
public record OpenCommandIntent(byte view, int contextHandle, long knownRevision)
        implements CustomPacketPayload {
    public static final Type<OpenCommandIntent> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            UniversalisIds.MOD_ID, "open_command"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenCommandIntent> STREAM_CODEC = StreamCodec.of(
            OpenCommandIntent::encode, OpenCommandIntent::decode);

    public OpenCommandIntent {
        if (view < ArmiesProtocol.VIEW_STRATEGIC || view > ArmiesProtocol.VIEW_LOGISTICS) {
            throw new IllegalArgumentException("Unknown strategic view: " + view);
        }
        if (knownRevision < 0) {
            throw new IllegalArgumentException("Known revision must be non-negative");
        }
    }

    private static void encode(RegistryFriendlyByteBuf buffer, OpenCommandIntent payload) {
        buffer.writeByte(payload.view);
        buffer.writeVarInt(payload.contextHandle);
        BoundedCodecs.writeRevision(buffer, payload.knownRevision, "knownRevision");
    }

    private static OpenCommandIntent decode(RegistryFriendlyByteBuf buffer) {
        return new OpenCommandIntent(
                buffer.readByte(),
                buffer.readVarInt(),
                BoundedCodecs.readRevision(buffer, "knownRevision"));
    }

    @Override
    public Type<OpenCommandIntent> type() {
        return TYPE;
    }
}
