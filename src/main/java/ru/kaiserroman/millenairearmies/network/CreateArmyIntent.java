package ru.kaiserroman.millenairearmies.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenairearmies.SarvarMillenaireArmies;

/** Server-authorized intent to raise an army from a Millenaire village. */
public record CreateArmyIntent(
        int actionId,
        int factionId,
        long homeVillageUuidMost,
        long homeVillageUuidLeast,
        long homeVillagePosition,
        int templateKeyId,
        int desiredUnits,
        long expectedRevision,
        byte flags)
        implements CustomPacketPayload {
    public static final Type<CreateArmyIntent> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            SarvarMillenaireArmies.MOD_ID, "create_army"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CreateArmyIntent> STREAM_CODEC = StreamCodec.of(
            CreateArmyIntent::encode, CreateArmyIntent::decode);

    public CreateArmyIntent {
        if (actionId < 0 || factionId < 0 || templateKeyId < 0) {
            throw new IllegalArgumentException("Action, faction and template ids must be non-negative");
        }
        if (desiredUnits <= 0 || desiredUnits > ArmiesProtocol.MAX_CREATE_UNITS) {
            throw new IllegalArgumentException("Desired units outside protocol bounds: " + desiredUnits);
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Expected revision must be non-negative");
        }
    }

    private static void encode(RegistryFriendlyByteBuf buffer, CreateArmyIntent payload) {
        BoundedCodecs.writeCount(buffer, payload.actionId, Integer.MAX_VALUE, "actionId");
        BoundedCodecs.writeCount(buffer, payload.factionId, Integer.MAX_VALUE, "factionId");
        buffer.writeLong(payload.homeVillageUuidMost);
        buffer.writeLong(payload.homeVillageUuidLeast);
        buffer.writeLong(payload.homeVillagePosition);
        BoundedCodecs.writeCount(buffer, payload.templateKeyId, Integer.MAX_VALUE, "templateKeyId");
        BoundedCodecs.writeCount(buffer, payload.desiredUnits, ArmiesProtocol.MAX_CREATE_UNITS, "desiredUnits");
        BoundedCodecs.writeRevision(buffer, payload.expectedRevision, "expectedRevision");
        buffer.writeByte(payload.flags);
    }

    private static CreateArmyIntent decode(RegistryFriendlyByteBuf buffer) {
        return new CreateArmyIntent(
                BoundedCodecs.readCount(buffer, Integer.MAX_VALUE, "actionId"),
                BoundedCodecs.readCount(buffer, Integer.MAX_VALUE, "factionId"),
                buffer.readLong(),
                buffer.readLong(),
                buffer.readLong(),
                BoundedCodecs.readCount(buffer, Integer.MAX_VALUE, "templateKeyId"),
                BoundedCodecs.readCount(buffer, ArmiesProtocol.MAX_CREATE_UNITS, "desiredUnits"),
                BoundedCodecs.readRevision(buffer, "expectedRevision"),
                buffer.readByte());
    }

    @Override
    public Type<CreateArmyIntent> type() {
        return TYPE;
    }
}
