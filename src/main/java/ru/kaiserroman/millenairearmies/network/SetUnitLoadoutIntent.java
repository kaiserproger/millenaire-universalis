package ru.kaiserroman.millenairearmies.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenairearmies.SarvarMillenaireArmies;

/** Server-authoritative loadout override change for one owned unit. */
public record SetUnitLoadoutIntent(
        int actionId,
        int armyHandle,
        int unitHandle,
        byte loadoutSelector,
        int loadoutToken,
        ResourceLocation loadoutKey,
        long expectedRevision)
        implements CustomPacketPayload {
    public static final byte LOADOUT_BY_TOKEN = 0;
    public static final byte LOADOUT_BY_KEY = 1;
    public static final byte LOADOUT_DEFAULT = 2;
    private static final int MAX_REGISTRY_KEY_BYTES = 192;

    public static final Type<SetUnitLoadoutIntent> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            SarvarMillenaireArmies.MOD_ID, "set_unit_loadout"));
    public static final StreamCodec<RegistryFriendlyByteBuf, SetUnitLoadoutIntent> STREAM_CODEC = StreamCodec.of(
            SetUnitLoadoutIntent::encode, SetUnitLoadoutIntent::decode);

    public SetUnitLoadoutIntent {
        if (actionId < 0 || expectedRevision < 0L) {
            throw new IllegalArgumentException("Set unit loadout intent is outside protocol bounds");
        }
        if (armyHandle == 0 || unitHandle == 0) {
            throw new IllegalArgumentException("Invalid army or unit handle");
        }
        if (loadoutSelector != LOADOUT_BY_TOKEN
                && loadoutSelector != LOADOUT_BY_KEY
                && loadoutSelector != LOADOUT_DEFAULT) {
            throw new IllegalArgumentException("Unknown loadout selector " + loadoutSelector);
        }
        if (loadoutSelector == LOADOUT_BY_TOKEN) {
            if (loadoutToken == 0) {
                throw new IllegalArgumentException("Loadout token zero is reserved for the default selector");
            }
            if (loadoutKey != null) {
                throw new IllegalArgumentException("Loadout key must be null when selector is token");
            }
        } else if (loadoutSelector == LOADOUT_BY_KEY) {
            if (loadoutKey == null) {
                throw new IllegalArgumentException("Loadout key missing for key selector");
            }
            if (loadoutToken != 0) {
                throw new IllegalArgumentException("Loadout token must be zero when selector is key");
            }
            BoundedCodecs.utf8Length(loadoutKey.toString(), MAX_REGISTRY_KEY_BYTES, "loadoutKey");
        } else if (loadoutKey != null || loadoutToken != 0) {
            throw new IllegalArgumentException("Token and key must be clear for default selector");
        }
    }

    public static SetUnitLoadoutIntent forToken(
            int actionId,
            int armyHandle,
            int unitHandle,
            int loadoutToken,
            long expectedRevision) {
        return new SetUnitLoadoutIntent(
                actionId,
                armyHandle,
                unitHandle,
                LOADOUT_BY_TOKEN,
                loadoutToken,
                null,
                expectedRevision);
    }

    public static SetUnitLoadoutIntent forKey(
            int actionId,
            int armyHandle,
            int unitHandle,
            ResourceLocation loadoutKey,
            long expectedRevision) {
        return new SetUnitLoadoutIntent(
                actionId,
                armyHandle,
                unitHandle,
                LOADOUT_BY_KEY,
                0,
                loadoutKey,
                expectedRevision);
    }

    public static SetUnitLoadoutIntent clearOverride(
            int actionId,
            int armyHandle,
            int unitHandle,
            long expectedRevision) {
        return new SetUnitLoadoutIntent(
                actionId,
                armyHandle,
                unitHandle,
                LOADOUT_DEFAULT,
                0,
                null,
                expectedRevision);
    }

    private static void encode(RegistryFriendlyByteBuf buffer, SetUnitLoadoutIntent payload) {
        BoundedCodecs.writeCount(buffer, payload.actionId, Integer.MAX_VALUE, "actionId");
        buffer.writeVarInt(payload.armyHandle);
        buffer.writeVarInt(payload.unitHandle);
        buffer.writeByte(payload.loadoutSelector);
        if (payload.loadoutSelector == LOADOUT_BY_TOKEN) {
            BoundedCodecs.writeSignedVarInt(buffer, payload.loadoutToken);
        } else if (payload.loadoutSelector == LOADOUT_BY_KEY) {
            BoundedCodecs.writeUtf8(
                    buffer, payload.loadoutKey.toString(), MAX_REGISTRY_KEY_BYTES, "loadoutKey");
        }
        BoundedCodecs.writeRevision(buffer, payload.expectedRevision, "expectedRevision");
    }

    private static SetUnitLoadoutIntent decode(RegistryFriendlyByteBuf buffer) {
        int actionId = BoundedCodecs.readCount(buffer, Integer.MAX_VALUE, "actionId");
        int armyHandle = buffer.readVarInt();
        int unitHandle = buffer.readVarInt();
        byte loadoutSelector = buffer.readByte();
        int loadoutToken = 0;
        ResourceLocation loadoutKey = null;
        if (loadoutSelector == LOADOUT_BY_TOKEN) {
            loadoutToken = BoundedCodecs.readSignedVarInt(buffer);
        } else if (loadoutSelector == LOADOUT_BY_KEY) {
            loadoutKey = ResourceLocation.tryParse(BoundedCodecs.readUtf8(
                    buffer,
                    MAX_REGISTRY_KEY_BYTES,
                    "loadoutKey"));
            if (loadoutKey == null) {
                throw new IllegalArgumentException("Invalid loadout registry key");
            }
        } else if (loadoutSelector != LOADOUT_DEFAULT) {
            throw new IllegalArgumentException("Unknown loadout selector " + loadoutSelector);
        }
        long expectedRevision = BoundedCodecs.readRevision(buffer, "expectedRevision");
        return new SetUnitLoadoutIntent(
                actionId,
                armyHandle,
                unitHandle,
                loadoutSelector,
                loadoutToken,
                loadoutKey,
                expectedRevision);
    }

    @Override
    public Type<SetUnitLoadoutIntent> type() {
        return TYPE;
    }
}
