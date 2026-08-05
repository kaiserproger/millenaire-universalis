package ru.kaiserroman.millenairearmies.presentation.client;

import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderNameTagEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.level.LevelEvent;
import ru.kaiserroman.millenairearmies.UniversalisIds;

/** Allocation-free render lookup using NeoForge's public name-tag event; no renderer mixin required. */
@EventBusSubscriber(modid = UniversalisIds.MOD_ID, value = Dist.CLIENT)
public final class ClientPresentationEvents {
    private static final double MAX_MARKER_DISTANCE_SQUARED = 64.0 * 64.0;

    private ClientPresentationEvents() {}

    @SubscribeEvent
    public static void onRenderNameTag(RenderNameTagEvent event) {
        Entity entity = event.getEntity();
        UUID entityId = entity.getUUID();
        ClientUnitPresentation presentation = ClientPresentationState.units().get(
                entityId.getMostSignificantBits(), entityId.getLeastSignificantBits());
        if (presentation == null || !presentation.showOverheadMarker()) {
            return;
        }

        Entity camera = net.minecraft.client.Minecraft.getInstance().getCameraEntity();
        if (camera == null || entity.distanceToSqr(camera) > MAX_MARKER_DISTANCE_SQUARED) {
            return;
        }
        event.setContent(presentation.overheadMarker());
        event.setCanRender(TriState.TRUE);
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            ClientPresentationState.units().clear();
        }
    }
}
