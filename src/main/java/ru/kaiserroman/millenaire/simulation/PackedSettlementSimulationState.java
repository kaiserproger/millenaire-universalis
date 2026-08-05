package ru.kaiserroman.millenaire.simulation;

import java.util.Arrays;

/**
 * Primitive-array strategic state. It deliberately contains no Minecraft, NeoForge or Millenaire
 * objects, so it can be persisted by an adapter and replayed in deterministic tests.
 */
public final class PackedSettlementSimulationState {
    private final int maximumSettlements;
    private final int commodityCount;

    private int size;
    private long revision;
    private long observationEpoch;

    private long[] settlementIds;
    private int[] cultureKeys;
    private long[] realmIds;
    private long[] regionKeys;
    private long[] observedPopulations;
    private long[] housingCapacities;
    private int[] buildingCounts;
    private int[] productiveBuildings;
    private int[] marketAccess;
    private int[] security;
    private int[] damage;
    private int[] education;
    private int[] geographicCapacity;
    private int[] fertility;
    private int[] specialization;

    private long[] populations;
    private int[] productivity;
    private int[] stability;
    private int[] attractiveness;
    private int[] productiveCapital;
    private byte[] statuses;
    private byte[] tiers;
    private int[] declineCycles;
    private int[] missingCycles;
    private int[] foundingCooldowns;
    private long[] nextDueTicks;
    private long[] seenEpochs;
    private byte[] physicalPresence;

    /** Persisted fixed-point residuals make historical rates independent from technical cadence. */
    private long[] historicalTimeRemainders;
    private long[] populationGrowthRemainders;
    private long[] populationObservationRemainders;
    private long[] capitalMoveRemainders;
    private long[] productivityMoveRemainders;
    private long[] stabilityMoveRemainders;
    private long[] attractivenessMoveRemainders;

    private long[] stocks;
    private int[] priceIndices;
    private long[] netFlows;
    private long[] flowRemainders;
    private long[] priceMoveRemainders;

    public PackedSettlementSimulationState(int maximumSettlements, int commodityCount) {
        if (maximumSettlements <= 0 || commodityCount <= 0 || commodityCount > 64) {
            throw new IllegalArgumentException("Invalid packed simulation bounds");
        }
        this.maximumSettlements = maximumSettlements;
        this.commodityCount = commodityCount;
        int capacity = Math.min(16, maximumSettlements);
        settlementIds = new long[capacity];
        cultureKeys = new int[capacity];
        realmIds = new long[capacity];
        regionKeys = new long[capacity];
        observedPopulations = new long[capacity];
        housingCapacities = new long[capacity];
        buildingCounts = new int[capacity];
        productiveBuildings = new int[capacity];
        marketAccess = new int[capacity];
        security = new int[capacity];
        damage = new int[capacity];
        education = new int[capacity];
        geographicCapacity = new int[capacity];
        fertility = new int[capacity];
        specialization = new int[capacity];
        populations = new long[capacity];
        productivity = new int[capacity];
        stability = new int[capacity];
        attractiveness = new int[capacity];
        productiveCapital = new int[capacity];
        statuses = new byte[capacity];
        tiers = new byte[capacity];
        declineCycles = new int[capacity];
        missingCycles = new int[capacity];
        foundingCooldowns = new int[capacity];
        nextDueTicks = new long[capacity];
        seenEpochs = new long[capacity];
        physicalPresence = new byte[capacity];
        historicalTimeRemainders = new long[capacity];
        populationGrowthRemainders = new long[capacity];
        populationObservationRemainders = new long[capacity];
        capitalMoveRemainders = new long[capacity];
        productivityMoveRemainders = new long[capacity];
        stabilityMoveRemainders = new long[capacity];
        attractivenessMoveRemainders = new long[capacity];
        stocks = new long[capacity * commodityCount];
        priceIndices = new int[capacity * commodityCount];
        netFlows = new long[capacity * commodityCount];
        flowRemainders = new long[capacity * commodityCount];
        priceMoveRemainders = new long[capacity * commodityCount];
    }

    public long beginObservation() {
        if (observationEpoch == Long.MAX_VALUE) {
            Arrays.fill(seenEpochs, 0L);
            observationEpoch = 1L;
        } else {
            observationEpoch++;
        }
        return observationEpoch;
    }

    public int observe(SettlementObservation observation, long firstDueTick) {
        if (observation == null) throw new NullPointerException("observation");
        if (firstDueTick < 0L) throw new IllegalArgumentException("Negative firstDueTick");
        int row = find(observation.settlementId());
        boolean mutated = false;
        if (row < 0) {
            if (size == maximumSettlements) return -1;
            ensureCapacity(size + 1);
            row = size++;
            settlementIds[row] = observation.settlementId();
            populations[row] = observation.population();
            productivity[row] = 500;
            stability[row] = 600;
            attractiveness[row] = 500;
            productiveCapital[row] = 250;
            statuses[row] = (byte) SettlementStatus.ACTIVE.ordinal();
            tiers[row] = (byte) SettlementTier.forPopulation(observation.population()).ordinal();
            nextDueTicks[row] = firstDueTick;
            mutated = true;
        }
        mutated |= cultureKeys[row] != observation.cultureKey()
                || realmIds[row] != observation.realmId()
                || regionKeys[row] != observation.regionKey()
                || observedPopulations[row] != observation.population()
                || housingCapacities[row] != observation.housingCapacity()
                || buildingCounts[row] != observation.buildingCount()
                || productiveBuildings[row] != observation.productiveBuildings()
                || marketAccess[row] != observation.marketAccess()
                || security[row] != observation.security()
                || damage[row] != observation.damage()
                || education[row] != observation.education()
                || geographicCapacity[row] != observation.geographicCapacity()
                || fertility[row] != observation.fertility()
                || specialization[row] != observation.specialization()
                || physicalPresence[row] == 0;
        cultureKeys[row] = observation.cultureKey();
        realmIds[row] = observation.realmId();
        regionKeys[row] = observation.regionKey();
        observedPopulations[row] = observation.population();
        housingCapacities[row] = observation.housingCapacity();
        buildingCounts[row] = observation.buildingCount();
        productiveBuildings[row] = observation.productiveBuildings();
        marketAccess[row] = observation.marketAccess();
        security[row] = observation.security();
        damage[row] = observation.damage();
        education[row] = observation.education();
        geographicCapacity[row] = observation.geographicCapacity();
        fertility[row] = observation.fertility();
        specialization[row] = observation.specialization();
        seenEpochs[row] = observationEpoch;
        physicalPresence[row] = 1;
        if (mutated) changed();
        return row;
    }

    public void finishObservation() {
        boolean mutated = false;
        for (int row = 0; row < size; row++) {
            byte present = seenEpochs[row] == observationEpoch ? (byte) 1 : (byte) 0;
            mutated |= physicalPresence[row] != present;
            physicalPresence[row] = present;
        }
        if (mutated) changed();
    }

    public int find(long settlementId) {
        for (int row = 0; row < size; row++) {
            if (settlementIds[row] == settlementId) return row;
        }
        return -1;
    }

    public int size() { return size; }
    public int commodityCount() { return commodityCount; }
    public long revision() { return revision; }
    public long settlementIdAt(int row) { checkRow(row); return settlementIds[row]; }
    public int cultureKeyAt(int row) { checkRow(row); return cultureKeys[row]; }
    public long realmIdAt(int row) { checkRow(row); return realmIds[row]; }
    public long regionKeyAt(int row) { checkRow(row); return regionKeys[row]; }
    public long observedPopulationAt(int row) { checkRow(row); return observedPopulations[row]; }
    public long housingCapacityAt(int row) { checkRow(row); return housingCapacities[row]; }
    public int buildingCountAt(int row) { checkRow(row); return buildingCounts[row]; }
    public int productiveBuildingsAt(int row) { checkRow(row); return productiveBuildings[row]; }
    public int marketAccessAt(int row) { checkRow(row); return marketAccess[row]; }
    public int securityAt(int row) { checkRow(row); return security[row]; }
    public int damageAt(int row) { checkRow(row); return damage[row]; }
    public int educationAt(int row) { checkRow(row); return education[row]; }
    public int geographicCapacityAt(int row) { checkRow(row); return geographicCapacity[row]; }
    public int fertilityAt(int row) { checkRow(row); return fertility[row]; }
    public int specializationAt(int row) { checkRow(row); return specialization[row]; }
    public long populationAt(int row) { checkRow(row); return populations[row]; }
    public int productivityAt(int row) { checkRow(row); return productivity[row]; }
    public int stabilityAt(int row) { checkRow(row); return stability[row]; }
    public int attractivenessAt(int row) { checkRow(row); return attractiveness[row]; }
    public int productiveCapitalAt(int row) { checkRow(row); return productiveCapital[row]; }
    public SettlementStatus statusAt(int row) {
        checkRow(row);
        return SettlementStatus.values()[Byte.toUnsignedInt(statuses[row])];
    }
    public SettlementTier tierAt(int row) {
        checkRow(row);
        return SettlementTier.values()[Byte.toUnsignedInt(tiers[row])];
    }
    /** Schema-2 semantic: thousandths of a historical year. */
    public int declineMilliYearsAt(int row) { checkRow(row); return declineCycles[row]; }
    public int missingMilliYearsAt(int row) { checkRow(row); return missingCycles[row]; }
    public int foundingCooldownMilliYearsAt(int row) { checkRow(row); return foundingCooldowns[row]; }
    /** Compatibility aliases retained for callers compiled against schema 1. */
    public int declineCyclesAt(int row) { return declineMilliYearsAt(row); }
    public int missingCyclesAt(int row) { return missingMilliYearsAt(row); }
    public int foundingCooldownAt(int row) { return foundingCooldownMilliYearsAt(row); }
    public long nextDueTickAt(int row) { checkRow(row); return nextDueTicks[row]; }
    public boolean physicallyPresentAt(int row) { checkRow(row); return physicalPresence[row] != 0; }
    public long historicalTimeRemainderAt(int row) { checkRow(row); return historicalTimeRemainders[row]; }
    public long populationGrowthRemainderAt(int row) { checkRow(row); return populationGrowthRemainders[row]; }
    public long populationObservationRemainderAt(int row) { checkRow(row); return populationObservationRemainders[row]; }
    public long capitalMoveRemainderAt(int row) { checkRow(row); return capitalMoveRemainders[row]; }
    public long productivityMoveRemainderAt(int row) { checkRow(row); return productivityMoveRemainders[row]; }
    public long stabilityMoveRemainderAt(int row) { checkRow(row); return stabilityMoveRemainders[row]; }
    public long attractivenessMoveRemainderAt(int row) { checkRow(row); return attractivenessMoveRemainders[row]; }
    public long stockAt(int row, int commodity) { return stocks[cell(row, commodity)]; }
    public int priceIndexAt(int row, int commodity) { return priceIndices[cell(row, commodity)]; }
    public long netFlowAt(int row, int commodity) { return netFlows[cell(row, commodity)]; }
    public long flowRemainderAt(int row, int commodity) { return flowRemainders[cell(row, commodity)]; }
    public long priceMoveRemainderAt(int row, int commodity) { return priceMoveRemainders[cell(row, commodity)]; }

    /** Strategic ownership update from the canonical Realm module. */
    public boolean assignRealm(long settlementId, long realmId) {
        if (settlementId <= 0L || realmId < 0L) {
            throw new IllegalArgumentException("Invalid settlement Realm assignment");
        }
        int row = find(settlementId);
        if (row < 0) return false;
        if (realmIds[row] != realmId) {
            realmIds[row] = realmId;
            changed();
        }
        return true;
    }

    /**
     * Cold-path restore hook used by persistence adapters. Rows must be restored in their persisted
     * order and settlement ids must remain unique.
     */
    public int restoreRow(
            long settlementId,
            int cultureKey,
            long realmId,
            long regionKey,
            long observedPopulation,
            long housingCapacity,
            int buildingCount,
            int productiveBuildingCount,
            int marketAccessValue,
            int securityValue,
            int damageValue,
            int educationValue,
            int geographicCapacityValue,
            int fertilityValue,
            int specializationValue,
            long population,
            int productivityValue,
            int stabilityValue,
            int attractivenessValue,
            int productiveCapitalValue,
            SettlementStatus status,
            SettlementTier tier,
            int declineCycleCount,
            int missingCycleCount,
            int foundingCooldown,
            long nextDueTick,
            boolean physicallyPresent,
            long[] commodityStocks,
            int[] commodityPrices,
            long[] commodityNetFlows) {
        if (settlementId <= 0L || cultureKey < 0 || realmId < 0L || observedPopulation < 0L
                || housingCapacity < 0L || buildingCount < 0 || productiveBuildingCount < 0
                || productiveBuildingCount > buildingCount || population < 0L
                || declineCycleCount < 0 || missingCycleCount < 0 || foundingCooldown < 0
                || nextDueTick < 0L || status == null || tier == null) {
            throw new IllegalArgumentException("Invalid restored settlement row");
        }
        requireIndex(marketAccessValue, "marketAccess");
        requireIndex(securityValue, "security");
        requireIndex(damageValue, "damage");
        requireIndex(educationValue, "education");
        requireIndex(geographicCapacityValue, "geographicCapacity");
        requireIndex(fertilityValue, "fertility");
        requireIndex(specializationValue, "specialization");
        requireIndex(productivityValue, "productivity");
        requireIndex(stabilityValue, "stability");
        requireIndex(attractivenessValue, "attractiveness");
        requireIndex(productiveCapitalValue, "productiveCapital");
        requireCommodityRow(commodityStocks, commodityPrices, commodityNetFlows);
        if (find(settlementId) >= 0) {
            throw new IllegalArgumentException("Duplicate restored settlement id " + settlementId);
        }
        if (size == maximumSettlements) {
            throw new IllegalArgumentException("Restored settlement count exceeds configured maximum");
        }
        ensureCapacity(size + 1);
        int row = size++;
        settlementIds[row] = settlementId;
        cultureKeys[row] = cultureKey;
        realmIds[row] = realmId;
        regionKeys[row] = regionKey;
        observedPopulations[row] = observedPopulation;
        housingCapacities[row] = housingCapacity;
        buildingCounts[row] = buildingCount;
        productiveBuildings[row] = productiveBuildingCount;
        marketAccess[row] = marketAccessValue;
        security[row] = securityValue;
        damage[row] = damageValue;
        education[row] = educationValue;
        geographicCapacity[row] = geographicCapacityValue;
        fertility[row] = fertilityValue;
        specialization[row] = specializationValue;
        populations[row] = population;
        productivity[row] = productivityValue;
        stability[row] = stabilityValue;
        attractiveness[row] = attractivenessValue;
        productiveCapital[row] = productiveCapitalValue;
        statuses[row] = (byte) status.ordinal();
        tiers[row] = (byte) tier.ordinal();
        declineCycles[row] = declineCycleCount;
        missingCycles[row] = missingCycleCount;
        foundingCooldowns[row] = foundingCooldown;
        nextDueTicks[row] = nextDueTick;
        physicalPresence[row] = physicallyPresent ? (byte) 1 : (byte) 0;
        for (int commodity = 0; commodity < commodityCount; commodity++) {
            int cell = row * commodityCount + commodity;
            if (commodityStocks[commodity] < 0L || commodityPrices[commodity] < 0) {
                throw new IllegalArgumentException("Invalid restored commodity cell");
            }
            stocks[cell] = commodityStocks[commodity];
            priceIndices[cell] = commodityPrices[commodity];
            netFlows[cell] = commodityNetFlows[commodity];
        }
        return row;
    }

    /** Cold-path schema-2 restore for historical fixed-point residuals. */
    public void restoreHistoricalState(
            int row,
            long historicalTimeRemainder,
            long populationGrowthRemainder,
            long populationObservationRemainder,
            long capitalMoveRemainder,
            long productivityMoveRemainder,
            long stabilityMoveRemainder,
            long attractivenessMoveRemainder,
            long[] commodityFlowRemainders,
            long[] commodityPriceMoveRemainders) {
        checkRow(row);
        if (historicalTimeRemainder < 0L
                || commodityFlowRemainders == null
                || commodityPriceMoveRemainders == null
                || commodityFlowRemainders.length != commodityCount
                || commodityPriceMoveRemainders.length != commodityCount) {
            throw new IllegalArgumentException("Invalid restored historical Simulation residuals");
        }
        historicalTimeRemainders[row] = historicalTimeRemainder;
        populationGrowthRemainders[row] = populationGrowthRemainder;
        populationObservationRemainders[row] = populationObservationRemainder;
        capitalMoveRemainders[row] = capitalMoveRemainder;
        productivityMoveRemainders[row] = productivityMoveRemainder;
        stabilityMoveRemainders[row] = stabilityMoveRemainder;
        attractivenessMoveRemainders[row] = attractivenessMoveRemainder;
        for (int commodity = 0; commodity < commodityCount; commodity++) {
            int cell = row * commodityCount + commodity;
            flowRemainders[cell] = commodityFlowRemainders[commodity];
            priceMoveRemainders[cell] = commodityPriceMoveRemainders[commodity];
        }
    }

    /** Restores the persisted revision after every row has been validated and appended. */
    public void restoreRevision(long value) {
        if (value < 0L) throw new IllegalArgumentException("Negative simulation revision");
        revision = value;
        observationEpoch = 0L;
        Arrays.fill(seenEpochs, 0L);
    }

    void populationAt(int row, long value) { checkRow(row); populations[row] = Math.max(0L, value); }
    void productivityAt(int row, int value) { checkRow(row); productivity[row] = clampIndex(value); }
    void stabilityAt(int row, int value) { checkRow(row); stability[row] = clampIndex(value); }
    void attractivenessAt(int row, int value) { checkRow(row); attractiveness[row] = clampIndex(value); }
    void productiveCapitalAt(int row, int value) { checkRow(row); productiveCapital[row] = clampIndex(value); }
    void statusAt(int row, SettlementStatus value) {
        checkRow(row);
        if (value == null) throw new NullPointerException("status");
        statuses[row] = (byte) value.ordinal();
    }
    void tierAt(int row, SettlementTier value) {
        checkRow(row);
        if (value == null) throw new NullPointerException("tier");
        tiers[row] = (byte) value.ordinal();
    }
    void declineMilliYearsAt(int row, int value) { checkRow(row); declineCycles[row] = Math.max(0, value); }
    void missingMilliYearsAt(int row, int value) { checkRow(row); missingCycles[row] = Math.max(0, value); }
    void foundingCooldownMilliYearsAt(int row, int value) { checkRow(row); foundingCooldowns[row] = Math.max(0, value); }
    void declineCyclesAt(int row, int value) { declineMilliYearsAt(row, value); }
    void missingCyclesAt(int row, int value) { missingMilliYearsAt(row, value); }
    void foundingCooldownAt(int row, int value) { foundingCooldownMilliYearsAt(row, value); }
    void nextDueTickAt(int row, long value) { checkRow(row); nextDueTicks[row] = Math.max(0L, value); }
    void historicalTimeRemainderAt(int row, long value) { checkRow(row); historicalTimeRemainders[row] = Math.max(0L, value); }
    void populationGrowthRemainderAt(int row, long value) { checkRow(row); populationGrowthRemainders[row] = value; }
    void populationObservationRemainderAt(int row, long value) { checkRow(row); populationObservationRemainders[row] = value; }
    void capitalMoveRemainderAt(int row, long value) { checkRow(row); capitalMoveRemainders[row] = value; }
    void productivityMoveRemainderAt(int row, long value) { checkRow(row); productivityMoveRemainders[row] = value; }
    void stabilityMoveRemainderAt(int row, long value) { checkRow(row); stabilityMoveRemainders[row] = value; }
    void attractivenessMoveRemainderAt(int row, long value) { checkRow(row); attractivenessMoveRemainders[row] = value; }
    void stockAt(int row, int commodity, long value) { stocks[cell(row, commodity)] = Math.max(0L, value); }
    void priceIndexAt(int row, int commodity, int value) { priceIndices[cell(row, commodity)] = Math.max(1, value); }
    void netFlowAt(int row, int commodity, long value) { netFlows[cell(row, commodity)] = value; }
    void flowRemainderAt(int row, int commodity, long value) { flowRemainders[cell(row, commodity)] = value; }
    void priceMoveRemainderAt(int row, int commodity, long value) { priceMoveRemainders[cell(row, commodity)] = value; }
    void changed() {
        if (revision == Long.MAX_VALUE) throw new IllegalStateException("Simulation revision exhausted");
        revision++;
    }

    public int estimatedPrimitiveBytes() {
        return settlementIds.length * Long.BYTES
                + cultureKeys.length * Integer.BYTES
                + realmIds.length * Long.BYTES
                + regionKeys.length * Long.BYTES
                + observedPopulations.length * Long.BYTES
                + housingCapacities.length * Long.BYTES
                + buildingCounts.length * Integer.BYTES
                + productiveBuildings.length * Integer.BYTES
                + marketAccess.length * Integer.BYTES
                + security.length * Integer.BYTES
                + damage.length * Integer.BYTES
                + education.length * Integer.BYTES
                + geographicCapacity.length * Integer.BYTES
                + fertility.length * Integer.BYTES
                + specialization.length * Integer.BYTES
                + populations.length * Long.BYTES
                + productivity.length * Integer.BYTES
                + stability.length * Integer.BYTES
                + attractiveness.length * Integer.BYTES
                + productiveCapital.length * Integer.BYTES
                + statuses.length
                + tiers.length
                + declineCycles.length * Integer.BYTES
                + missingCycles.length * Integer.BYTES
                + foundingCooldowns.length * Integer.BYTES
                + nextDueTicks.length * Long.BYTES
                + seenEpochs.length * Long.BYTES
                + physicalPresence.length
                + (historicalTimeRemainders.length
                        + populationGrowthRemainders.length
                        + populationObservationRemainders.length
                        + capitalMoveRemainders.length
                        + productivityMoveRemainders.length
                        + stabilityMoveRemainders.length
                        + attractivenessMoveRemainders.length) * Long.BYTES
                + stocks.length * Long.BYTES
                + priceIndices.length * Integer.BYTES
                + netFlows.length * Long.BYTES
                + flowRemainders.length * Long.BYTES
                + priceMoveRemainders.length * Long.BYTES;
    }

    private int cell(int row, int commodity) {
        checkRow(row);
        if (commodity < 0 || commodity >= commodityCount) {
            throw new IndexOutOfBoundsException("commodity=" + commodity);
        }
        return row * commodityCount + commodity;
    }

    private void checkRow(int row) {
        if (row < 0 || row >= size) throw new IndexOutOfBoundsException("row=" + row);
    }

    private void ensureCapacity(int required) {
        if (required <= settlementIds.length) return;
        int oldCapacity = settlementIds.length;
        int capacity = Math.min(
                maximumSettlements,
                Math.max(required, oldCapacity + Math.max(1, oldCapacity >>> 1)));
        settlementIds = Arrays.copyOf(settlementIds, capacity);
        cultureKeys = Arrays.copyOf(cultureKeys, capacity);
        realmIds = Arrays.copyOf(realmIds, capacity);
        regionKeys = Arrays.copyOf(regionKeys, capacity);
        observedPopulations = Arrays.copyOf(observedPopulations, capacity);
        housingCapacities = Arrays.copyOf(housingCapacities, capacity);
        buildingCounts = Arrays.copyOf(buildingCounts, capacity);
        productiveBuildings = Arrays.copyOf(productiveBuildings, capacity);
        marketAccess = Arrays.copyOf(marketAccess, capacity);
        security = Arrays.copyOf(security, capacity);
        damage = Arrays.copyOf(damage, capacity);
        education = Arrays.copyOf(education, capacity);
        geographicCapacity = Arrays.copyOf(geographicCapacity, capacity);
        fertility = Arrays.copyOf(fertility, capacity);
        specialization = Arrays.copyOf(specialization, capacity);
        populations = Arrays.copyOf(populations, capacity);
        productivity = Arrays.copyOf(productivity, capacity);
        stability = Arrays.copyOf(stability, capacity);
        attractiveness = Arrays.copyOf(attractiveness, capacity);
        productiveCapital = Arrays.copyOf(productiveCapital, capacity);
        statuses = Arrays.copyOf(statuses, capacity);
        tiers = Arrays.copyOf(tiers, capacity);
        declineCycles = Arrays.copyOf(declineCycles, capacity);
        missingCycles = Arrays.copyOf(missingCycles, capacity);
        foundingCooldowns = Arrays.copyOf(foundingCooldowns, capacity);
        nextDueTicks = Arrays.copyOf(nextDueTicks, capacity);
        seenEpochs = Arrays.copyOf(seenEpochs, capacity);
        physicalPresence = Arrays.copyOf(physicalPresence, capacity);
        historicalTimeRemainders = Arrays.copyOf(historicalTimeRemainders, capacity);
        populationGrowthRemainders = Arrays.copyOf(populationGrowthRemainders, capacity);
        populationObservationRemainders = Arrays.copyOf(populationObservationRemainders, capacity);
        capitalMoveRemainders = Arrays.copyOf(capitalMoveRemainders, capacity);
        productivityMoveRemainders = Arrays.copyOf(productivityMoveRemainders, capacity);
        stabilityMoveRemainders = Arrays.copyOf(stabilityMoveRemainders, capacity);
        attractivenessMoveRemainders = Arrays.copyOf(attractivenessMoveRemainders, capacity);
        stocks = resizeCells(stocks, oldCapacity, capacity, Long.BYTES);
        priceIndices = resizeCells(priceIndices, oldCapacity, capacity);
        netFlows = resizeCells(netFlows, oldCapacity, capacity, Long.BYTES);
        flowRemainders = resizeCells(flowRemainders, oldCapacity, capacity, Long.BYTES);
        priceMoveRemainders = resizeCells(priceMoveRemainders, oldCapacity, capacity, Long.BYTES);
    }

    private long[] resizeCells(long[] source, int oldCapacity, int newCapacity, int ignored) {
        long[] target = new long[newCapacity * commodityCount];
        for (int row = 0; row < oldCapacity; row++) {
            System.arraycopy(source, row * commodityCount, target, row * commodityCount, commodityCount);
        }
        return target;
    }

    private int[] resizeCells(int[] source, int oldCapacity, int newCapacity) {
        int[] target = new int[newCapacity * commodityCount];
        for (int row = 0; row < oldCapacity; row++) {
            System.arraycopy(source, row * commodityCount, target, row * commodityCount, commodityCount);
        }
        return target;
    }

    private static int clampIndex(int value) {
        return Math.max(0, Math.min(1000, value));
    }

    private void requireCommodityRow(long[] rowStocks, int[] rowPrices, long[] rowFlows) {
        if (rowStocks == null || rowPrices == null || rowFlows == null
                || rowStocks.length != commodityCount
                || rowPrices.length != commodityCount
                || rowFlows.length != commodityCount) {
            throw new IllegalArgumentException("Restored commodity row width mismatch");
        }
    }

    private static void requireIndex(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " outside 0..1000");
        }
    }
}
