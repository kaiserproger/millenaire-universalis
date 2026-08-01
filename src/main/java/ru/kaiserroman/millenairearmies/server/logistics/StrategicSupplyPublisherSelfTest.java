package ru.kaiserroman.millenairearmies.server.logistics;

import ru.kaiserroman.millenairearmies.model.LogisticsRequestStatus;
import ru.kaiserroman.millenairearmies.persistence.PackedCommandState;
import ru.kaiserroman.millenairearmies.persistence.PackedLogisticsState;

/** Standalone bounded read-only inventory projection test; run through Gradle with assertions. */
public final class StrategicSupplyPublisherSelfTest {
    private static final int ARMY = 0x0010_0001;

    private StrategicSupplyPublisherSelfTest() {}

    public static void main(String[] args) {
        demandDrivenReadOnlyPublishing();
        boundedKeyAdmission();
        fullEngineRingRetriesWithoutLosingDirtyKey();
        unavailableSourceRetainsSnapshot();
        terminalRowsDoNotRegisterOrSweep();
        System.out.println("Strategic supply publisher self-test passed");
    }

    private static void demandDrivenReadOnlyPublishing() {
        PackedLogisticsState requests = new PackedLogisticsState();
        PackedCommandState commands = new PackedCommandState(4);
        StrategicLogisticsEngine engine = new StrategicLogisticsEngine(16, 8, 8, 1, 8);
        engine.start(requests, commands, () -> {});
        FakeInventory inventory = new FakeInventory();
        inventory.set(3, 0, 4, 12);
        inventory.set(3, 0, 5, 9);
        StrategicSupplyPublisher publisher =
                new StrategicSupplyPublisher(engine, requests, inventory, 8, 1, 1, 100);

        long first = engine.requestSupply(3, ARMY, 4, 5, 0, 10L, 0L, (byte) 1);
        long second = engine.requestSupply(3, ARMY, 4, 6, 0, 10L, 0L, (byte) 1);
        engine.requestSupply(3, ARMY, 5, 3, 0, 10L, 0L, (byte) 1);

        publisher.tick(0L);
        engine.tick(0L);
        publisher.tick(1L);
        engine.tick(1L);
        publisher.tick(2L);
        engine.tick(2L);

        check(publisher.trackedKeyCount() == 2, "duplicate demand key deduplicated");
        check(inventory.reads == 2, "one physical read per demanded aggregate key");
        check(requests.statusCodeAt(requests.findRow(first)) == LogisticsRequestStatus.ASSIGNED.code(),
                "first request assigned");
        check(requests.statusCodeAt(requests.findRow(second)) == LogisticsRequestStatus.ASSIGNED.code(),
                "second request assigned");
        check(inventory.stock(3, 0, 4) == 12, "publication never mutates physical stock");
        check(requests.statusCodeAt(requests.findRow(first)) == LogisticsRequestStatus.ASSIGNED.code(),
                "publisher never reports physical dispatch");

        check(engine.cancel(first), "first cancellation queued");
        check(engine.cancel(second), "second cancellation queued");
        engine.tick(3L);
        publisher.tick(3L);

        int tableCapacity = publisher.tableCapacity();
        int queueCapacity = publisher.dirtyQueueCapacity();
        for (long tick = 4L; tick < 512L; tick++) {
            publisher.tick(tick);
            engine.tick(tick);
        }
        check(publisher.activeKeyCount() == 1, "terminal requests retired from active demand");
        check(inventory.readsFor(3, 0, 4) == 1, "inactive key excluded from safety sweeps");
        check(publisher.tableCapacity() == tableCapacity, "publisher table stayed fixed");
        check(publisher.dirtyQueueCapacity() == queueCapacity, "publisher queue stayed fixed");
        check(publisher.unchangedSnapshotCount() > 0, "unchanged safety snapshot suppressed");
    }

    private static void boundedKeyAdmission() {
        PackedLogisticsState requests = new PackedLogisticsState();
        StrategicLogisticsEngine engine = new StrategicLogisticsEngine(8, 4, 4, 1, 4);
        engine.start(requests, new PackedCommandState(1), () -> {});
        FakeInventory inventory = new FakeInventory();
        StrategicSupplyPublisher publisher =
                new StrategicSupplyPublisher(engine, requests, inventory, 1, 8, 1, 100);
        engine.requestSupply(1, ARMY, 1, 1, 0, 0L, 0L, (byte) 0);
        engine.requestSupply(1, ARMY, 2, 1, 0, 0L, 0L, (byte) 0);
        publisher.tick(0L);
        check(publisher.trackedKeyCount() == 1, "tracked key hard cap");
        check(publisher.rejectedKeyCount() == 1, "overflow key counted");
    }

    private static void fullEngineRingRetriesWithoutLosingDirtyKey() {
        PackedLogisticsState requests = new PackedLogisticsState();
        StrategicLogisticsEngine engine = new StrategicLogisticsEngine(4, 4, 1, 1, 1);
        engine.start(requests, new PackedCommandState(1), () -> {});
        FakeInventory inventory = new FakeInventory();
        inventory.set(2, 0, 7, 4);
        StrategicSupplyPublisher publisher =
                new StrategicSupplyPublisher(engine, requests, inventory, 4, 4, 1, 100);
        engine.requestSupply(2, ARMY, 7, 2, 0, 0L, 0L, (byte) 0);
        check(engine.publishSupply(9, 0, 9, 1), "engine event ring prefilled");
        publisher.tick(0L);
        check(publisher.queuedKeyCount() == 1, "rejected snapshot retained dirty key");
        engine.tick(0L);
        publisher.tick(1L);
        check(publisher.acceptedSnapshotCount() == 1, "dirty snapshot retried");
        engine.tick(1L);
    }

    private static void unavailableSourceRetainsSnapshot() {
        PackedLogisticsState requests = new PackedLogisticsState();
        StrategicLogisticsEngine engine = new StrategicLogisticsEngine(4, 4, 4, 1, 4);
        engine.start(requests, new PackedCommandState(1), () -> {});
        FakeInventory inventory = new FakeInventory();
        inventory.unavailable = true;
        StrategicSupplyPublisher publisher =
                new StrategicSupplyPublisher(engine, requests, inventory, 4, 4, 1, 10);
        engine.requestSupply(2, ARMY, 3, 2, 0, 0L, 0L, (byte) 0);
        publisher.tick(0L);
        check(publisher.unavailableReadCount() == 1, "unavailable read recorded");
        check(publisher.acceptedSnapshotCount() == 0, "unavailable source did not publish false zero");
    }

    private static void terminalRowsDoNotRegisterOrSweep() {
        PackedLogisticsState requests = new PackedLogisticsState();
        StrategicLogisticsEngine engine = new StrategicLogisticsEngine(8, 8, 8, 1, 8);
        engine.start(requests, new PackedCommandState(1), () -> {});

        long fulfilled = engine.requestSupply(4, ARMY, 1, 1, 0, 0L, 0L, (byte) 0);
        check(engine.publishSupply(4, 0, 1, 1), "terminal setup stock queued");
        engine.tick(0L);
        check(engine.dispatch(fulfilled), "terminal setup dispatch queued");
        engine.tick(1L);
        check(engine.deliver(fulfilled, 1), "terminal setup delivery queued");
        engine.tick(2L);

        long cancelled = engine.requestSupply(4, ARMY, 2, 1, 0, 0L, 2L, (byte) 0);
        check(engine.cancel(cancelled), "terminal setup cancellation queued");
        engine.tick(3L);
        engine.requestSupply(4, ARMY, 3, 1, 0, 0L, 3L, (byte) 0);

        FakeInventory inventory = new FakeInventory();
        inventory.set(4, 0, 1, 10);
        inventory.set(4, 0, 2, 10);
        inventory.set(4, 0, 3, 10);
        StrategicSupplyPublisher publisher =
                new StrategicSupplyPublisher(engine, requests, inventory, 8, 8, 2, 10);
        publisher.tick(4L);
        engine.tick(4L);

        check(publisher.trackedKeyCount() == 1, "pre-existing terminal rows not registered");
        check(publisher.activeKeyCount() == 1, "only live request contributes demand");
        check(inventory.readsFor(4, 0, 1) == 0, "fulfilled key never scanned");
        check(inventory.readsFor(4, 0, 2) == 0, "cancelled key never scanned");
        check(inventory.readsFor(4, 0, 3) == 1, "live key scanned");

        check(engine.cancel(requests.requestIdAt(2)), "live request cancellation queued");
        engine.tick(5L);
        publisher.tick(5L);
        int readsAfterRetirement = inventory.reads;
        for (long tick = 6L; tick < 64L; tick++) {
            publisher.tick(tick);
            engine.tick(tick);
        }
        check(publisher.activeKeyCount() == 0, "last terminal request retired demand key");
        check(inventory.reads == readsAfterRetirement, "no sweeps executed without active demand");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FakeInventory implements SupplyInventoryAccess {
        private final int[] factions = new int[8];
        private final int[] dimensions = new int[8];
        private final int[] items = new int[8];
        private final int[] stocks = new int[8];
        private final int[] readCounts = new int[8];
        private int size;
        private int reads;
        private boolean unavailable;

        void set(int factionId, int dimensionId, int itemKey, int stock) {
            int row = find(factionId, dimensionId, itemKey);
            if (row < 0) {
                row = size++;
                factions[row] = factionId;
                dimensions[row] = dimensionId;
                items[row] = itemKey;
            }
            stocks[row] = stock;
        }

        int stock(int factionId, int dimensionId, int itemKey) {
            int row = find(factionId, dimensionId, itemKey);
            return row < 0 ? 0 : stocks[row];
        }

        int readsFor(int factionId, int dimensionId, int itemKey) {
            int row = find(factionId, dimensionId, itemKey);
            return row < 0 ? 0 : readCounts[row];
        }

        @Override
        public int absoluteStock(int factionId, int dimensionId, int itemKey) {
            reads++;
            int row = find(factionId, dimensionId, itemKey);
            if (row >= 0) {
                readCounts[row]++;
            }
            return unavailable ? UNAVAILABLE : (row < 0 ? 0 : stocks[row]);
        }

        private int find(int factionId, int dimensionId, int itemKey) {
            for (int row = 0; row < size; row++) {
                if (factions[row] == factionId && dimensions[row] == dimensionId && items[row] == itemKey) {
                    return row;
                }
            }
            return -1;
        }
    }
}
