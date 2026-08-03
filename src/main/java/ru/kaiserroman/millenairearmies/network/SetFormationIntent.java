package ru.kaiserroman.millenairearmies.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenairearmies.SarvarMillenaireArmies;
import ru.kaiserroman.millenairearmies.model.ArmyFormation;

/** Server-authoritative formation change for one controlled army. */
public record SetFormationIntent(
        int actionId,
        int armyHandle,
        byte formationCode,
        long expectedRevision)
        implements CustomPacketPayload {
    public static final Type<SetFormationIntent> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            SarvarMillenaireArmies.MOD_ID, "set_formation"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetFormationIntent> STREAM_CODEC = StreamCodec.of(
            SetFormationIntent::encode, SetFormationIntent::decode);

    public SetFormationIntent {
        if (actionId < 0 || expectedRevision < 0L
                || !ArmyFormation.isValidCode(Byte.toUnsignedInt(formationCode))) {
            throw new IllegalArgumentException("Formation intent is outside protocol bounds");
        }
    }

    private static void encode(RegistryFriendlyByteBuf buffer, SetFormationIntent payload) {
        BoundedCodecs.writeCount(buffer, payload.actionId, Integer.MAX_VALUE, "actionId");
        buffer.writeVarInt(payload.armyHandle);
        buffer.writeByte(payload.formationCode);
        BoundedCodecs.writeRevision(buffer, payload.expectedRevision, "expectedRevision");
    }

    private static SetFormationIntent decode(RegistryFriendlyByteBuf buffer) {
        return new SetFormationIntent(
                BoundedCodecs.readCount(buffer, Integer.MAX_VALUE, "actionId"),
                buffer.readVarInt(),
                buffer.readByte(),
                BoundedCodecs.readRevision(buffer, "expectedRevision"));
    }

    @Override
    public Type<SetFormationIntent> type() {
        return TYPE;
    }
}
