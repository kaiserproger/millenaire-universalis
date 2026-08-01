package ru.kaiserroman.millenairearmies.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.millenaire.client.gui.ControlledMilitaryScreen;
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
            ArmyClientScreenBridge.install(() -> Minecraft.getInstance().setScreen(new MillenaireCommandScreen()));
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

            if (minecraft.player != null
                    && (minecraft.screen == null || minecraft.screen instanceof ControlledMilitaryScreen)) {
                minecraft.setScreen(new MillenaireCommandScreen());
            }
        }

        @SubscribeEvent
        public static void interaction(InputEvent.InteractionKeyMappingTriggered event) {
            if (!ArmyTargetSelection.active() || !event.isAttack() && !event.isUseItem()) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.hitResult instanceof BlockHitResult block
                    && block.getType() == HitResult.Type.BLOCK
                    && ArmyTargetSelection.confirm(block)) {
                event.setCanceled(true);
                event.setSwingHand(false);
            } else if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable(
                                "gui.millenaire_armies.target.invalid"), true);
                event.setCanceled(true);
                event.setSwingHand(false);
            }
        }

        @SubscribeEvent
        public static void keyInput(InputEvent.Key event) {
            if (ArmyTargetSelection.active()
                    && event.getKey() == GLFW.GLFW_KEY_ESCAPE
                    && event.getAction() == GLFW.GLFW_PRESS) {
                ArmyTargetSelection.cancelFromEscape();
            }
        }

        @SubscribeEvent
        public static void screenOpening(ScreenEvent.Opening event) {
            if (event.getNewScreen() instanceof PauseScreen && ArmyTargetSelection.consumePauseOpening()) {
                event.setCanceled(true);
                return;
            }
            if (event.getNewScreen() instanceof ControlledMilitaryScreen
                    && Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable(
                                "gui.millenaire_armies.entry.controlled_military"), false);
            }
        }

        @SubscribeEvent
        public static void disconnected(ClientPlayerNetworkEvent.LoggingOut event) {
            ArmyClientState.clear();
            ArmyTargetSelection.clear();
        }
    }
}
