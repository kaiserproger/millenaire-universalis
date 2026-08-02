package ru.kaiserroman.millenairearmies;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import ru.kaiserroman.millenairearmies.lifecycle.ArmyLifecycleService;
import ru.kaiserroman.millenairearmies.server.command.MillArmiesCommands;
import ru.kaiserroman.millenairearmies.server.command.MillArmiesFactionCommands;
import ru.kaiserroman.millenairearmies.server.command.MillArmiesRecruitmentCommands;
import ru.kaiserroman.millenairearmies.server.command.MillArmiesRealmCommands;

@Mod(SarvarMillenaireArmies.MOD_ID)
public final class SarvarMillenaireArmies {
    public static final String MOD_ID = "millenaire_armies";
    private static final Logger LOGGER = LogUtils.getLogger();

    private final ArmyLifecycleService lifecycle = new ArmyLifecycleService();

    public SarvarMillenaireArmies() {
        if (ArmiesConfig.ENABLED) {
            NeoForge.EVENT_BUS.register(this);
        }
        LOGGER.info(
                "Millenaire Armies strategy: {}; limits factions={}, armies={}, orders={}, logistics={}; config={}",
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
                    "Millenaire Armies strategy, recruitment, realm economy and networking are ready; physical entity orders={}; navigation/combat use real Millenaire entities",
                    ArmiesConfig.ORDER_EXECUTION_ENABLED ? "enabled" : "disabled (emergency state-only fallback)");
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        MillArmiesCommands.register(event.getDispatcher(), lifecycle.commandService());
        MillArmiesFactionCommands.register(event.getDispatcher(), lifecycle.factionProjection());
        MillArmiesRecruitmentCommands.register(event.getDispatcher(), lifecycle.recruitmentService());
        MillArmiesRealmCommands.register(event.getDispatcher(), lifecycle::realmService);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onServerTick(ServerTickEvent.Post event) {
        lifecycle.tick(event.getServer());
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
    public void onLivingDamage(LivingDamageEvent.Post event) {
        if (event.getEntity() instanceof org.millenaire.entity.MillVillager victim
                && event.getSource().getEntity() instanceof net.minecraft.world.entity.LivingEntity source) {
            lifecycle.entityDamaged(victim, source, event.getNewDamage());
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof org.millenaire.entity.MillVillager villager) {
            lifecycle.entityDied(villager);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        lifecycle.stop();
    }
}
