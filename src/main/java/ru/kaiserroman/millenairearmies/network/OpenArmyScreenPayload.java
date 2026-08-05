package ru.kaiserroman.millenairearmies.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenairearmies.UniversalisIds;

/** Server-to-client instruction used by the user-facing /millarmies command entry point. */
public record OpenArmyScreenPayload() implements CustomPacketPayload {
    public static final Type<OpenArmyScreenPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            UniversalisIds.MOD_ID, "open_screen"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenArmyScreenPayload> STREAM_CODEC = StreamCodec.unit(
            new OpenArmyScreenPayload());

    @Override
    public Type<OpenArmyScreenPayload> type() {
        return TYPE;
    }
}
