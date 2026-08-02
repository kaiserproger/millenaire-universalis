package ru.kaiserroman.millenairearmies.server.execution;

import net.minecraft.core.BlockPos;
import org.millenaire.entity.MillVillager;
import org.millenaire.entity.VillagerNavDriver;

/** Exact bounded call-site into Millenaire's owning navigation driver. */
final class BoundedNavigationDelegation {
    static final int RETARGET_INTERVAL_TICKS = 20;

    private BoundedNavigationDelegation() {}

    static int retarget(
            VillagerNavDriver navigation,
            MillVillager villager,
            BlockPos target,
            double speed,
            int cooldown) {
        BlockPos currentDestination = navigation.getDestination();
        if (currentDestination != null && target.equals(currentDestination)) {
            return 0;
        }
        if (cooldown > 0) {
            return cooldown - 1;
        }
        navigation.navigateTo(villager, target, speed);
        return RETARGET_INTERVAL_TICKS;
    }

    static void stop(VillagerNavDriver navigation, MillVillager villager) {
        navigation.stop(villager);
    }
}
