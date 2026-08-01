package ru.kaiserroman.millenairearmies.network;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import ru.kaiserroman.millenairearmies.SarvarMillenaireArmies;
import ru.kaiserroman.millenairearmies.client.state.ClientArmyState;
import ru.kaiserroman.millenairearmies.client.state.ClientFactionMetadataState;
import ru.kaiserroman.millenairearmies.client.state.ClientArmyRosterState;
import ru.kaiserroman.millenairearmies.client.ArmyClientScreenBridge;

/** NeoForge 1.21.1 payload registration and main-thread dispatch. */
@EventBusSubscriber(modid = SarvarMillenaireArmies.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class ArmiesNetwork {
    private ArmiesNetwork() {}

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // Required registration intentionally makes the addon BOTH: mismatched clients are rejected
        // during negotiation instead of seeing a half-functional strategic UI.
        PayloadRegistrar registrar = event.registrar(ArmiesProtocol.VERSION);
        registrar.playToServer(OpenCommandIntent.TYPE, OpenCommandIntent.STREAM_CODEC, ArmiesNetwork::handleOpen);
        registrar.playToServer(RequestStateIntent.TYPE, RequestStateIntent.STREAM_CODEC, ArmiesNetwork::handleRequest);
        registrar.playToServer(CreateArmyIntent.TYPE, CreateArmyIntent.STREAM_CODEC, ArmiesNetwork::handleCreate);
        registrar.playToServer(RecruitUnitsIntent.TYPE, RecruitUnitsIntent.STREAM_CODEC, ArmiesNetwork::handleRecruit);
        registrar.playToServer(IssueOrderIntent.TYPE, IssueOrderIntent.STREAM_CODEC, ArmiesNetwork::handleOrder);
        registrar.playToClient(
                ArmyStateSnapshotPayload.TYPE,
                ArmyStateSnapshotPayload.STREAM_CODEC,
                (payload, context) -> ClientArmyState.INSTANCE.applySnapshot(payload));
        registrar.playToClient(
                ArmyStateDeltaPayload.TYPE,
                ArmyStateDeltaPayload.STREAM_CODEC,
                (payload, context) -> ClientArmyState.INSTANCE.applyDelta(payload));
        registrar.playToClient(
                FactionMetadataPayload.TYPE,
                FactionMetadataPayload.STREAM_CODEC,
                (payload, context) -> ClientFactionMetadataState.INSTANCE.apply(payload));
        registrar.playToClient(
                ArmyRosterSnapshotPayload.TYPE,
                ArmyRosterSnapshotPayload.STREAM_CODEC,
                (payload, context) -> ClientArmyRosterState.INSTANCE.apply(payload));
        registrar.playToClient(
                OpenArmyScreenPayload.TYPE,
                OpenArmyScreenPayload.STREAM_CODEC,
                (payload, context) -> ArmyClientScreenBridge.open());
    }

    public static void sendSnapshot(ServerPlayer player, ArmyStateSnapshotPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendDelta(ServerPlayer player, ArmyStateDeltaPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendFactionMetadata(ServerPlayer player, FactionMetadataPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendRoster(ServerPlayer player, ArmyRosterSnapshotPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void openScreen(ServerPlayer player) {
        PacketDistributor.sendToPlayer(player, new OpenArmyScreenPayload());
    }

    private static void handleOpen(OpenCommandIntent intent, IPayloadContext context) {
        ServerPlayer player = authenticatedPlayer(context);
        if (player != null) {
            ServerIntentRouter.dispatch(player, intent);
        }
    }

    private static void handleRequest(RequestStateIntent intent, IPayloadContext context) {
        ServerPlayer player = authenticatedPlayer(context);
        if (player != null) {
            ServerIntentRouter.dispatch(player, intent);
        }
    }

    private static void handleCreate(CreateArmyIntent intent, IPayloadContext context) {
        ServerPlayer player = authenticatedPlayer(context);
        if (player != null) {
            ServerIntentRouter.dispatch(player, intent);
        }
    }

    private static void handleRecruit(RecruitUnitsIntent intent, IPayloadContext context) {
        ServerPlayer player = authenticatedPlayer(context);
        if (player != null) {
            ServerIntentRouter.dispatch(player, intent);
        }
    }

    private static void handleOrder(IssueOrderIntent intent, IPayloadContext context) {
        ServerPlayer player = authenticatedPlayer(context);
        if (player != null) {
            ServerIntentRouter.dispatch(player, intent);
        }
    }

    private static ServerPlayer authenticatedPlayer(IPayloadContext context) {
        if (context.player() instanceof ServerPlayer player) {
            return player;
        }
        context.disconnect(Component.translatable("disconnect.genericReason", "Invalid armies packet direction"));
        return null;
    }
}
