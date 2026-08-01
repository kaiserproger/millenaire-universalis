package ru.kaiserroman.millenairearmies.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenairearmies.SarvarMillenaireArmies;

/** An army order proposal. Dimension, ownership, visibility and authority are server-derived. */
public record IssueOrderIntent(
        int actionId,
        int armyHandle,
        byte orderType,
        ResourceLocation targetDimension,
        long primaryPosition,
        long secondaryPosition,
        int subjectEntityId,
        long expectedRevision,
        byte flags)
        implements CustomPacketPayload {
    public static final Type<IssueOrderIntent> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            SarvarMillenaireArmies.MOD_ID, "issue_order"));
    public static final StreamCodec<RegistryFriendlyByteBuf, IssueOrderIntent> STREAM_CODEC = StreamCodec.of(
            IssueOrderIntent::encode, IssueOrderIntent::decode);

    public IssueOrderIntent {
        if (actionId < 0) {
            throw new IllegalArgumentException("Action id must be non-negative");
        }
        if (!ArmiesProtocol.validStrategicOrder(orderType)) {
            throw new IllegalArgumentException("Unknown army order: " + orderType);
        }
        if (targetDimension == null) {
            throw new IllegalArgumentException("Target dimension must not be null");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Expected revision must be non-negative");
        }
        if ((flags & ~ArmiesProtocol.ORDER_FLAGS) != 0) {
            throw new IllegalArgumentException("Unknown order flags: " + flags);
        }
    }

    private static void encode(RegistryFriendlyByteBuf buffer, IssueOrderIntent payload) {
        BoundedCodecs.writeCount(buffer, payload.actionId, Integer.MAX_VALUE, "actionId");
        buffer.writeVarInt(payload.armyHandle);
        buffer.writeByte(payload.orderType);
        BoundedCodecs.writeUtf8(buffer, payload.targetDimension.toString(), 128, "targetDimension");
        buffer.writeByte(payload.flags);
        buffer.writeLong(payload.primaryPosition);
        if ((payload.flags & ArmiesProtocol.ORDER_FLAG_SECONDARY_POSITION) != 0) {
            buffer.writeLong(payload.secondaryPosition);
        }
        if ((payload.flags & ArmiesProtocol.ORDER_FLAG_SUBJECT_ENTITY) != 0) {
            buffer.writeVarInt(payload.subjectEntityId);
        }
        BoundedCodecs.writeRevision(buffer, payload.expectedRevision, "expectedRevision");
    }

    private static IssueOrderIntent decode(RegistryFriendlyByteBuf buffer) {
        int actionId = BoundedCodecs.readCount(buffer, Integer.MAX_VALUE, "actionId");
        int armyHandle = buffer.readVarInt();
        byte orderType = buffer.readByte();
        ResourceLocation targetDimension = ResourceLocation.tryParse(
                BoundedCodecs.readUtf8(buffer, 128, "targetDimension"));
        if (targetDimension == null) {
            throw new IllegalArgumentException("Invalid target dimension");
        }
        byte flags = buffer.readByte();
        long primaryPosition = buffer.readLong();
        long secondaryPosition = (flags & ArmiesProtocol.ORDER_FLAG_SECONDARY_POSITION) != 0
                ? buffer.readLong()
                : 0L;
        int subjectEntityId = (flags & ArmiesProtocol.ORDER_FLAG_SUBJECT_ENTITY) != 0
                ? buffer.readVarInt()
                : -1;
        long expectedRevision = BoundedCodecs.readRevision(buffer, "expectedRevision");
        return new IssueOrderIntent(
                actionId,
                armyHandle,
                orderType,
                targetDimension,
                primaryPosition,
                secondaryPosition,
                subjectEntityId,
                expectedRevision,
                flags);
    }

    @Override
    public Type<IssueOrderIntent> type() {
        return TYPE;
    }
}
