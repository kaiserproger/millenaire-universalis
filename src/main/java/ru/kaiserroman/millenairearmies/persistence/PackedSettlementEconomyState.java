package ru.kaiserroman.millenairearmies.persistence;

import java.util.Arrays;
import net.minecraft.nbt.CompoundTag;

/**
 * Persisted primitive state for the coarse NPC-settlement economy.
 *
 * <p>Settlement rows are stable and shipments are an append-only write-ahead log. A shipment is
 * debited before it becomes {@link #SHIPMENT_IN_TRANSIT}; completion and rollback are one-way
 * transitions, so replaying a tick after a save or crash cannot mint or delete commodities.</p>
 */
public final class PackedSettlementEconomyState {
    public static final int FOOD = 0;
    public static final int IRON = 1;
    public static final int LEATHER = 2;
    public static final int ARROWS = 3;
    public static final int COMMODITY_COUNT = 4;
    public static final byte SHIPMENT_IN_TRANSIT = 1;
    public static final byte SHIPMENT_DELIVERED = 2;
    public static final byte SHIPMENT_ROLLED_BACK = 3;

    private static final int MIN_SETTLEMENT_CAPACITY = 16;
    private static final int MIN_SHIPMENT_CAPACITY = 16;

    private final int[] commodityItemKeys = {-1, -1, -1, -1};

    private long[] villageMost = new long[0];
    private long[] villageLeast = new long[0];
    private int[] factionIds = new int[0];
    private int[] dimensionIds = new int[0];
    private long[] positions = new long[0];
    private long[] nextDueTicks = new long[0];
    private byte[] active = new byte[0];
    private byte[] observed = new byte[0];
    private int[] stock = new int[0];
    private int[] physicalObserved = new int[0];
    private int[] reserves = new int[0];
    private int[] production = new int[0];
    private int[] consumption = new int[0];
    private int settlementCount;

    private long[] shipmentIds = new long[0];
    private int[] shipmentOrigins = new int[0];
    private int[] shipmentDestinations = new int[0];
    private byte[] shipmentCommodities = new byte[0];
    private int[] shipmentAmounts = new int[0];
    private long[] shipmentDueTicks = new long[0];
    private byte[] shipmentStatuses = new byte[0];
    private int shipmentCount;
    private long nextShipmentId = 1L;
    private long physicalReconciliationShortfall;

    public void configureCommodityKeys(int food, int iron, int leather, int arrows) {
        int[] requested = {food, iron, leather, arrows};
        for (int commodity = 0; commodity < COMMODITY_COUNT; commodity++) {
            if (requested[commodity] < 0) {
                throw new IllegalArgumentException("Commodity item keys must be non-negative");
            }
            int existing = commodityItemKeys[commodity];
            if (existing >= 0 && existing != requested[commodity]) {
                throw new IllegalStateException("Persisted commodity dictionary changed at slot " + commodity);
            }
            commodityItemKeys[commodity] = requested[commodity];
        }
    }

    public int commodityItemKey(int commodity) {
        checkCommodity(commodity);
        return commodityItemKeys[commodity];
    }

    public int commodityForItemKey(int itemKey) {
        for (int commodity = 0; commodity < COMMODITY_COUNT; commodity++) {
            if (commodityItemKeys[commodity] == itemKey) {
                return commodity;
            }
        }
        return -1;
    }

    public int upsertSettlement(
            long uuidMost,
            long uuidLeast,
            int factionId,
            int dimensionId,
            long position,
            long nextDueTick) {
        if ((uuidMost | uuidLeast) == 0L || factionId < 0 || dimensionId < 0 || nextDueTick < 0L) {
            throw new IllegalArgumentException("Invalid settlement identity or schedule");
        }
        int row = findSettlement(uuidMost, uuidLeast);
        if (row < 0) {
            ensureSettlementCapacity(settlementCount + 1);
            row = settlementCount++;
            villageMost[row] = uuidMost;
            villageLeast[row] = uuidLeast;
            nextDueTicks[row] = nextDueTick;
        } else if (nextDueTicks[row] == 0L) {
            nextDueTicks[row] = nextDueTick;
        }
        factionIds[row] = factionId;
        dimensionIds[row] = dimensionId;
        positions[row] = position;
        active[row] = 1;
        return row;
    }

    public int findSettlement(long uuidMost, long uuidLeast) {
        for (int row = 0; row < settlementCount; row++) {
            if (villageMost[row] == uuidMost && villageLeast[row] == uuidLeast) {
                return row;
            }
        }
        return -1;
    }

    public void markAllSettlementsInactive() {
        Arrays.fill(active, 0, settlementCount, (byte) 0);
    }

    public void configureRates(
            int row, int commodity, int reserve, int producedPerCycle, int consumedPerCycle) {
        checkSettlement(row);
        checkCommodity(commodity);
        if (reserve < 0 || producedPerCycle < 0 || consumedPerCycle < 0) {
            throw new IllegalArgumentException("Settlement rates must be non-negative");
        }
        int cell = cell(row, commodity);
        reserves[cell] = reserve;
        production[cell] = producedPerCycle;
        consumption[cell] = consumedPerCycle;
    }

    /** Applies a revision snapshot as a delta, preserving strategic debits/credits since last scan. */
    public boolean observePhysicalStock(int row, int commodity, int absoluteStock) {
        checkSettlement(row);
        checkCommodity(commodity);
        if (absoluteStock < 0) {
            return false;
        }
        int cell = cell(row, commodity);
        if (observed[cell] == 0) {
            stock[cell] = absoluteStock;
            physicalObserved[cell] = absoluteStock;
            observed[cell] = 1;
            return true;
        }
        int previous = physicalObserved[cell];
        physicalObserved[cell] = absoluteStock;
        long reconciled = (long) stock[cell] + absoluteStock - previous;
        if (reconciled < 0L) {
            physicalReconciliationShortfall = saturatedAdd(
                    physicalReconciliationShortfall, -reconciled);
        }
        stock[cell] = saturatedStock(reconciled);
        return absoluteStock != previous;
    }

    public void applyCycles(int row, long cycles) {
        checkSettlement(row);
        if (cycles <= 0L) {
            return;
        }
        for (int commodity = 0; commodity < COMMODITY_COUNT; commodity++) {
            int cell = cell(row, commodity);
            long delta = (long) production[cell] * cycles - (long) consumption[cell] * cycles;
            stock[cell] = saturatedStock((long) stock[cell] + delta);
        }
    }

    public boolean tryDebit(int row, int commodity, int amount) {
        checkSettlement(row);
        checkCommodity(commodity);
        if (amount <= 0) {
            return false;
        }
        int cell = cell(row, commodity);
        if (stock[cell] < amount) {
            return false;
        }
        stock[cell] -= amount;
        return true;
    }

    public void credit(int row, int commodity, int amount) {
        checkSettlement(row);
        checkCommodity(commodity);
        if (amount <= 0) {
            throw new IllegalArgumentException("Credit must be positive");
        }
        int cell = cell(row, commodity);
        stock[cell] = saturatedStock((long) stock[cell] + amount);
    }

    public long addShipment(int origin, int destination, int commodity, int amount, long dueTick) {
        checkSettlement(origin);
        checkSettlement(destination);
        checkCommodity(commodity);
        if (origin == destination || amount <= 0 || dueTick < 0L) {
            throw new IllegalArgumentException("Invalid shipment");
        }
        if (nextShipmentId == Long.MAX_VALUE) {
            throw new IllegalStateException("Shipment identity space exhausted");
        }
        ensureShipmentCapacity(shipmentCount + 1);
        int row = shipmentCount++;
        return writeShipment(row, origin, destination, commodity, amount, dueTick);
    }

    /** Recycles a terminal internal-WAL row without growing the configured shipment bound. */
    public long replaceTerminalShipmentAt(
            int row, int origin, int destination, int commodity, int amount, long dueTick) {
        checkShipment(row);
        if (shipmentStatuses[row] == SHIPMENT_IN_TRANSIT) {
            throw new IllegalStateException("Cannot recycle an in-transit shipment row");
        }
        checkSettlement(origin);
        checkSettlement(destination);
        checkCommodity(commodity);
        if (origin == destination || amount <= 0 || dueTick < 0L) {
            throw new IllegalArgumentException("Invalid shipment");
        }
        return writeShipment(row, origin, destination, commodity, amount, dueTick);
    }

    private long writeShipment(
            int row, int origin, int destination, int commodity, int amount, long dueTick) {
        if (nextShipmentId == Long.MAX_VALUE) {
            throw new IllegalStateException("Shipment identity space exhausted");
        }
        long id = nextShipmentId++;
        shipmentIds[row] = id;
        shipmentOrigins[row] = origin;
        shipmentDestinations[row] = destination;
        shipmentCommodities[row] = (byte) commodity;
        shipmentAmounts[row] = amount;
        shipmentDueTicks[row] = dueTick;
        shipmentStatuses[row] = SHIPMENT_IN_TRANSIT;
        return id;
    }

    public boolean deliverShipmentAt(int row) {
        checkShipment(row);
        if (shipmentStatuses[row] != SHIPMENT_IN_TRANSIT) {
            return false;
        }
        credit(shipmentDestinations[row], shipmentCommodities[row], shipmentAmounts[row]);
        shipmentStatuses[row] = SHIPMENT_DELIVERED;
        return true;
    }

    public boolean rollbackShipmentAt(int row) {
        checkShipment(row);
        if (shipmentStatuses[row] != SHIPMENT_IN_TRANSIT) {
            return false;
        }
        credit(shipmentOrigins[row], shipmentCommodities[row], shipmentAmounts[row]);
        shipmentStatuses[row] = SHIPMENT_ROLLED_BACK;
        return true;
    }

    public int settlementCount() { return settlementCount; }
    public int shipmentCount() { return shipmentCount; }
    public int settlementCapacity() { return villageMost.length; }
    public int shipmentCapacity() { return shipmentIds.length; }
    public long physicalReconciliationShortfall() { return physicalReconciliationShortfall; }
    public long villageMostAt(int row) { checkSettlement(row); return villageMost[row]; }
    public long villageLeastAt(int row) { checkSettlement(row); return villageLeast[row]; }
    public int factionIdAt(int row) { checkSettlement(row); return factionIds[row]; }
    public int dimensionIdAt(int row) { checkSettlement(row); return dimensionIds[row]; }
    public long positionAt(int row) { checkSettlement(row); return positions[row]; }
    public boolean activeAt(int row) { checkSettlement(row); return active[row] != 0; }
    public boolean activeAt(int row, boolean value) {
        checkSettlement(row);
        byte next = value ? (byte) 1 : (byte) 0;
        if (active[row] == next) return false;
        active[row] = next;
        return true;
    }
    public long nextDueTickAt(int row) { checkSettlement(row); return nextDueTicks[row]; }
    public void nextDueTickAt(int row, long value) { checkSettlement(row); nextDueTicks[row] = value; }
    public int stockAt(int row, int commodity) { checkSettlement(row); checkCommodity(commodity); return stock[cell(row, commodity)]; }
    public int reserveAt(int row, int commodity) { checkSettlement(row); checkCommodity(commodity); return reserves[cell(row, commodity)]; }
    public int surplusAt(int row, int commodity) { return Math.max(0, stockAt(row, commodity) - reserveAt(row, commodity)); }
    public int deficitAt(int row, int commodity) { return Math.max(0, reserveAt(row, commodity) - stockAt(row, commodity)); }
    public long shipmentIdAt(int row) { checkShipment(row); return shipmentIds[row]; }
    public int shipmentOriginAt(int row) { checkShipment(row); return shipmentOrigins[row]; }
    public int shipmentDestinationAt(int row) { checkShipment(row); return shipmentDestinations[row]; }
    public int shipmentCommodityAt(int row) { checkShipment(row); return shipmentCommodities[row]; }
    public int shipmentAmountAt(int row) { checkShipment(row); return shipmentAmounts[row]; }
    public long shipmentDueTickAt(int row) { checkShipment(row); return shipmentDueTicks[row]; }
    public byte shipmentStatusAt(int row) { checkShipment(row); return shipmentStatuses[row]; }

    public int estimatedPrimitiveBytes() {
        long bytes = (long) commodityItemKeys.length * Integer.BYTES
                + (long) villageMost.length * (Long.BYTES * 3 + Integer.BYTES * 2 + Byte.BYTES)
                + (long) observed.length * Byte.BYTES
                + (long) stock.length * Integer.BYTES * 5
                + (long) shipmentIds.length * (Long.BYTES * 2 + Integer.BYTES * 3 + Byte.BYTES * 2);
        return bytes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) bytes;
    }

    public long deterministicHash() {
        long hash = 0xcbf29ce484222325L;
        for (int row = 0; row < settlementCount; row++) {
            hash = mix(hash, villageMost[row]);
            hash = mix(hash, villageLeast[row]);
            hash = mix(hash, factionIds[row]);
            hash = mix(hash, dimensionIds[row]);
            hash = mix(hash, nextDueTicks[row]);
            hash = mix(hash, active[row]);
            for (int commodity = 0; commodity < COMMODITY_COUNT; commodity++) {
                int cell = cell(row, commodity);
                hash = mix(hash, stock[cell]);
                hash = mix(hash, reserves[cell]);
                hash = mix(hash, production[cell]);
                hash = mix(hash, consumption[cell]);
            }
        }
        for (int row = 0; row < shipmentCount; row++) {
            hash = mix(hash, shipmentIds[row]);
            hash = mix(hash, shipmentOrigins[row]);
            hash = mix(hash, shipmentDestinations[row]);
            hash = mix(hash, shipmentCommodities[row]);
            hash = mix(hash, shipmentAmounts[row]);
            hash = mix(hash, shipmentDueTicks[row]);
            hash = mix(hash, shipmentStatuses[row]);
        }
        hash = mix(hash, physicalReconciliationShortfall);
        return hash;
    }

    public CompoundTag save(CompoundTag tag) {
        tag.putIntArray("CommodityItems", commodityItemKeys);
        tag.putInt("SettlementCount", settlementCount);
        tag.putLongArray("VillageMost", Arrays.copyOf(villageMost, settlementCount));
        tag.putLongArray("VillageLeast", Arrays.copyOf(villageLeast, settlementCount));
        tag.putIntArray("Factions", Arrays.copyOf(factionIds, settlementCount));
        tag.putIntArray("Dimensions", Arrays.copyOf(dimensionIds, settlementCount));
        tag.putLongArray("Positions", Arrays.copyOf(positions, settlementCount));
        tag.putLongArray("NextDueTicks", Arrays.copyOf(nextDueTicks, settlementCount));
        tag.putByteArray("Active", Arrays.copyOf(active, settlementCount));
        int cells = settlementCount * COMMODITY_COUNT;
        tag.putByteArray("Observed", Arrays.copyOf(observed, cells));
        tag.putIntArray("Stock", Arrays.copyOf(stock, cells));
        tag.putIntArray("PhysicalObserved", Arrays.copyOf(physicalObserved, cells));
        tag.putIntArray("Reserves", Arrays.copyOf(reserves, cells));
        tag.putIntArray("Production", Arrays.copyOf(production, cells));
        tag.putIntArray("Consumption", Arrays.copyOf(consumption, cells));

        tag.putInt("ShipmentCount", shipmentCount);
        tag.putLong("NextShipmentId", nextShipmentId);
        tag.putLong("PhysicalReconciliationShortfall", physicalReconciliationShortfall);
        tag.putLongArray("ShipmentIds", Arrays.copyOf(shipmentIds, shipmentCount));
        tag.putIntArray("ShipmentOrigins", Arrays.copyOf(shipmentOrigins, shipmentCount));
        tag.putIntArray("ShipmentDestinations", Arrays.copyOf(shipmentDestinations, shipmentCount));
        tag.putByteArray("ShipmentCommodities", Arrays.copyOf(shipmentCommodities, shipmentCount));
        tag.putIntArray("ShipmentAmounts", Arrays.copyOf(shipmentAmounts, shipmentCount));
        tag.putLongArray("ShipmentDueTicks", Arrays.copyOf(shipmentDueTicks, shipmentCount));
        tag.putByteArray("ShipmentStatuses", Arrays.copyOf(shipmentStatuses, shipmentCount));
        return tag;
    }

    public static PackedSettlementEconomyState load(
            CompoundTag tag, int maximumSettlements, int maximumShipments) {
        if (maximumSettlements <= 0 || maximumShipments <= 0) {
            throw new IllegalArgumentException("Economy persistence limits must be positive");
        }
        PackedSettlementEconomyState state = new PackedSettlementEconomyState();
        int[] items = required(tag.getIntArray("CommodityItems"), COMMODITY_COUNT, "CommodityItems");
        System.arraycopy(items, 0, state.commodityItemKeys, 0, COMMODITY_COUNT);

        int settlements = checkedCount(tag.getInt("SettlementCount"), maximumSettlements, "SettlementCount");
        state.ensureSettlementCapacity(settlements);
        state.settlementCount = settlements;
        state.villageMost = copy(tag.getLongArray("VillageMost"), settlements, state.villageMost, "VillageMost");
        state.villageLeast = copy(tag.getLongArray("VillageLeast"), settlements, state.villageLeast, "VillageLeast");
        state.factionIds = copy(tag.getIntArray("Factions"), settlements, state.factionIds, "Factions");
        state.dimensionIds = copy(tag.getIntArray("Dimensions"), settlements, state.dimensionIds, "Dimensions");
        state.positions = copy(tag.getLongArray("Positions"), settlements, state.positions, "Positions");
        state.nextDueTicks = copy(tag.getLongArray("NextDueTicks"), settlements, state.nextDueTicks, "NextDueTicks");
        state.active = copy(tag.getByteArray("Active"), settlements, state.active, "Active");
        int cells = settlements * COMMODITY_COUNT;
        state.observed = copy(tag.getByteArray("Observed"), cells, state.observed, "Observed");
        state.stock = copy(tag.getIntArray("Stock"), cells, state.stock, "Stock");
        state.physicalObserved = copy(tag.getIntArray("PhysicalObserved"), cells, state.physicalObserved, "PhysicalObserved");
        state.reserves = copy(tag.getIntArray("Reserves"), cells, state.reserves, "Reserves");
        state.production = copy(tag.getIntArray("Production"), cells, state.production, "Production");
        state.consumption = copy(tag.getIntArray("Consumption"), cells, state.consumption, "Consumption");

        int shipments = checkedCount(tag.getInt("ShipmentCount"), maximumShipments, "ShipmentCount");
        state.ensureShipmentCapacity(shipments);
        state.shipmentCount = shipments;
        state.nextShipmentId = tag.getLong("NextShipmentId");
        state.physicalReconciliationShortfall = tag.getLong("PhysicalReconciliationShortfall");
        if (state.nextShipmentId <= 0L) {
            throw new IllegalArgumentException("NextShipmentId must be positive");
        }
        if (state.physicalReconciliationShortfall < 0L) {
            throw new IllegalArgumentException("Physical reconciliation shortfall must be non-negative");
        }
        state.shipmentIds = copy(tag.getLongArray("ShipmentIds"), shipments, state.shipmentIds, "ShipmentIds");
        state.shipmentOrigins = copy(tag.getIntArray("ShipmentOrigins"), shipments, state.shipmentOrigins, "ShipmentOrigins");
        state.shipmentDestinations = copy(tag.getIntArray("ShipmentDestinations"), shipments, state.shipmentDestinations, "ShipmentDestinations");
        state.shipmentCommodities = copy(tag.getByteArray("ShipmentCommodities"), shipments, state.shipmentCommodities, "ShipmentCommodities");
        state.shipmentAmounts = copy(tag.getIntArray("ShipmentAmounts"), shipments, state.shipmentAmounts, "ShipmentAmounts");
        state.shipmentDueTicks = copy(tag.getLongArray("ShipmentDueTicks"), shipments, state.shipmentDueTicks, "ShipmentDueTicks");
        state.shipmentStatuses = copy(tag.getByteArray("ShipmentStatuses"), shipments, state.shipmentStatuses, "ShipmentStatuses");
        state.validateRestored();
        return state;
    }

    private void validateRestored() {
        long highestId = 0L;
        for (int row = 0; row < settlementCount; row++) {
            if ((villageMost[row] | villageLeast[row]) == 0L
                    || factionIds[row] < 0
                    || dimensionIds[row] < 0
                    || nextDueTicks[row] < 0L
                    || active[row] < 0
                    || active[row] > 1) {
                throw new IllegalArgumentException("Invalid persisted settlement row " + row);
            }
            for (int commodity = 0; commodity < COMMODITY_COUNT; commodity++) {
                int cell = cell(row, commodity);
                if (stock[cell] < 0 || physicalObserved[cell] < 0 || reserves[cell] < 0
                        || production[cell] < 0 || consumption[cell] < 0
                        || observed[cell] < 0 || observed[cell] > 1) {
                    throw new IllegalArgumentException("Invalid persisted settlement commodity cell " + cell);
                }
            }
        }
        int idCapacity = 1;
        while (idCapacity < Math.max(2, shipmentCount * 2)) idCapacity <<= 1;
        long[] seenShipmentIds = new long[idCapacity];
        int idMask = idCapacity - 1;
        for (int row = 0; row < shipmentCount; row++) {
            long id = shipmentIds[row];
            int idSlot = mixId(id) & idMask;
            while (seenShipmentIds[idSlot] != 0L && seenShipmentIds[idSlot] != id) {
                idSlot = idSlot + 1 & idMask;
            }
            boolean duplicateId = seenShipmentIds[idSlot] == id;
            seenShipmentIds[idSlot] = id;
            if (id <= 0L || duplicateId
                    || shipmentOrigins[row] < 0 || shipmentOrigins[row] >= settlementCount
                    || shipmentDestinations[row] < 0 || shipmentDestinations[row] >= settlementCount
                    || shipmentOrigins[row] == shipmentDestinations[row]
                    || shipmentCommodities[row] < 0 || shipmentCommodities[row] >= COMMODITY_COUNT
                    || shipmentAmounts[row] <= 0 || shipmentDueTicks[row] < 0L
                    || shipmentStatuses[row] < SHIPMENT_IN_TRANSIT
                    || shipmentStatuses[row] > SHIPMENT_ROLLED_BACK) {
                throw new IllegalArgumentException("Invalid persisted shipment row " + row);
            }
            highestId = Math.max(highestId, id);
        }
        if (nextShipmentId <= highestId) {
            throw new IllegalArgumentException("NextShipmentId precedes persisted WAL rows");
        }
    }

    private void ensureSettlementCapacity(int required) {
        if (required <= villageMost.length) {
            return;
        }
        int capacity = grow(villageMost.length, required, MIN_SETTLEMENT_CAPACITY);
        villageMost = Arrays.copyOf(villageMost, capacity);
        villageLeast = Arrays.copyOf(villageLeast, capacity);
        factionIds = Arrays.copyOf(factionIds, capacity);
        dimensionIds = Arrays.copyOf(dimensionIds, capacity);
        positions = Arrays.copyOf(positions, capacity);
        nextDueTicks = Arrays.copyOf(nextDueTicks, capacity);
        active = Arrays.copyOf(active, capacity);
        int cells = capacity * COMMODITY_COUNT;
        observed = Arrays.copyOf(observed, cells);
        stock = Arrays.copyOf(stock, cells);
        physicalObserved = Arrays.copyOf(physicalObserved, cells);
        reserves = Arrays.copyOf(reserves, cells);
        production = Arrays.copyOf(production, cells);
        consumption = Arrays.copyOf(consumption, cells);
    }

    private void ensureShipmentCapacity(int required) {
        if (required <= shipmentIds.length) {
            return;
        }
        int capacity = grow(shipmentIds.length, required, MIN_SHIPMENT_CAPACITY);
        shipmentIds = Arrays.copyOf(shipmentIds, capacity);
        shipmentOrigins = Arrays.copyOf(shipmentOrigins, capacity);
        shipmentDestinations = Arrays.copyOf(shipmentDestinations, capacity);
        shipmentCommodities = Arrays.copyOf(shipmentCommodities, capacity);
        shipmentAmounts = Arrays.copyOf(shipmentAmounts, capacity);
        shipmentDueTicks = Arrays.copyOf(shipmentDueTicks, capacity);
        shipmentStatuses = Arrays.copyOf(shipmentStatuses, capacity);
    }

    private static int grow(int current, int required, int minimum) {
        int capacity = Math.max(minimum, current);
        while (capacity < required) {
            int next = capacity + (capacity >>> 1);
            if (next <= capacity) {
                return required;
            }
            capacity = next;
        }
        return capacity;
    }

    private static int saturatedStock(long value) {
        if (value <= 0L) return 0;
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static int cell(int row, int commodity) {
        return row * COMMODITY_COUNT + commodity;
    }

    private static int checkedCount(int value, int maximum, String key) {
        if (value < 0 || value > maximum) {
            throw new IllegalArgumentException(key + " outside 0.." + maximum + ": " + value);
        }
        return value;
    }

    private static int[] required(int[] source, int count, String key) {
        if (source.length != count) throw new IllegalArgumentException(key + " length mismatch");
        return source;
    }

    private static int[] copy(int[] source, int count, int[] target, String key) {
        if (source.length != count) throw new IllegalArgumentException(key + " length mismatch");
        System.arraycopy(source, 0, target, 0, count);
        return target;
    }

    private static long[] copy(long[] source, int count, long[] target, String key) {
        if (source.length != count) throw new IllegalArgumentException(key + " length mismatch");
        System.arraycopy(source, 0, target, 0, count);
        return target;
    }

    private static byte[] copy(byte[] source, int count, byte[] target, String key) {
        if (source.length != count) throw new IllegalArgumentException(key + " length mismatch");
        System.arraycopy(source, 0, target, 0, count);
        return target;
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }

    private static int mixId(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        return (int) (value ^ value >>> 32);
    }

    private void checkSettlement(int row) {
        if (row < 0 || row >= settlementCount) throw new IndexOutOfBoundsException("Settlement row " + row);
    }

    private void checkShipment(int row) {
        if (row < 0 || row >= shipmentCount) throw new IndexOutOfBoundsException("Shipment row " + row);
    }

    private static void checkCommodity(int commodity) {
        if (commodity < 0 || commodity >= COMMODITY_COUNT) {
            throw new IllegalArgumentException("Commodity " + commodity);
        }
    }
}
