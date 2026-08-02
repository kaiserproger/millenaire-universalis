package ru.kaiserroman.millenairearmies.server.economy;

import java.util.Arrays;
import java.util.Objects;
import ru.kaiserroman.millenairearmies.integration.millenaire.RecruitmentSupplyPolicy;
import ru.kaiserroman.millenairearmies.persistence.PackedSettlementEconomyState;
import ru.kaiserroman.millenairearmies.server.logistics.SupplyInventoryAccess;
import ru.kaiserroman.millenairearmies.server.logistics.SupplyMutationSink;

/**
 * Bounded, server-thread coarse economy for NPC settlements.
 *
 * <p>Players remain the political drivers: this engine declares no wars, alliances, targets or
 * army orders. It only advances configured production/consumption, protects local reserves and
 * balances same-faction/same-dimension deficits over coarse routes. Due work catches up in a
 * single arithmetic step, so unloaded settlements progress without entities, chunks or players.</p>
 *
 * <p>Strategic commits intentionally do not impersonate physical couriers or mutate Millenaire
 * block entities. Every later physical observation is applied as a delta to the already-debited
 * ledger, so a chest cannot replenish a strategic debit merely because it still contains the
 * visual stack. If Millenaire consumes more than the remaining logical balance, stock is clamped
 * to zero and the persisted reconciliation-shortfall metric records the conflict.</p>
 */
public final class SettlementEconomyEngine
        implements SupplyMutationSink, RecruitmentSupplyPolicy {
    public static final int FOOD = 0;
    public static final int IRON = 1;
    public static final int LEATHER = 2;
    public static final int ARROWS = 3;
    public static final int SETTLEMENT_LIMIT_REACHED = -1;

    private static final int[] RECRUITMENT_COST = {8, 1, 2, 8};

    private final PackedSettlementEconomyState state;
    private final Runnable dirtyMarker;
    private final int economyIntervalTicks;
    private final int settlementsPerTick;
    private final int shipmentsPerTick;
    private final int routesPerTick;
    private final int maximumSettlements;
    private final int maximumShipments;
    private final int maximumRouteBlocks;
    private final Thread ownerThread;

    private int settlementCursor;
    private int shipmentCursor;
    private int routeCursor;
    private int originSearchCursor;
    private int shipmentReuseCursor;
    private int[] incomingAmounts = new int[0];
    private long lastGameTime = Long.MIN_VALUE;
    private long earliestDueTick = Long.MAX_VALUE;
    private boolean projectionReady;
    private int lastTickWorkUnits;
    private long producedCycles;
    private long createdShipments;
    private long deliveredShipments;
    private long rolledBackShipments;
    private long rejectedRoutes;

    public SettlementEconomyEngine(
            PackedSettlementEconomyState state,
            Runnable dirtyMarker,
            int economyIntervalTicks,
            int settlementsPerTick,
            int shipmentsPerTick,
            int routesPerTick,
            int maximumSettlements,
            int maximumShipments,
            int maximumRouteBlocks) {
        this.state = Objects.requireNonNull(state, "state");
        this.dirtyMarker = Objects.requireNonNull(dirtyMarker, "dirtyMarker");
        if (economyIntervalTicks <= 0 || settlementsPerTick <= 0 || shipmentsPerTick <= 0
                || routesPerTick <= 0 || maximumSettlements <= 0
                || maximumShipments <= 0 || maximumRouteBlocks <= 0) {
            throw new IllegalArgumentException("Settlement economy bounds must be positive");
        }
        this.economyIntervalTicks = economyIntervalTicks;
        this.settlementsPerTick = settlementsPerTick;
        this.shipmentsPerTick = shipmentsPerTick;
        this.routesPerTick = routesPerTick;
        this.maximumSettlements = maximumSettlements;
        this.maximumShipments = maximumShipments;
        this.maximumRouteBlocks = maximumRouteBlocks;
        ownerThread = Thread.currentThread();
        if (state.settlementCount() > maximumSettlements || state.shipmentCount() > maximumShipments) {
            throw new IllegalArgumentException("Persisted settlement economy exceeds configured bounds");
        }
        ensureIncomingCapacity(state.settlementCount());
        rebuildIncomingAmounts();
        recomputeEarliestDue();
    }

    public int registerSettlement(
            long villageMost,
            long villageLeast,
            int factionId,
            int dimensionId,
            long position,
            long gameTime) {
        requireOwnerThread();
        int existing = state.findSettlement(villageMost, villageLeast);
        if (existing < 0 && state.settlementCount() == maximumSettlements) {
            return SETTLEMENT_LIMIT_REACHED;
        }
        int previousCount = state.settlementCount();
        long firstDue = saturatedAdd(gameTime, economyIntervalTicks);
        int row = state.upsertSettlement(
                villageMost, villageLeast, factionId, dimensionId, position, firstDue);
        ensureIncomingCapacity(state.settlementCount());
        earliestDueTick = Math.min(earliestDueTick, state.nextDueTickAt(row));
        if (state.settlementCount() != previousCount) {
            dirtyMarker.run();
        }
        return row;
    }

    public void beginSettlementReconciliation() {
        requireOwnerThread();
        // Existing rows stay authoritative until the bounded capture finishes. The bridge then
        // deactivates only rows absent from the complete revision, avoiding false rollbacks while
        // a large village set is spread across ticks.
    }

    public void finishSettlementReconciliation(long[] seenRevisions, long revision) {
        requireOwnerThread();
        boolean changed = false;
        for (int row = 0; row < state.settlementCount(); row++) {
            boolean seen = row < seenRevisions.length && seenRevisions[row] == revision;
            changed |= state.activeAt(row, seen);
        }
        if (changed) dirtyMarker.run();
    }

    public void configureRates(
            int row, int commodity, int reserve, int production, int consumption) {
        requireOwnerThread();
        state.configureRates(row, commodity, reserve, production, consumption);
        dirtyMarker.run();
    }

    /** A negative value means the physical source is unloaded; the last sound observation wins. */
    public boolean observePhysicalStock(int row, int commodity, int stock) {
        requireOwnerThread();
        if (stock < 0) {
            return false;
        }
        boolean changed = state.observePhysicalStock(row, commodity, stock);
        if (changed) {
            dirtyMarker.run();
        }
        return changed;
    }

    public void projectionReady() {
        requireOwnerThread();
        projectionReady = true;
    }

    public boolean isProjectionReady() {
        return projectionReady;
    }

    /** Advances only when a persisted due tick exists; duplicate hooks for one tick are ignored. */
    public void tick(long gameTime) {
        requireOwnerThread();
        if (gameTime < 0L) throw new IllegalArgumentException("Game time must be non-negative");
        if (gameTime == lastGameTime) return;
        if (lastGameTime != Long.MIN_VALUE && gameTime < lastGameTime) {
            throw new IllegalStateException("Game time moved backwards");
        }
        lastGameTime = gameTime;
        lastTickWorkUnits = 0;
        if (gameTime < earliestDueTick) {
            return;
        }

        boolean mutated = processDueShipments(gameTime);
        boolean economyDue = processDueSettlements(gameTime);
        mutated |= economyDue;
        if (economyDue) {
            mutated |= planRoutes(gameTime);
        }
        recomputeEarliestDue();
        if (mutated) {
            dirtyMarker.run();
        }
    }

    private boolean processDueSettlements(long gameTime) {
        int count = state.settlementCount();
        if (count == 0) return false;
        boolean mutated = false;
        int budget = Math.min(settlementsPerTick, count);
        for (int inspected = 0; inspected < budget; inspected++) {
            if (settlementCursor == count) settlementCursor = 0;
            int row = settlementCursor++;
            lastTickWorkUnits++;
            long due = state.nextDueTickAt(row);
            if (due > gameTime) continue;
            if (!state.activeAt(row)) {
                state.nextDueTickAt(row, saturatedAdd(gameTime, economyIntervalTicks));
                mutated = true;
                continue;
            }
            long cycles = (gameTime - due) / economyIntervalTicks + 1L;
            state.applyCycles(row, cycles);
            state.nextDueTickAt(row, saturatedAdd(due, saturatedMultiply(cycles, economyIntervalTicks)));
            producedCycles = saturatedAdd(producedCycles, cycles);
            mutated = true;
        }
        return mutated;
    }

    private boolean processDueShipments(long gameTime) {
        int count = state.shipmentCount();
        if (count == 0) return false;
        boolean mutated = false;
        int budget = Math.min(shipmentsPerTick, count);
        for (int inspected = 0; inspected < budget; inspected++) {
            if (shipmentCursor == count) shipmentCursor = 0;
            int row = shipmentCursor++;
            lastTickWorkUnits++;
            if (state.shipmentStatusAt(row) != PackedSettlementEconomyState.SHIPMENT_IN_TRANSIT
                    || state.shipmentDueTickAt(row) > gameTime) {
                continue;
            }
            int destination = state.shipmentDestinationAt(row);
            if (state.activeAt(destination)) {
                if (state.deliverShipmentAt(row)) {
                    subtractIncoming(destination, state.shipmentCommodityAt(row), state.shipmentAmountAt(row));
                    deliveredShipments++;
                }
            } else if (state.rollbackShipmentAt(row)) {
                subtractIncoming(destination, state.shipmentCommodityAt(row), state.shipmentAmountAt(row));
                rolledBackShipments++;
            }
            mutated = true;
        }
        return mutated;
    }

    private boolean planRoutes(long gameTime) {
        int routes = 0;
        boolean mutated = false;
        int settlementCount = state.settlementCount();
        int destinationBudget = Math.min(settlementsPerTick, settlementCount);
        for (int inspected = 0; inspected < destinationBudget && routes < routesPerTick; inspected++) {
            if (routeCursor == settlementCount) routeCursor = 0;
            int destination = routeCursor++;
            lastTickWorkUnits++;
            if (!state.activeAt(destination)) continue;
            for (int commodity = 0; commodity < PackedSettlementEconomyState.COMMODITY_COUNT
                    && routes < routesPerTick; commodity++) {
                int deficit = state.deficitAt(destination, commodity)
                        - incomingAmount(destination, commodity);
                if (deficit <= 0) continue;
                int origin = bestOrigin(destination, commodity);
                if (origin < 0) continue;
                int amount = Math.min(deficit, state.surplusAt(origin, commodity));
                if (amount <= 0 || !state.tryDebit(origin, commodity, amount)) continue;
                long due = saturatedAdd(gameTime, routeTravelTicks(origin, destination));
                if (!recordShipment(origin, destination, commodity, amount, due)) {
                    state.credit(origin, commodity, amount);
                    rejectedRoutes++;
                    return mutated;
                }
                addIncoming(destination, commodity, amount);
                earliestDueTick = Math.min(earliestDueTick, due);
                routes++;
                createdShipments++;
                mutated = true;
            }
        }
        return mutated;
    }

    private boolean recordShipment(
            int origin, int destination, int commodity, int amount, long dueTick) {
        if (state.shipmentCount() < maximumShipments) {
            state.addShipment(origin, destination, commodity, amount, dueTick);
            return true;
        }
        int count = state.shipmentCount();
        for (int inspected = 0; inspected < count; inspected++) {
            lastTickWorkUnits++;
            if (shipmentReuseCursor == count) shipmentReuseCursor = 0;
            int row = shipmentReuseCursor++;
            if (state.shipmentStatusAt(row) != PackedSettlementEconomyState.SHIPMENT_IN_TRANSIT) {
                state.replaceTerminalShipmentAt(row, origin, destination, commodity, amount, dueTick);
                return true;
            }
        }
        return false;
    }

    private int bestOrigin(int destination, int commodity) {
        int best = -1;
        long bestDistance = Long.MAX_VALUE;
        int count = state.settlementCount();
        int budget = Math.min(settlementsPerTick, count);
        for (int inspected = 0; inspected < budget; inspected++) {
            if (originSearchCursor == count) originSearchCursor = 0;
            int origin = originSearchCursor++;
            lastTickWorkUnits++;
            if (origin == destination || !state.activeAt(origin)
                    || state.factionIdAt(origin) != state.factionIdAt(destination)
                    || state.dimensionIdAt(origin) != state.dimensionIdAt(destination)
                    || state.surplusAt(origin, commodity) == 0) {
                continue;
            }
            long distance = blockDistance(state.positionAt(origin), state.positionAt(destination));
            if (distance <= maximumRouteBlocks && distance < bestDistance) {
                best = origin;
                bestDistance = distance;
            }
        }
        return best;
    }

    private int incomingAmount(int destination, int commodity) {
        return incomingAmounts[destination * PackedSettlementEconomyState.COMMODITY_COUNT + commodity];
    }

    private long routeTravelTicks(int origin, int destination) {
        long blocks = blockDistance(state.positionAt(origin), state.positionAt(destination));
        return Math.max(40L, Math.min(2_400L, 40L + blocks * 2L));
    }

    /** Publishes only reserves-excluded stock, so army reservations cannot starve a settlement. */
    public int absoluteAvailableStock(int factionId, int dimensionId, int itemKey) {
        requireOwnerThread();
        int commodity = state.commodityForItemKey(itemKey);
        if (commodity < 0 || !projectionReady) return SupplyInventoryAccess.UNAVAILABLE;
        long total = 0L;
        for (int row = 0; row < state.settlementCount(); row++) {
            if (state.activeAt(row) && state.factionIdAt(row) == factionId
                    && state.dimensionIdAt(row) == dimensionId) {
                total += state.surplusAt(row, commodity);
                if (total >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
            }
        }
        return (int) total;
    }

    @Override
    public boolean tryDebit(int factionId, int dimensionId, int itemKey, int amount) {
        requireOwnerThread();
        int commodity = state.commodityForItemKey(itemKey);
        if (commodity < 0 || amount <= 0) return false;
        int available = absoluteAvailableStock(factionId, dimensionId, itemKey);
        if (available < amount) return false;
        int remaining = amount;
        for (int row = 0; row < state.settlementCount() && remaining > 0; row++) {
            if (!state.activeAt(row) || state.factionIdAt(row) != factionId
                    || state.dimensionIdAt(row) != dimensionId) continue;
            int debit = Math.min(remaining, state.surplusAt(row, commodity));
            if (debit > 0) {
                state.tryDebit(row, commodity, debit);
                remaining -= debit;
            }
        }
        if (remaining != 0) throw new IllegalStateException("Economy aggregate debit lost its preflight stock");
        dirtyMarker.run();
        return true;
    }

    @Override
    public void credit(int factionId, int dimensionId, int itemKey, int amount) {
        requireOwnerThread();
        int commodity = state.commodityForItemKey(itemKey);
        if (commodity < 0 || amount <= 0) return;
        for (int row = 0; row < state.settlementCount(); row++) {
            if (state.activeAt(row) && state.factionIdAt(row) == factionId
                    && state.dimensionIdAt(row) == dimensionId) {
                state.credit(row, commodity, amount);
                dirtyMarker.run();
                return;
            }
        }
        throw new IllegalStateException("No settlement can receive a compensating supply credit");
    }

    @Override
    public boolean tryConsumeRecruitmentKits(long villageMost, long villageLeast, int count) {
        requireOwnerThread();
        int row = state.findSettlement(villageMost, villageLeast);
        if (row < 0 || !state.activeAt(row) || !projectionReady || count <= 0) return false;
        for (int commodity = 0; commodity < RECRUITMENT_COST.length; commodity++) {
            long amount = (long) RECRUITMENT_COST[commodity] * count;
            if (amount > Integer.MAX_VALUE
                    || (long) state.stockAt(row, commodity) - amount < state.reserveAt(row, commodity)) {
                return false;
            }
        }
        for (int commodity = 0; commodity < RECRUITMENT_COST.length; commodity++) {
            int amount = RECRUITMENT_COST[commodity] * count;
            if (!state.tryDebit(row, commodity, amount)) {
                throw new IllegalStateException("Recruitment kit preflight/commit mismatch");
            }
        }
        dirtyMarker.run();
        return true;
    }

    @Override
    public void refundRecruitmentKits(long villageMost, long villageLeast, int count) {
        requireOwnerThread();
        int row = state.findSettlement(villageMost, villageLeast);
        if (row < 0 || count <= 0) {
            throw new IllegalStateException("Recruitment kit refund lost its settlement");
        }
        for (int commodity = 0; commodity < RECRUITMENT_COST.length; commodity++) {
            state.credit(row, commodity, RECRUITMENT_COST[commodity] * count);
        }
        dirtyMarker.run();
    }

    public int stock(long villageMost, long villageLeast, int commodity) {
        int row = state.findSettlement(villageMost, villageLeast);
        return row < 0 ? 0 : state.stockAt(row, commodity);
    }

    public int surplus(long villageMost, long villageLeast, int commodity) {
        int row = state.findSettlement(villageMost, villageLeast);
        return row < 0 ? 0 : state.surplusAt(row, commodity);
    }

    public int deficit(long villageMost, long villageLeast, int commodity) {
        int row = state.findSettlement(villageMost, villageLeast);
        return row < 0 ? 0 : state.deficitAt(row, commodity);
    }

    /** Reserve-relative faction readiness sent to the existing army supply percentage UI. */
    public int factionSupplyPercent(int factionId) {
        requireOwnerThread();
        long reserve = 0L;
        long surplus = 0L;
        boolean found = false;
        for (int row = 0; row < state.settlementCount(); row++) {
            if (!state.activeAt(row) || state.factionIdAt(row) != factionId) continue;
            found = true;
            for (int commodity = 0; commodity < PackedSettlementEconomyState.COMMODITY_COUNT; commodity++) {
                reserve += state.reserveAt(row, commodity);
                surplus += state.surplusAt(row, commodity);
            }
        }
        if (!found || !projectionReady) return 0;
        if (reserve == 0L) return 100;
        if (surplus >= reserve) return 100;
        return (int) (surplus * 100L / reserve);
    }

    public PackedSettlementEconomyState state() { return state; }
    public int lastTickWorkUnits() { return lastTickWorkUnits; }
    public int maximumTickWorkUnits() {
        long value = (long) settlementsPerTick
                + shipmentsPerTick
                + maximumShipments
                + settlementsPerTick
                + (long) settlementsPerTick
                        * PackedSettlementEconomyState.COMMODITY_COUNT
                        * settlementsPerTick;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
    public long producedCycles() { return producedCycles; }
    public long createdShipmentCount() { return createdShipments; }
    public long deliveredShipmentCount() { return deliveredShipments; }
    public long rolledBackShipmentCount() { return rolledBackShipments; }
    public long rejectedRouteCount() { return rejectedRoutes; }
    public long physicalReconciliationShortfall() { return state.physicalReconciliationShortfall(); }
    public int estimatedRuntimePrimitiveBytes() { return incomingAmounts.length * Integer.BYTES; }

    private void ensureIncomingCapacity(int settlements) {
        int cells = settlements * PackedSettlementEconomyState.COMMODITY_COUNT;
        if (cells <= incomingAmounts.length) return;
        int capacity = Math.max(64, incomingAmounts.length);
        while (capacity < cells) capacity += Math.max(1, capacity >>> 1);
        incomingAmounts = Arrays.copyOf(incomingAmounts, capacity);
    }

    private void rebuildIncomingAmounts() {
        Arrays.fill(incomingAmounts, 0);
        for (int row = 0; row < state.shipmentCount(); row++) {
            if (state.shipmentStatusAt(row) == PackedSettlementEconomyState.SHIPMENT_IN_TRANSIT) {
                addIncoming(
                        state.shipmentDestinationAt(row),
                        state.shipmentCommodityAt(row),
                        state.shipmentAmountAt(row));
            }
        }
    }

    private void addIncoming(int settlement, int commodity, int amount) {
        int cell = settlement * PackedSettlementEconomyState.COMMODITY_COUNT + commodity;
        long value = (long) incomingAmounts[cell] + amount;
        incomingAmounts[cell] = value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private void subtractIncoming(int settlement, int commodity, int amount) {
        int cell = settlement * PackedSettlementEconomyState.COMMODITY_COUNT + commodity;
        if (incomingAmounts[cell] < amount) {
            throw new IllegalStateException("Settlement incoming shipment counter underflow");
        }
        incomingAmounts[cell] -= amount;
    }

    private void recomputeEarliestDue() {
        long earliest = Long.MAX_VALUE;
        for (int row = 0; row < state.settlementCount(); row++) {
            earliest = Math.min(earliest, state.nextDueTickAt(row));
        }
        for (int row = 0; row < state.shipmentCount(); row++) {
            if (state.shipmentStatusAt(row) == PackedSettlementEconomyState.SHIPMENT_IN_TRANSIT) {
                earliest = Math.min(earliest, state.shipmentDueTickAt(row));
            }
        }
        earliestDueTick = earliest;
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Settlement economy commits must run on its owning server thread");
        }
    }

    private static long blockDistance(long left, long right) {
        long dx = Math.abs((long) unpackX(left) - unpackX(right));
        long dz = Math.abs((long) unpackZ(left) - unpackZ(right));
        return Math.max(dx, dz);
    }

    private static int unpackX(long packed) { return (int) (packed >> 38); }
    private static int unpackZ(long packed) { return (int) (packed << 26 >> 38); }

    private static long saturatedMultiply(long left, int right) {
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
