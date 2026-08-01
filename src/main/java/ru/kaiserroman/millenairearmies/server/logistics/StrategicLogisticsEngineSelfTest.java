package ru.kaiserroman.millenairearmies.server.logistics;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import ru.kaiserroman.millenairearmies.model.ArmyOrderType;
import ru.kaiserroman.millenairearmies.model.LogisticsRequestStatus;
import ru.kaiserroman.millenairearmies.persistence.PackedCommandState;
import ru.kaiserroman.millenairearmies.persistence.PackedLogisticsState;

/** Standalone deterministic replay and fixed-storage test; run through Gradle with assertions. */
public final class StrategicLogisticsEngineSelfTest {
    private static final int ARMY = 0x0010_0001;

    private StrategicLogisticsEngineSelfTest() {
    }

    public static void main(String[] args) {
        Scenario first = new Scenario();
        Scenario second = new Scenario();
        exercise(first);
        exercise(second);
        partialReservationAndCancellation();
        inTransitRequestSurvivesEngineRestart();
        externallyAppendedRowsGrowRuntimeColumnsSafely();
        dormantStorageIsLazyAndTickAllocationFree();

        check(first.engine.deterministicHash() == second.engine.deterministicHash(), "replay hash");
        check(first.logistics.capacity() == first.engine.requestRuntimeCapacity(), "request storage tiers align");
        check(first.logistics.capacity() < 64 + 1, "small scenario did not reserve production maximum");
        check(first.commands.capacity() == 8, "command array did not grow");
        check(first.engine.eventCapacity() == 16, "event ring allocated once at first ingress");
        check(first.engine.supplyTableCapacity() > 0, "supply table allocated at first supply ingress");
        check(first.engine.queuedEventCount() == 0, "event ring drained");
        check(first.dirtyCount > 0, "persistent mutations marked dirty");
        System.out.println("Strategic logistics self-test passed: hash="
                + Long.toUnsignedString(first.engine.deterministicHash(), 16)
                + ", rows=" + first.engine.mutatedRowCount()
                + ", events=" + first.engine.processedEventCount());
    }

    private static void dormantStorageIsLazyAndTickAllocationFree() {
        // Warm class/lambda initialization outside the measured construction.
        StrategicLogisticsEngine warm = new StrategicLogisticsEngine(8, 4, 4, 1, 1);
        warm.start(new PackedLogisticsState(), new PackedCommandState(0), () -> {});
        warm.stop();

        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        long beforeConstruction = bean.getThreadAllocatedBytes(thread);
        PackedLogisticsState logistics = new PackedLogisticsState();
        PackedCommandState commands = new PackedCommandState(0);
        StrategicLogisticsEngine engine = new StrategicLogisticsEngine(32_768, 8_192, 2_048, 16, 128);
        engine.start(logistics, commands, () -> {});
        long constructionBytes = bean.getThreadAllocatedBytes(thread) - beforeConstruction;

        check(logistics.capacity() == 0, "dormant persisted logistics has no columns");
        check(engine.requestRuntimeCapacity() == 0, "dormant engine has no request runtime arrays");
        check(engine.eventCapacity() == 0, "dormant engine has no event ring");
        check(engine.supplyTableCapacity() == 0, "dormant engine has no supply table");
        check(constructionBytes < 64 * 1024L, "dormant engine unexpectedly reserved large storage");

        for (long tick = 0; tick < 2_000; tick++) {
            engine.tick(tick);
        }
        long beforeTicks = bean.getThreadAllocatedBytes(thread);
        for (long tick = 2_000; tick < 102_000; tick++) {
            engine.tick(tick);
        }
        long tickBytes = bean.getThreadAllocatedBytes(thread) - beforeTicks;
        // Graal may attribute one late compilation/runtime bookkeeping object to this thread; the
        // important bound is no allocation proportional to the 100,000 tick calls.
        check(tickBytes <= 1_024L, "zero-ingress ticks allocated " + tickBytes + " bytes");

        long request = engine.requestSupply(1, ARMY, 2, 4, 0, 3L, 102_000L, (byte) 1);
        check(request > 0L, "first lazy request accepted");
        check(engine.requestRuntimeCapacity() == 64, "first request allocated only the first tier");
        check(logistics.capacity() == 64, "persisted and runtime request tiers match");
        check(engine.publishSupply(1, 0, 2, 4), "first lazy event accepted");
        check(engine.eventCapacity() == 2_048, "event ring allocated only on ingress");
        check(engine.supplyTableCapacity() > 0, "supply table allocated only on ingress");
        engine.stop();
        check(engine.requestRuntimeCapacity() == 0, "stop released request runtime storage");
        check(engine.eventCapacity() == 0, "stop released event storage");
        check(engine.supplyTableCapacity() == 0, "stop released supply storage");
        check(engine.requestStorageGrowthCount() == 0, "stop reset request storage growth count");
        System.out.println("dormant logistics: startup=" + constructionBytes
                + " B, 100k zero-ingress ticks=" + tickBytes
                + " B, first request tier=64 rows");
    }

    private static void externallyAppendedRowsGrowRuntimeColumnsSafely() {
        PackedLogisticsState logistics = new PackedLogisticsState();
        StrategicLogisticsEngine engine = new StrategicLogisticsEngine(128, 8, 8, 4, 4);
        engine.start(logistics, new PackedCommandState(0), () -> {});

        long request = logistics.add(4, ARMY, 7, 3, 0, 17L, 0L, (byte) 1);
        check(engine.requestRuntimeCapacity() == 0, "external append stays lazy until engine access");
        check(engine.reservedAmount(request) == 0, "external row getter is safe");
        check(engine.requestRuntimeCapacity() >= logistics.size(), "getter covered external rows");
        engine.tick(0L);
        check(engine.requestPlannedProgress(0) == 0, "external row tick and projection are safe");
        engine.stop();
    }

    private static void partialReservationAndCancellation() {
        Scenario scenario = new Scenario();
        long request = scenario.engine.requestSupply(3, ARMY, 9, 10, 0, 44L, 0L, (byte) 1);
        check(scenario.engine.publishSupply(3, 0, 9, 4), "partial stock queued");
        scenario.engine.tick(0L);
        check(scenario.logistics.statusCodeAt(0) == LogisticsRequestStatus.PENDING.code(), "partial stays pending");
        check(scenario.engine.reservedAmount(request) == 4, "partial amount reserved");
        check(scenario.engine.publishSupply(3, 0, 9, 10), "replenishment queued");
        for (long tick = 1L; tick <= 4L; tick++) {
            scenario.engine.tick(tick);
        }
        check(scenario.logistics.statusCodeAt(0) == LogisticsRequestStatus.ASSIGNED.code(), "replenished request assigned");
        check(scenario.engine.cancel(request), "cancellation queued");
        scenario.engine.tick(5L);
        check(scenario.logistics.statusCodeAt(0) == LogisticsRequestStatus.CANCELLED.code(), "request cancelled");
        check(scenario.engine.reservedAmount(request) == 0, "cancel released reservation");
    }

    private static void inTransitRequestSurvivesEngineRestart() {
        PackedLogisticsState logistics = new PackedLogisticsState();
        PackedCommandState commands = new PackedCommandState(4);
        commands.add(ARMY, 2, ArmyOrderType.RESUPPLY.code(), 0, 0L, 0L, 0L, 0L, 0L, (byte) 0);
        StrategicLogisticsEngine before = new StrategicLogisticsEngine(16, 4, 8, 4, 4);
        before.start(logistics, commands, () -> {});
        long request = before.requestSupply(2, ARMY, 6, 7, 0, 99L, 0L, (byte) 1);
        check(before.publishSupply(2, 0, 6, 7), "restart stock queued");
        before.tick(0L);
        check(before.dispatch(request), "restart shipment queued");
        before.tick(1L);
        check(logistics.statusCodeAt(0) == LogisticsRequestStatus.IN_TRANSIT.code(), "restart shipment in transit");
        before.stop();

        StrategicLogisticsEngine after = new StrategicLogisticsEngine(16, 4, 8, 4, 4);
        after.start(logistics, commands, () -> {});
        check(after.deliver(request, 7), "post-restart delivery queued");
        after.tick(2L);
        check(logistics.statusCodeAt(0) == LogisticsRequestStatus.FULFILLED.code(), "post-restart delivery accepted");
        check(commands.size() == 0, "post-restart order retired");
    }

    private static void exercise(Scenario scenario) {
        long resupplyOrder = scenario.commands.add(
                ARMY,
                7,
                ArmyOrderType.RESUPPLY.code(),
                0,
                0L,
                0L,
                0L,
                0L,
                0L,
                (byte) 0);
        long request = scenario.engine.requestSupply(7, ARMY, 4, 12, 0, 1234L, 0L, (byte) 5);
        check(request > 0L, "request created");
        check(scenario.engine.commandProgress(resupplyOrder) == 0, "new order progress");

        check(scenario.engine.publishSupply(7, 0, 4, 12), "stock snapshot queued");
        scenario.engine.tick(0L);
        check(scenario.logistics.statusCodeAt(0) == LogisticsRequestStatus.ASSIGNED.code(), "request assigned");
        check(scenario.engine.reservedAmount(request) == 12, "full reservation");
        check(scenario.engine.requestPlannedProgress(0) == StrategicLogisticsEngine.PROGRESS_COMPLETE,
                "planned progress includes reservation");

        check(scenario.engine.dispatch(request), "dispatch queued");
        scenario.engine.tick(1L);
        check(scenario.logistics.statusCodeAt(0) == LogisticsRequestStatus.IN_TRANSIT.code(), "in transit");
        check(scenario.engine.inTransitAmount(request) == 12, "shipment tracked");

        check(scenario.engine.deliver(request, 4), "partial delivery queued");
        scenario.engine.tick(2L);
        check(scenario.logistics.fulfilledAmountAt(0) == 4, "partial delivery applied");
        check(scenario.engine.requestProgress(0) == 3_333, "request progress");
        check(scenario.engine.commandProgress(resupplyOrder) == 3_333, "order progress");

        check(scenario.engine.deliver(request, 8), "final delivery queued");
        scenario.engine.tick(3L);
        check(scenario.logistics.statusCodeAt(0) == LogisticsRequestStatus.FULFILLED.code(), "fulfilled");
        check(scenario.commands.size() == 0, "completed resupply command retired");
        long completedHash = scenario.engine.deterministicHash();
        scenario.engine.tick(3L);
        check(scenario.engine.deterministicHash() == completedHash, "duplicate tick is idempotent");

        // Exercise ring wrap and every request stripe without growing any backing store.
        for (long tick = 4L; tick < 2_052L; tick++) {
            check(scenario.engine.publishSupply(7, 0, 4, 12), "steady supply event admitted");
            scenario.engine.tick(tick);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class Scenario {
        private final PackedLogisticsState logistics = new PackedLogisticsState();
        private final PackedCommandState commands = new PackedCommandState(8);
        private final StrategicLogisticsEngine engine = new StrategicLogisticsEngine(64, 8, 16, 4, 4);
        private int dirtyCount;

        private Scenario() {
            engine.start(logistics, commands, () -> dirtyCount++);
        }
    }
}
