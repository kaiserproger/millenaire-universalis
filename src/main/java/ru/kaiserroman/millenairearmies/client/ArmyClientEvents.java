package ru.kaiserroman.millenairearmies.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;
import ru.kaiserroman.millenairearmies.SarvarMillenaireArmies;
import ru.kaiserroman.millenairearmies.client.ui.MillenaireCommandScreen;

/** Physical-client hooks. The Dist gate keeps every referenced Minecraft client class off a server. */
public final class ArmyClientEvents {
    private ArmyClientEvents() {
    }

    @EventBusSubscriber(modid = SarvarMillenaireArmies.MOD_ID, value = Dist.CLIENT)
    public static final class Registration {
        private static final KeyMapping OPEN_COMMAND = new KeyMapping(
                "key.millenaire_armies.open_command",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                "key.categories.millenaire_armies");

        private Registration() {
        }

        @SubscribeEvent
        public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(OPEN_COMMAND);
        }
    }

    @EventBusSubscriber(modid = SarvarMillenaireArmies.MOD_ID, value = Dist.CLIENT)
    public static final class GameEvents {
        private GameEvents() {
        }

        @SubscribeEvent
        public static void clientTick(ClientTickEvent.Post event) {
            Minecraft minecraft = Minecraft.getInstance();
            if (!Registration.OPEN_COMMAND.consumeClick()) {
                return;
            }

            // Drain repeat clicks without allocating/opening multiple screen instances.
            while (Registration.OPEN_COMMAND.consumeClick()) {
                // Deliberately empty.
            }

            if (minecraft.player != null && minecraft.screen == null) {
                minecraft.setScreen(new MillenaireCommandScreen());
            }
        }

        @SubscribeEvent
        public static void disconnected(ClientPlayerNetworkEvent.LoggingOut event) {
            ArmyClientState.clear();
        }
    }
}
