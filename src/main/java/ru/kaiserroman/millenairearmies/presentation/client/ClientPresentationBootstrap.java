package ru.kaiserroman.millenairearmies.presentation.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import ru.kaiserroman.millenairearmies.UniversalisIds;

/** Registers client resources without loading client classes on a dedicated server. */
@EventBusSubscriber(
        modid = UniversalisIds.MOD_ID,
        value = Dist.CLIENT)
public final class ClientPresentationBootstrap {
    private ClientPresentationBootstrap() {}

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(ClientPresentationCatalog.INSTANCE);
    }
}
