package ru.kaiserroman.millenairearmies.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenairearmies.UniversalisIds;

/** Bounded request to assign selected, server-projected Millenaire residents to an army. */
public record RecruitUnitsIntent(
        int actionId,
        int armyHandle,
        long villageUuidMost,
        long villageUuidLeast,
        long expectedRevision,
        int count,
        long[] villagerUuidBits)
        implements CustomPacketPayload {
    public static final Type<RecruitUnitsIntent> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            UniversalisIds.MOD_ID, "recruit_units"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RecruitUnitsIntent> STREAM_CODEC = StreamCodec.of(
            RecruitUnitsIntent::encode, RecruitUnitsIntent::decode);

    public RecruitUnitsIntent {
        if (actionId < 0 || expectedRevision < 0) {
            throw new IllegalArgumentException("Recruit action id and revision must be non-negative");
        }
        if (count <= 0 || count > ArmiesProtocol.MAX_RECRUITS_PER_INTENT
                || villagerUuidBits == null
                || villagerUuidBits.length != BoundedCodecs.checkedLength(count, 2)) {
            throw new IllegalArgumentException("Recruit selection is outside protocol bounds");
        }
    }

    private static void encode(RegistryFriendlyByteBuf buffer, RecruitUnitsIntent payload) {
        BoundedCodecs.writeCount(buffer, payload.actionId, Integer.MAX_VALUE, "actionId");
        buffer.writeVarInt(payload.armyHandle);
        buffer.writeLong(payload.villageUuidMost);
        buffer.writeLong(payload.villageUuidLeast);
        BoundedCodecs.writeRevision(buffer, payload.expectedRevision, "expectedRevision");
        BoundedCodecs.writeCount(
                buffer, payload.count, ArmiesProtocol.MAX_RECRUITS_PER_INTENT, "selected recruits");
        BoundedCodecs.writeLongs(buffer, payload.villagerUuidBits);
    }

    private static RecruitUnitsIntent decode(RegistryFriendlyByteBuf buffer) {
        int actionId = BoundedCodecs.readCount(buffer, Integer.MAX_VALUE, "actionId");
        int armyHandle = buffer.readVarInt();
        long villageMost = buffer.readLong();
        long villageLeast = buffer.readLong();
        long expectedRevision = BoundedCodecs.readRevision(buffer, "expectedRevision");
        int count = BoundedCodecs.readCount(
                buffer, ArmiesProtocol.MAX_RECRUITS_PER_INTENT, "selected recruits");
        if (count == 0) {
            throw new IllegalArgumentException("Recruit selection must not be empty");
        }
        return new RecruitUnitsIntent(
                actionId,
                armyHandle,
                villageMost,
                villageLeast,
                expectedRevision,
                count,
                BoundedCodecs.readLongs(buffer, count, 2));
    }

    @Override
    public Type<RecruitUnitsIntent> type() {
        return TYPE;
    }
}
