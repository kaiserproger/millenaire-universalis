package ru.kaiserroman.millenairearmies.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenaire.realm.GovernmentForm;
import ru.kaiserroman.millenairearmies.UniversalisIds;
import ru.kaiserroman.millenairearmies.persistence.RealmGovernanceSavedData;

/** Bounded server projection of one authenticated player's founded realm and economy. */
public record RealmStatePayload(
        long realmRevision,
        int acknowledgementActionId,
        byte acknowledgementAction,
        int acknowledgementResult,
        boolean founded,
        byte role,
        byte government,
        String name,
        String capitalName,
        String controlledSettlementName,
        int taxRate,
        long treasury,
        int settlementCount,
        int regionCount,
        int population,
        int capturedSettlements,
        int food,
        int iron,
        int leather,
        int arrows)
        implements CustomPacketPayload {
    public static final int MAX_STRING_UTF8_BYTES = 192;
    public static final Type<RealmStatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            UniversalisIds.MOD_ID, "realm_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RealmStatePayload> STREAM_CODEC = StreamCodec.of(
            RealmStatePayload::encode, RealmStatePayload::decode);

    public RealmStatePayload {
        if (realmRevision < 0L || acknowledgementActionId < 0
                || acknowledgementAction < 0 || acknowledgementAction > RealmActionIntent.ACTION_SET_TAX
                || acknowledgementResult < ArmiesProtocol.RESULT_NONE
                || acknowledgementResult > ArmiesProtocol.RESULT_PARTIAL
                || role < RealmGovernanceSavedData.ROLE_NONE
                || role > RealmGovernanceSavedData.ROLE_GOVERNOR
                || government < 0 || government > GovernmentForm.values().length
                || taxRate < 0 || taxRate > 100 || treasury < 0L
                || settlementCount < 0 || regionCount < 0 || regionCount > settlementCount
                || population < 0 || capturedSettlements < 0
                || food < 0 || iron < 0 || leather < 0 || arrows < 0) {
            throw new IllegalArgumentException("Realm snapshot is outside protocol bounds");
        }
        BoundedCodecs.utf8Length(name, MAX_STRING_UTF8_BYTES, "realm name");
        BoundedCodecs.utf8Length(capitalName, MAX_STRING_UTF8_BYTES, "capital name");
        BoundedCodecs.utf8Length(
                controlledSettlementName, MAX_STRING_UTF8_BYTES, "controlled settlement name");
        if (!founded && (!name.isEmpty()
                || !capitalName.isEmpty()
                || !controlledSettlementName.isEmpty()
                || role != RealmGovernanceSavedData.ROLE_NONE
                || government != 0
                || settlementCount != 0
                || regionCount != 0
                || realmRevision != 0L)) {
            throw new IllegalArgumentException("Unfounded realm snapshot contains founded state");
        }
        if (founded && (role == RealmGovernanceSavedData.ROLE_NONE || government == 0)) {
            throw new IllegalArgumentException("Founded realm snapshot lacks political role");
        }
    }

    private static void encode(RegistryFriendlyByteBuf buffer, RealmStatePayload payload) {
        BoundedCodecs.writeRevision(buffer, payload.realmRevision, "realmRevision");
        BoundedCodecs.writeCount(buffer, payload.acknowledgementActionId, Integer.MAX_VALUE, "actionId");
        buffer.writeByte(payload.acknowledgementAction);
        BoundedCodecs.writeCount(buffer, payload.acknowledgementResult, ArmiesProtocol.RESULT_PARTIAL, "result");
        buffer.writeBoolean(payload.founded);
        buffer.writeByte(payload.role);
        buffer.writeByte(payload.government);
        BoundedCodecs.writeUtf8(buffer, payload.name, MAX_STRING_UTF8_BYTES, "realm name");
        BoundedCodecs.writeUtf8(buffer, payload.capitalName, MAX_STRING_UTF8_BYTES, "capital name");
        BoundedCodecs.writeUtf8(
                buffer,
                payload.controlledSettlementName,
                MAX_STRING_UTF8_BYTES,
                "controlled settlement name");
        BoundedCodecs.writeCount(buffer, payload.taxRate, 100, "taxRate");
        buffer.writeVarLong(payload.treasury);
        BoundedCodecs.writeCount(buffer, payload.settlementCount, Integer.MAX_VALUE, "settlements");
        BoundedCodecs.writeCount(buffer, payload.regionCount, Integer.MAX_VALUE, "regions");
        BoundedCodecs.writeCount(buffer, payload.population, Integer.MAX_VALUE, "population");
        BoundedCodecs.writeCount(buffer, payload.capturedSettlements, Integer.MAX_VALUE, "captures");
        BoundedCodecs.writeCount(buffer, payload.food, Integer.MAX_VALUE, "food");
        BoundedCodecs.writeCount(buffer, payload.iron, Integer.MAX_VALUE, "iron");
        BoundedCodecs.writeCount(buffer, payload.leather, Integer.MAX_VALUE, "leather");
        BoundedCodecs.writeCount(buffer, payload.arrows, Integer.MAX_VALUE, "arrows");
    }

    private static RealmStatePayload decode(RegistryFriendlyByteBuf buffer) {
        return new RealmStatePayload(
                BoundedCodecs.readRevision(buffer, "realmRevision"),
                BoundedCodecs.readCount(buffer, Integer.MAX_VALUE, "actionId"),
                buffer.readByte(),
                BoundedCodecs.readCount(buffer, ArmiesProtocol.RESULT_PARTIAL, "result"),
                buffer.readBoolean(),
                buffer.readByte(),
                buffer.readByte(),
                BoundedCodecs.readUtf8(buffer, MAX_STRING_UTF8_BYTES, "realm name"),
                BoundedCodecs.readUtf8(buffer, MAX_STRING_UTF8_BYTES, "capital name"),
                BoundedCodecs.readUtf8(buffer, MAX_STRING_UTF8_BYTES, "controlled settlement name"),
                BoundedCodecs.readCount(buffer, 100, "taxRate"),
                buffer.readVarLong(),
                BoundedCodecs.readCount(buffer, Integer.MAX_VALUE, "settlements"),
                BoundedCodecs.readCount(buffer, Integer.MAX_VALUE, "regions"),
                BoundedCodecs.readCount(buffer, Integer.MAX_VALUE, "population"),
                BoundedCodecs.readCount(buffer, Integer.MAX_VALUE, "captures"),
                BoundedCodecs.readCount(buffer, Integer.MAX_VALUE, "food"),
                BoundedCodecs.readCount(buffer, Integer.MAX_VALUE, "iron"),
                BoundedCodecs.readCount(buffer, Integer.MAX_VALUE, "leather"),
                BoundedCodecs.readCount(buffer, Integer.MAX_VALUE, "arrows"));
    }

    @Override
    public Type<RealmStatePayload> type() { return TYPE; }
}
