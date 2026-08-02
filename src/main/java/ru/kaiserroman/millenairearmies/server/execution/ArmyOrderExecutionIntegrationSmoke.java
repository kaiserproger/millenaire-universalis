package ru.kaiserroman.millenairearmies.server.execution;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import org.millenaire.entity.MillVillager;
import org.millenaire.entity.VillagerNavDriver;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.persistence.StableDimensionTable;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandAuthority;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandAuthorization;
import ru.kaiserroman.millenairearmies.server.service.PackedArmyControllers;
import ru.kaiserroman.millenairearmies.server.service.StrategicArmyOrder;

/** Cross-component smoke for the production order-execution admission and replay contract. */
public final class ArmyOrderExecutionIntegrationSmoke {
    private static final int UNIT_COUNT = 1_000;

    private ArmyOrderExecutionIntegrationSmoke() {}

    public static void main(String[] args) {
        StableDimensionTable dimensions = new StableDimensionTable();
        ResourceLocation overworld = ResourceLocation.parse("minecraft:overworld");
        ResourceLocation nether = ResourceLocation.parse("minecraft:the_nether");
        int overworldId = dimensions.intern(overworld);
        int netherId = dimensions.intern(nether);

        revisionAndStaleHandle(overworldId, netherId);
        missingUnloadAndRebind();
        dimensionAdmission(dimensions, overworldId, netherId, overworld, nether);
        ownerAuthorization();
        boundedPhysicalDelegation();
        long allocated = thousandUnitSteadyStateAllocationBudget();
        telemetryTransitions();

        System.out.println(
                "ArmyOrderExecutionIntegrationSmoke: PASS; units=" + UNIT_COUNT
                        + ", steady_allocated=" + allocated + " B");
    }

    private static void revisionAndStaleHandle(int overworldId, int netherId) {
        PackedArmyOrderRevisions revisions = new PackedArmyOrderRevisions();
        int firstGeneration = 0x0010_0007;
        int nextGeneration = firstGeneration + (1 << 20);
        long target = PackedArmyEcs.packBlockPos(100, 70, 120);
        check(revisions.observe(firstGeneration, StrategicArmyOrder.MOVE.code(), overworldId, target) == 1L,
                "first committed revision");
        check(revisions.observe(firstGeneration, StrategicArmyOrder.MOVE.code(), overworldId, target) == 1L,
                "identical commit is stable");
        check(revisions.observe(firstGeneration, StrategicArmyOrder.MOVE.code(), netherId, target) == 2L,
                "dimension participates in revision");
        check(revisions.observe(nextGeneration, StrategicArmyOrder.HOLD.code(), overworldId, target) == 1L,
                "reused slot starts a fresh generation");
        check(revisions.revision(firstGeneration) == 0L, "stale generation is unreachable");
        check(revisions.size() == 1, "slot reuse does not leak revision rows");
    }

    private static void missingUnloadAndRebind() {
        PackedUnitExecutionState states = new PackedUnitExecutionState();
        int army = 0x0010_0002;
        int unit = 0x0010_0012;
        long revision = 9L;

        // A missing entity performs no state mutation, so the stripe will discover a later join.
        check(states.needsApply(unit, army, revision), "missing entity remains pending");
        check(states.size() == 0, "missing entity creates no acknowledgement row");

        states.markRunning(unit, army, revision);
        check(!states.needsApply(unit, army, revision), "loaded entity owns current revision");
        check(states.invalidate(unit), "chunk unload invalidates the runtime binding");
        check(states.needsApply(unit, army, revision), "rebind replays unchanged persisted order");
        states.markRunning(unit, army, revision);
        states.markPending(unit, army, revision + 1L);
        check(!states.markTerminalIfCurrent(unit, army, revision), "late old-instance completion is stale");
        check(states.needsApply(unit, army, revision + 1L), "successor revision remains pending");
    }

    private static void dimensionAdmission(
            StableDimensionTable dimensions,
            int overworldId,
            int netherId,
            ResourceLocation overworld,
            ResourceLocation nether) {
        check(OrderExecutionPolicy.targetInLevel(dimensions, overworldId, overworld),
                "same-dimension target admitted");
        check(!OrderExecutionPolicy.targetInLevel(dimensions, overworldId, nether),
                "cross-dimension target fails closed");
        check(!OrderExecutionPolicy.targetInLevel(
                        dimensions, PackedArmyEcs.UNKNOWN_DIMENSION, overworld),
                "legacy unknown dimension fails closed");
        check(OrderExecutionPolicy.targetInLevel(dimensions, netherId, nether),
                "second stable dimension admitted");
    }

    private static void ownerAuthorization() {
        int army = 0x0010_0020;
        PackedArmyControllers controllers = new PackedArmyControllers(1);
        controllers.put(army, 11L, 22L, true);
        check(ArmyCommandAuthorization.canControl(
                        new ArmyCommandAuthority(11L, 22L, true, false), controllers, army),
                "persisted controller accepted");
        check(!ArmyCommandAuthorization.canControl(
                        new ArmyCommandAuthority(11L, 23L, true, false), controllers, army),
                "different player rejected");
        check(!ArmyCommandAuthorization.canControl(
                        new ArmyCommandAuthority(0L, 0L, false, false), controllers, army),
                "identity-free non-operator rejected");
        check(ArmyCommandAuthorization.canControl(
                        ArmyCommandAuthority.operatorWithoutIdentity(), controllers, army),
                "operator override accepted");
    }

    private static void boundedPhysicalDelegation() {
        FakeNavigation navigation = new FakeNavigation();
        BlockPos target = new BlockPos(80, 70, 90);
        int cooldown = BoundedNavigationDelegation.retarget(
                navigation, null, target, 0.5D, 0);
        check(navigation.navigateCalls == 1, "missing route delegates to Millenaire navigation");
        check(target.equals(navigation.destination), "delegated route uses committed endpoint");

        navigation.destination = BlockPos.ZERO;
        for (int tick = 0; tick < BoundedNavigationDelegation.RETARGET_INTERVAL_TICKS; tick++) {
            cooldown = BoundedNavigationDelegation.retarget(
                    navigation, null, target, 0.5D, cooldown);
        }
        check(navigation.navigateCalls == 1, "stolen destination is not retargeted every tick");
        cooldown = BoundedNavigationDelegation.retarget(
                navigation, null, target, 0.5D, cooldown);
        check(navigation.navigateCalls == 2, "stolen destination is retargeted after bounded cooldown");
        BoundedNavigationDelegation.stop(navigation, null);
        check(navigation.destination == null, "hold/cancel stops bridge-owned navigation");
    }

    private static long thousandUnitSteadyStateAllocationBudget() {
        PackedUnitExecutionState states = new PackedUnitExecutionState();
        states.reserve(UNIT_COUNT);
        int army = 0x0010_0001;
        long revision = 3L;
        for (int slot = 0; slot < UNIT_COUNT; slot++) {
            states.markRunning((1 << 20) | slot, army, revision);
        }

        // Warm the exact steady-state stripe before measuring ThreadMXBean allocation.
        long checksum = scanAcknowledged(states, army, revision, 20);
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        long threadId = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(threadId);
        checksum += scanAcknowledged(states, army, revision, 1_000);
        long allocated = bean.getThreadAllocatedBytes(threadId) - before;
        check(checksum == 0L, "all warm units remain acknowledged");
        check(allocated <= 1_024L, "1000-unit steady stripes allocated " + allocated + " B");
        return allocated;
    }

    private static long scanAcknowledged(
            PackedUnitExecutionState states, int army, long revision, int passes) {
        long pending = 0L;
        for (int pass = 0; pass < passes; pass++) {
            for (int slot = 0; slot < UNIT_COUNT; slot++) {
                if (states.needsApply((1 << 20) | slot, army, revision)) {
                    pending++;
                }
            }
        }
        return pending;
    }

    private static void telemetryTransitions() {
        OrderExecutionTelemetry telemetry = new OrderExecutionTelemetry();
        telemetry.accepted();
        telemetry.executing();
        telemetry.arrived();
        telemetry.blocked();
        check(telemetry.acceptedCount() == 1L, "accepted telemetry");
        check(telemetry.executingCount() == 1L, "executing telemetry");
        check(telemetry.arrivedCount() == 1L, "arrived telemetry");
        check(telemetry.blockedCount() == 1L, "blocked telemetry");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FakeNavigation implements VillagerNavDriver {
        private BlockPos destination;
        private int navigateCalls;

        @Override
        public void navigateTo(MillVillager villager, BlockPos target, double speed) {
            destination = target;
            navigateCalls++;
        }

        @Override
        public void tick(MillVillager villager, org.millenaire.village.Village village) {}

        @Override
        public boolean isArrived(MillVillager villager, double distance) {
            return false;
        }

        @Override
        public boolean isArrivedHorizontal(MillVillager villager, double distance) {
            return false;
        }

        @Override
        public boolean isArrivedSameFloor(MillVillager villager, double distance) {
            return false;
        }

        @Override
        public boolean isAbandoned() {
            return false;
        }

        @Override
        public void stop(MillVillager villager) {
            destination = null;
        }

        @Override
        public BlockPos getDestination() {
            return destination;
        }

        @Override
        public NavDiagnostics getDiagnostics() {
            return null;
        }
    }
}
