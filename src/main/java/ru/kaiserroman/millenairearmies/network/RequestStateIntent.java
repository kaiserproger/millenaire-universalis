package ru.kaiserroman.millenairearmies.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenairearmies.UniversalisIds;

/** Bounded, pageable request for an authorized state projection. */
public record RequestStateIntent(
        byte sectionMask, byte scope, int scopeHandle, int cursor, long knownRevision)
        implements CustomPacketPayload {
    public static final Type<RequestStateIntent> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            UniversalisIds.MOD_ID, "request_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RequestStateIntent> STREAM_CODEC = StreamCodec.of(
            RequestStateIntent::encode, RequestStateIntent::decode);

    public RequestStateIntent {
        if (!ArmiesProtocol.validSectionMask(sectionMask)) {
            throw new IllegalArgumentException("Invalid state section mask: " + sectionMask);
        }
        if (scope < ArmiesProtocol.SCOPE_GLOBAL || scope > ArmiesProtocol.SCOPE_ARMY) {
            throw new IllegalArgumentException("Unknown state scope: " + scope);
        }
        if (cursor < 0 || cursor > ArmiesProtocol.MAX_REQUEST_CURSOR) {
            throw new IllegalArgumentException("Request cursor outside protocol bounds: " + cursor);
        }
        if (knownRevision < 0) {
            throw new IllegalArgumentException("Known revision must be non-negative");
        }
    }

    private static void encode(RegistryFriendlyByteBuf buffer, RequestStateIntent payload) {
        buffer.writeByte(payload.sectionMask);
        buffer.writeByte(payload.scope);
        buffer.writeVarInt(payload.scopeHandle);
        BoundedCodecs.writeCount(buffer, payload.cursor, ArmiesProtocol.MAX_REQUEST_CURSOR, "cursor");
        BoundedCodecs.writeRevision(buffer, payload.knownRevision, "knownRevision");
    }

    private static RequestStateIntent decode(RegistryFriendlyByteBuf buffer) {
        return new RequestStateIntent(
                buffer.readByte(),
                buffer.readByte(),
                buffer.readVarInt(),
                BoundedCodecs.readCount(buffer, ArmiesProtocol.MAX_REQUEST_CURSOR, "cursor"),
                BoundedCodecs.readRevision(buffer, "knownRevision"));
    }

    @Override
    public Type<RequestStateIntent> type() {
        return TYPE;
    }
}
