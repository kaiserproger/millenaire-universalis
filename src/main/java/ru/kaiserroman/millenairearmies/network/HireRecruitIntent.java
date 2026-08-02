package ru.kaiserroman.millenairearmies.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenairearmies.SarvarMillenaireArmies;

/** Authenticated request to pay Millenaire's one-day hire cost and form/join a retinue. */
public record HireRecruitIntent(
        int actionId, long villagerUuidMost, long villagerUuidLeast, long expectedRevision)
        implements CustomPacketPayload {
    public static final Type<HireRecruitIntent> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            SarvarMillenaireArmies.MOD_ID, "hire_recruit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, HireRecruitIntent> STREAM_CODEC = StreamCodec.of(
            HireRecruitIntent::encode, HireRecruitIntent::decode);

    public HireRecruitIntent {
        if (actionId < 0 || expectedRevision < 0) {
            throw new IllegalArgumentException("Hire action id and revision must be non-negative");
        }
    }

    private static void encode(RegistryFriendlyByteBuf buffer, HireRecruitIntent payload) {
        BoundedCodecs.writeCount(buffer, payload.actionId, Integer.MAX_VALUE, "actionId");
        buffer.writeLong(payload.villagerUuidMost);
        buffer.writeLong(payload.villagerUuidLeast);
        BoundedCodecs.writeRevision(buffer, payload.expectedRevision, "expectedRevision");
    }

    private static HireRecruitIntent decode(RegistryFriendlyByteBuf buffer) {
        return new HireRecruitIntent(
                BoundedCodecs.readCount(buffer, Integer.MAX_VALUE, "actionId"),
                buffer.readLong(),
                buffer.readLong(),
                BoundedCodecs.readRevision(buffer, "expectedRevision"));
    }

    @Override
    public Type<HireRecruitIntent> type() { return TYPE; }
}
