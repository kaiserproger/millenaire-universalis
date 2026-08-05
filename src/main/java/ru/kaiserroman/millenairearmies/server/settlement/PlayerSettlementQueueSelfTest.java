package ru.kaiserroman.millenairearmies.server.settlement;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.millenaire.village.ControlledQueuedProject;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;

/** Regression check that controlled queues are drained through Millenaire's mutable Village API. */
public final class PlayerSettlementQueueSelfTest {
    private PlayerSettlementQueueSelfTest() {}

    public static void main(String[] args) {
        Village village = new Village(
                new VillageId(UUID.randomUUID()),
                ResourceLocation.fromNamespaceAndPath("millenaire", "norman"),
                ResourceLocation.fromNamespaceAndPath("millenaire", "norman/test"),
                BlockPos.ZERO);
        ResourceLocation plan = ResourceLocation.fromNamespaceAndPath(
                "millenaire", "norman/test_house");
        check(village.enqueueControlledProject(
                        new ControlledQueuedProject(plan, "a", 0, null)),
                "first project enqueued");
        check(village.enqueueControlledProject(
                        new ControlledQueuedProject(plan, "b", 0, null)),
                "second project enqueued");
        check(PlayerSettlementService.drainControlledQueue(null, village) == 2,
                "all queued projects drained");
        check(village.getControlledQueue().isEmpty(), "queue is empty after drain");
        check(PlayerSettlementService.drainControlledQueue(null, village) == 0,
                "draining an empty queue is stable");
        System.out.println("PlayerSettlementQueueSelfTest: OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
