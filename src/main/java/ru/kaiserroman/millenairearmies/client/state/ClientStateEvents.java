package ru.kaiserroman.millenairearmies.client.state;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import ru.kaiserroman.millenairearmies.SarvarMillenaireArmies;

/** Keeps state from one server/world out of the next strategic screen. */
@EventBusSubscriber(
        value = Dist.CLIENT,
        modid = SarvarMillenaireArmies.MOD_ID,
        bus = EventBusSubscriber.Bus.GAME)
public final class ClientStateEvents {
    private ClientStateEvents() {}

    @SubscribeEvent
    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        ClientArmyState.INSTANCE.listener(NetworkArmyClientMirror.INSTANCE);
        ClientFactionMetadataState.INSTANCE.listener(NetworkArmyClientMirror.INSTANCE);
        ClientArmyRosterState.INSTANCE.listener(() -> NetworkArmyClientMirror.INSTANCE.rosterChanged());
        ClientRealmState.INSTANCE.listener(() -> NetworkArmyClientMirror.INSTANCE.realmChanged());
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientArmyState.INSTANCE.reset();
        ClientFactionMetadataState.INSTANCE.reset();
        ClientArmyRosterState.INSTANCE.reset();
        ClientRealmState.INSTANCE.reset();
        ClientArmyState.INSTANCE.listener(null);
        ClientFactionMetadataState.INSTANCE.listener(null);
        ClientArmyRosterState.INSTANCE.listener(null);
        ClientRealmState.INSTANCE.listener(null);
    }
}
