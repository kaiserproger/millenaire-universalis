package ru.kaiserroman.millenairearmies.server.unit;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import ru.kaiserroman.millenairearmies.SarvarMillenaireArmies;

/** Static GAME-bus hook; does not require lifecycle or network entry-point ownership. */
@EventBusSubscriber(modid = SarvarMillenaireArmies.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class UnitDescriptorReloadEvents {
    private UnitDescriptorReloadEvents() {}

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(UnitDescriptorCatalog.INSTANCE);
    }
}
