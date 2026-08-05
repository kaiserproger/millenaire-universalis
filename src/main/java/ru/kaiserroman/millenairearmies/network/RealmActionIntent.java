package ru.kaiserroman.millenairearmies.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenairearmies.UniversalisIds;

/** Found a realm from a controlled capital or update its bounded tax rate. */
public record RealmActionIntent(
        int actionId,
        byte action,
        long capitalVillageMost,
        long capitalVillageLeast,
        int taxRate,
        long expectedArmyRevision,
        long expectedRealmRevision)
        implements CustomPacketPayload {
    public static final byte ACTION_FOUND = 1;
    public static final byte ACTION_SET_TAX = 2;
    public static final Type<RealmActionIntent> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            UniversalisIds.MOD_ID, "realm_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RealmActionIntent> STREAM_CODEC = StreamCodec.of(
            RealmActionIntent::encode, RealmActionIntent::decode);

    public RealmActionIntent {
        if (actionId < 0 || expectedArmyRevision < 0L || expectedRealmRevision < 0L
                || action < ACTION_FOUND || action > ACTION_SET_TAX
                || taxRate < 0 || taxRate > 25) {
            throw new IllegalArgumentException("Realm action is outside protocol bounds");
        }
    }

    private static void encode(RegistryFriendlyByteBuf buffer, RealmActionIntent payload) {
        BoundedCodecs.writeCount(buffer, payload.actionId, Integer.MAX_VALUE, "actionId");
        buffer.writeByte(payload.action);
        buffer.writeLong(payload.capitalVillageMost);
        buffer.writeLong(payload.capitalVillageLeast);
        BoundedCodecs.writeCount(buffer, payload.taxRate, 25, "taxRate");
        BoundedCodecs.writeRevision(buffer, payload.expectedArmyRevision, "expectedArmyRevision");
        BoundedCodecs.writeRevision(buffer, payload.expectedRealmRevision, "expectedRealmRevision");
    }

    private static RealmActionIntent decode(RegistryFriendlyByteBuf buffer) {
        return new RealmActionIntent(
                BoundedCodecs.readCount(buffer, Integer.MAX_VALUE, "actionId"),
                buffer.readByte(),
                buffer.readLong(),
                buffer.readLong(),
                BoundedCodecs.readCount(buffer, 25, "taxRate"),
                BoundedCodecs.readRevision(buffer, "expectedArmyRevision"),
                BoundedCodecs.readRevision(buffer, "expectedRealmRevision"));
    }

    @Override
    public Type<RealmActionIntent> type() { return TYPE; }
}
