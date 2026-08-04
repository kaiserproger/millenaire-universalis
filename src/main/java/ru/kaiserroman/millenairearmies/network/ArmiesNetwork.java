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
import ru.kaiserroman.millenairearmies.client.state.ClientRealmState;
import ru.kaiserroman.millenairearmies.client.state.ClientRealmDiplomacyState;
import ru.kaiserroman.millenairearmies.client.state.ClientGarrisonState;
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
        registrar.playToServer(HireRecruitIntent.TYPE, HireRecruitIntent.STREAM_CODEC, ArmiesNetwork::handleHire);
        registrar.playToServer(IssueOrderIntent.TYPE, IssueOrderIntent.STREAM_CODEC, ArmiesNetwork::handleOrder);
        registrar.playToServer(
                SetFormationIntent.TYPE, SetFormationIntent.STREAM_CODEC, ArmiesNetwork::handleFormation);
        registrar.playToServer(
                SetTacticalIntent.TYPE, SetTacticalIntent.STREAM_CODEC, ArmiesNetwork::handleTactical);
        registrar.playToServer(
                SetSupplyChestIntent.TYPE, SetSupplyChestIntent.STREAM_CODEC, ArmiesNetwork::handleSupplyChest);
        registrar.playToServer(
                SetUnitLoadoutIntent.TYPE, SetUnitLoadoutIntent.STREAM_CODEC, ArmiesNetwork::handleUnitLoadout);
        registrar.playToServer(
                SetGarrisonIntent.TYPE, SetGarrisonIntent.STREAM_CODEC, ArmiesNetwork::handleGarrison);
        registrar.playToServer(
                RealmActionIntent.TYPE, RealmActionIntent.STREAM_CODEC, ArmiesNetwork::handleRealmAction);
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
                RealmStatePayload.TYPE,
                RealmStatePayload.STREAM_CODEC,
                (payload, context) -> ClientRealmState.INSTANCE.apply(payload));
        registrar.playToClient(
                RealmDiplomacySnapshotPayload.TYPE,
                RealmDiplomacySnapshotPayload.STREAM_CODEC,
                (payload, context) -> ClientRealmDiplomacyState.INSTANCE.apply(payload));
        registrar.playToClient(
                GarrisonStatePayload.TYPE,
                GarrisonStatePayload.STREAM_CODEC,
                (payload, context) -> ClientGarrisonState.INSTANCE.apply(payload));
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

    public static void sendRealm(ServerPlayer player, RealmStatePayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendRealmDiplomacy(
            ServerPlayer player, RealmDiplomacySnapshotPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void sendGarrisonState(ServerPlayer player, GarrisonStatePayload payload) {
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

    private static void handleHire(HireRecruitIntent intent, IPayloadContext context) {
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

    private static void handleFormation(SetFormationIntent intent, IPayloadContext context) {
        ServerPlayer player = authenticatedPlayer(context);
        if (player != null) {
            ServerIntentRouter.dispatch(player, intent);
        }
    }

    private static void handleTactical(SetTacticalIntent intent, IPayloadContext context) {
        ServerPlayer player = authenticatedPlayer(context);
        if (player != null) {
            ServerIntentRouter.dispatch(player, intent);
        }
    }

    private static void handleSupplyChest(SetSupplyChestIntent intent, IPayloadContext context) {
        ServerPlayer player = authenticatedPlayer(context);
        if (player != null) {
            ServerIntentRouter.dispatch(player, intent);
        }
    }

    private static void handleUnitLoadout(SetUnitLoadoutIntent intent, IPayloadContext context) {
        ServerPlayer player = authenticatedPlayer(context);
        if (player != null) {
            ServerIntentRouter.dispatch(player, intent);
        }
    }

    private static void handleGarrison(SetGarrisonIntent intent, IPayloadContext context) {
        ServerPlayer player = authenticatedPlayer(context);
        if (player != null) {
            ServerIntentRouter.dispatch(player, intent);
        }
    }

    private static void handleRealmAction(RealmActionIntent intent, IPayloadContext context) {
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
