package ru.kaiserroman.millenairearmies;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.millenaire.entity.MillVillager;
import org.slf4j.Logger;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireSettlementBlockProtectionService;
import ru.kaiserroman.millenairearmies.integration.millenaire.SettlementBlockProtectionPolicy;
import ru.kaiserroman.millenairearmies.lifecycle.ArmyLifecycleService;
import ru.kaiserroman.millenairearmies.server.command.MillArmiesCommands;
import ru.kaiserroman.millenairearmies.server.command.MillArmiesFactionCommands;
import ru.kaiserroman.millenairearmies.server.command.MillArmiesRealmCommands;
import ru.kaiserroman.millenairearmies.server.command.MillArmiesRecruitmentCommands;
import ru.kaiserroman.millenairearmies.server.command.MillArmiesSimulationCommands;
import ru.kaiserroman.millenairearmies.server.integration.ArmiesIntegrationBridge;

@Mod(SarvarMillenaireArmies.MOD_ID)
public final class SarvarMillenaireArmies {
    public static final String MOD_ID = "millenaire_universalis";
    public static final String LEGACY_CONTENT_NAMESPACE = "millenaire_armies";
    private static final Logger LOGGER = LogUtils.getLogger();

    private final ArmyLifecycleService lifecycle = new ArmyLifecycleService();
    private final MillenaireSettlementBlockProtectionService blockProtection =
            new MillenaireSettlementBlockProtectionService(lifecycle);

    public SarvarMillenaireArmies() {
        ArmiesIntegrationBridge.bind(lifecycle);
        if (ArmiesConfig.ENABLED) {
            NeoForge.EVENT_BUS.register(this);
        }
        LOGGER.info(
                "Millenaire Universalis strategy: {}; limits factions={}, armies={}, orders={}, logistics={}; config={}",
                ArmiesConfig.ENABLED ? "enabled" : "disabled by config",
                ArmiesConfig.MAX_FACTIONS,
                ArmiesConfig.MAX_ARMIES,
                ArmiesConfig.MAX_PENDING_ORDERS,
                ArmiesConfig.MAX_LOGISTICS_REQUESTS,
                ArmiesConfig.path());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onServerStarted(ServerStartedEvent event) {
        if (lifecycle.start(event.getServer())) {
            LOGGER.info(
                    "Millenaire Universalis strategy, recruitment, diplomacy, logistics and networking are ready; entity-side order delegation={}; combat/pathfinding remain Millenaire-owned",
                    ArmiesConfig.ORDER_EXECUTION_ENABLED ? "enabled (bounded server-thread bridge)" : "disabled (state-only orders)");
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        MillArmiesCommands.register(event.getDispatcher(), lifecycle.commandService());
        MillArmiesFactionCommands.register(event.getDispatcher(), lifecycle.factionProjection());
        MillArmiesRecruitmentCommands.register(event.getDispatcher(), lifecycle.recruitmentService());
        MillArmiesSimulationCommands.register(event.getDispatcher(), lifecycle);
        MillArmiesRealmCommands.register(event.getDispatcher(), lifecycle);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onServerTick(ServerTickEvent.Post event) {
        lifecycle.tick(event.getServer());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        BlockPos position = event.getPosition().orElse(null);
        if (position == null) return;
        ServerLevel level = player.serverLevel();
        SettlementBlockProtectionPolicy.Decision decision =
                blockProtection.decide(player, level, position);
        float adjusted = blockProtection.adjustedSpeed(event.getNewSpeed(), decision);
        if (adjusted != event.getNewSpeed()) event.setNewSpeed(adjusted);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)
                || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        SettlementBlockProtectionPolicy.Decision decision =
                blockProtection.decide(player, level, event.getPos());
        if (blockProtection.cancelFinalBreak(decision)) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND
                || event.getLevel().isClientSide()
                || !(event.getEntity() instanceof ServerPlayer player)
                || !(event.getTarget() instanceof MillVillager villager)) {
            return;
        }
        if (lifecycle.tryOpenDynamicTrade(player, villager)) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onEntityJoin(EntityJoinLevelEvent event) {
        lifecycle.entityJoined(event.getEntity(), event.getLevel());
    }

    @SubscribeEvent
    public void onEntityLeave(EntityLeaveLevelEvent event) {
        lifecycle.entityLeft(event.getEntity(), event.getLevel());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLivingDeath(LivingDeathEvent event) {
        lifecycle.entityDied(event.getEntity(), event.getSource().getEntity());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        lifecycle.stop();
    }
}
