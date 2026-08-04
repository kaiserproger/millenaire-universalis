package ru.kaiserroman.millenairearmies.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.SarvarMillenaireArmies;

/** Server-authorized proposal to assign/change or clear an army's settlement garrison. */
public record SetGarrisonIntent(
        int actionId,
        byte operation,
        int armyHandle,
        long villageUuidMost,
        long villageUuidLeast,
        ResourceLocation targetDimension,
        long musterPosition,
        int guardRadius,
        long expectedRevision)
        implements CustomPacketPayload {
    public static final byte OP_SET = 1;
    public static final byte OP_CLEAR = 2;

    public static final Type<SetGarrisonIntent> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            SarvarMillenaireArmies.MOD_ID, "set_garrison"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetGarrisonIntent> STREAM_CODEC = StreamCodec.of(
            SetGarrisonIntent::encode, SetGarrisonIntent::decode);

    public SetGarrisonIntent {
        if (actionId < 0) {
            throw new IllegalArgumentException("Action id must be non-negative");
        }
        if (operation != OP_SET && operation != OP_CLEAR) {
            throw new IllegalArgumentException("Unknown garrison operation " + operation);
        }
        if (armyHandle == 0) {
            throw new IllegalArgumentException("Zero is not a valid army handle");
        }
        if (expectedRevision < 0L) {
            throw new IllegalArgumentException("Expected revision must be non-negative");
        }
        if (operation == OP_SET) {
            if (targetDimension == null) {
                throw new IllegalArgumentException("Garrison target dimension is absent");
            }
            if (guardRadius < ArmiesConfig.GARRISON_MIN_RADIUS
                    || guardRadius > ArmiesConfig.GARRISON_MAX_RADIUS) {
                throw new IllegalArgumentException("Garrison radius outside configured bounds: " + guardRadius);
            }
        }
    }

    public static SetGarrisonIntent clear(int actionId, int armyHandle, long expectedRevision) {
        return new SetGarrisonIntent(
                actionId, OP_CLEAR, armyHandle, 0L, 0L,
                ResourceLocation.fromNamespaceAndPath("minecraft", "overworld"),
                0L, ArmiesConfig.GARRISON_DEFAULT_RADIUS, expectedRevision);
    }

    private static void encode(RegistryFriendlyByteBuf buffer, SetGarrisonIntent payload) {
        BoundedCodecs.writeCount(buffer, payload.actionId, Integer.MAX_VALUE, "actionId");
        buffer.writeByte(payload.operation);
        buffer.writeVarInt(payload.armyHandle);
        buffer.writeLong(payload.villageUuidMost);
        buffer.writeLong(payload.villageUuidLeast);
        BoundedCodecs.writeUtf8(buffer, payload.targetDimension.toString(), 128, "targetDimension");
        buffer.writeLong(payload.musterPosition);
        BoundedCodecs.writeCount(
                buffer, payload.guardRadius, ArmiesConfig.GARRISON_MAX_RADIUS, "guardRadius");
        BoundedCodecs.writeRevision(buffer, payload.expectedRevision, "expectedRevision");
    }

    private static SetGarrisonIntent decode(RegistryFriendlyByteBuf buffer) {
        int actionId = BoundedCodecs.readCount(buffer, Integer.MAX_VALUE, "actionId");
        byte operation = buffer.readByte();
        int armyHandle = buffer.readVarInt();
        long villageMost = buffer.readLong();
        long villageLeast = buffer.readLong();
        ResourceLocation targetDimension = ResourceLocation.tryParse(
                BoundedCodecs.readUtf8(buffer, 128, "targetDimension"));
        if (targetDimension == null) {
            throw new IllegalArgumentException("Invalid garrison target dimension");
        }
        long musterPosition = buffer.readLong();
        int guardRadius = BoundedCodecs.readCount(
                buffer, ArmiesConfig.GARRISON_MAX_RADIUS, "guardRadius");
        long expectedRevision = BoundedCodecs.readRevision(buffer, "expectedRevision");
        return new SetGarrisonIntent(
                actionId,
                operation,
                armyHandle,
                villageMost,
                villageLeast,
                targetDimension,
                musterPosition,
                guardRadius,
                expectedRevision);
    }

    @Override
    public Type<SetGarrisonIntent> type() {
        return TYPE;
    }
}
