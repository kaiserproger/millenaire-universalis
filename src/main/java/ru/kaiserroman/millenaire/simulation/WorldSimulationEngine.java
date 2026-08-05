package ru.kaiserroman.millenaire.simulation;

/**
 * Bounded, deterministic settlement simulation. It advances virtual population, labour
 * productivity, capital, stability, attractiveness, commodity stocks and local prices while
 * unloaded. Physical village creation/removal is intentionally delegated through events.
 */
public final class WorldSimulationEngine {
    public static final int REASON_POPULATION = 1;
    public static final int REASON_CAPACITY = 1 << 1;
    public static final int REASON_ATTRACTIVENESS = 1 << 2;
    public static final int REASON_STABILITY = 1 << 3;
    public static final int REASON_FOOD = 1 << 4;
    public static final int REASON_GEOGRAPHY = 1 << 5;
    public static final int REASON_MISSING = 1 << 6;
    public static final int REASON_PRICE = 1 << 7;
    public static final int REASON_MIGRATION = 1 << 8;

    private static final int DEFAULT_SHOCK_CAPACITY = 64;
    private static final int MAX_PRICE_INDEX = 100_000;
    private static final int MILLI_YEARS_PER_YEAR = 1_000;
    private static final long PERMILLE_MILLI_YEAR_DENOMINATOR = 1_000_000L;
    private static final int OBSERVATION_RECONCILIATION_PERMILLE_PER_YEAR = 250;
    private static final int CAPITAL_MOVE_PER_YEAR = 35;
    private static final int PRODUCTIVITY_MOVE_PER_YEAR = 40;
    private static final int STABILITY_MOVE_PER_YEAR = 55;
    private static final int ATTRACTIVENESS_MOVE_PER_YEAR = 50;

    private final PackedSettlementSimulationState state;
    private final SimulationPolicy policy;
    private final CommodityProfile[] commodities;
    private final SimulationEventSink events;
    private final SimulationShockLedger shocks;
    private final RegionalShockPropagationPolicy shockPropagation =
            new RegionalShockPropagationPolicy();
    private final Thread ownerThread;


    private int cursor;
    private long lastGameTime = Long.MIN_VALUE;
    private long currentCycle;
    private int lastTickWorkUnits;
    private long simulatedCycles;
    private long emittedEvents;
    private long relocatedPopulation;
    /** Scratch output of scaleWithRemainder; owner-thread confinement makes it allocation-free. */
    private long scaledRemainder;

    public WorldSimulationEngine(
            PackedSettlementSimulationState state,
            SimulationPolicy policy,
            CommodityProfile[] commodities,
            SimulationEventSink events) {
        this(state, policy, commodities, events, new SimulationShockLedger(DEFAULT_SHOCK_CAPACITY));
    }

    public WorldSimulationEngine(
            PackedSettlementSimulationState state,
            SimulationPolicy policy,
            CommodityProfile[] commodities,
            SimulationEventSink events,
            SimulationShockLedger shocks) {
        if (state == null || policy == null || commodities == null || events == null || shocks == null) {
            throw new NullPointerException("simulation dependency");
        }
        if (commodities.length != policy.commodityCount()
                || state.commodityCount() != policy.commodityCount()) {
            throw new IllegalArgumentException("Commodity dictionary mismatch");
        }
        this.state = state;
        this.policy = policy;
        this.commodities = commodities.clone();
        for (CommodityProfile commodity : this.commodities) {
            if (commodity == null) throw new NullPointerException("commodity profile");
        }
        this.events = events;
        this.shocks = shocks;
        ownerThread = Thread.currentThread();
    }

    public void beginReconciliation() {
        requireOwnerThread();
        state.beginObservation();
    }

    /** Returns the stable packed row, or -1 when the configured settlement cap is reached. */
    public int observe(SettlementObservation observation, long gameTime) {
        requireOwnerThread();
        if (gameTime < 0L) throw new IllegalArgumentException("Negative gameTime");
        boolean newSettlement = state.find(observation.settlementId()) < 0;
        int row = state.observe(observation, saturatedAdd(gameTime, policy.cycleIntervalTicks()));
        if (row < 0) return -1;
        if (newSettlement) {
            for (int commodity = 0; commodity < commodities.length; commodity++) {
                CommodityProfile profile = commodities[commodity];
                long initialStock = scaled(observation.population(), profile.targetStockPerPersonMilli());
                state.stockAt(row, commodity, initialStock);
                state.priceIndexAt(row, commodity, profile.basePrice());
            }
            state.changed();
        }
        return row;
    }

    /**
     * Reconciles one physically observed Millenaire commodity stock into the strategic ledger.
     * A bounded weight prevents a transient unloaded/partially restocked chest state from erasing
     * years of simulated production in one scan, while a weight of 1000 performs exact adoption.
     */
    public boolean observePhysicalStock(
            int row,
            int commodity,
            long physicalEquivalent,
            int observationWeightPermille) {
        requireOwnerThread();
        if (row < 0 || row >= state.size()
                || commodity < 0 || commodity >= state.commodityCount()
                || physicalEquivalent < 0L
                || observationWeightPermille < 0 || observationWeightPermille > 1000) {
            throw new IllegalArgumentException("Invalid physical stock observation");
        }
        if (observationWeightPermille == 0) return false;
        long current = state.stockAt(row, commodity);
        long next;
        if (observationWeightPermille == 1000) {
            next = physicalEquivalent;
        } else if (physicalEquivalent >= current) {
            long distance = physicalEquivalent - current;
            next = saturatedAdd(current, scaled(distance, observationWeightPermille));
        } else {
            long distance = current - physicalEquivalent;
            next = Math.max(0L, current - scaled(distance, observationWeightPermille));
        }
        if (next == current) return false;
        state.stockAt(row, commodity, next);
        state.changed();
        return true;
    }

    public void finishReconciliation() {
        requireOwnerThread();
        state.finishObservation();
    }

    public boolean addShock(WorldShock shock, long gameTime) {
        requireOwnerThread();
        if (shock == null) throw new NullPointerException("shock");
        if (gameTime < 0L) throw new IllegalArgumentException("Negative gameTime");
        return shocks.add(shock, gameTime / policy.cycleIntervalTicks());
    }

    /** Evaluates and commits one bounded spread from an existing shock to another settlement. */
    public boolean propagateShock(
            WorldShock source,
            long targetSettlementId,
            ShockPropagationInputs inputs,
            long gameTime) {
        requireOwnerThread();
        if (source == null || inputs == null) throw new NullPointerException("shock propagation input");
        if (targetSettlementId <= 0L || gameTime < 0L) {
            throw new IllegalArgumentException("Invalid propagated shock target");
        }
        if (source.targetSettlementId() == targetSettlementId) return false;
        int targetRow = state.find(targetSettlementId);
        if (targetRow < 0
                || !state.physicallyPresentAt(targetRow)
                || state.statusAt(targetRow) == SettlementStatus.RUINED
                || state.statusAt(targetRow) == SettlementStatus.ABANDONED) {
            return false;
        }

        int sourceRow = source.targetSettlementId() <= 0L
                ? -1
                : state.find(source.targetSettlementId());
        long targetRegion = state.regionKeyAt(targetRow);
        int targetCulture = state.cultureKeyAt(targetRow);
        boolean sharedRegion = source.targetRegionKey() != 0L
                ? source.targetRegionKey() == targetRegion
                : sourceRow >= 0 && state.regionKeyAt(sourceRow) == targetRegion;
        boolean sharedCulture = source.targetCultureKey() != 0
                ? source.targetCultureKey() == targetCulture
                : sourceRow >= 0 && state.cultureKeyAt(sourceRow) == targetCulture;
        ShockPropagationDecision decision = shockPropagation.evaluate(
                source,
                state.marketAccessAt(targetRow),
                state.securityAt(targetRow),
                sharedRegion,
                sharedCulture,
                inputs);
        WorldShock propagated = decision.toShock(source.type(), targetSettlementId, 0L, 0);
        return propagated != null
                && shocks.add(propagated, gameTime / policy.cycleIntervalTicks());
    }

    /**
     * Moves a bounded number of virtual residents between existing settlements. Route selection is
     * adapter-owned; this method enforces source retention, per-cause flow limits and destination
     * housing before committing one deterministic refugee/migration event.
     */
    public long relocatePopulation(
            long sourceSettlementId,
            long destinationSettlementId,
            long requestedPeople,
            MigrationReason reason,
            long gameTime) {
        requireOwnerThread();
        if (sourceSettlementId <= 0L || destinationSettlementId <= 0L
                || requestedPeople < 0L || gameTime < 0L) {
            throw new IllegalArgumentException("Invalid population relocation input");
        }
        if (reason == null) throw new NullPointerException("reason");
        if (requestedPeople == 0L || sourceSettlementId == destinationSettlementId) return 0L;

        int sourceRow = state.find(sourceSettlementId);
        int destinationRow = state.find(destinationSettlementId);
        if (sourceRow < 0 || destinationRow < 0) return 0L;
        SettlementStatus sourceStatus = state.statusAt(sourceRow);
        SettlementStatus destinationStatus = state.statusAt(destinationRow);
        if (sourceStatus == SettlementStatus.RUINED
                || destinationStatus == SettlementStatus.RUINED
                || destinationStatus == SettlementStatus.ABANDONED
                || !state.physicallyPresentAt(destinationRow)) {
            return 0L;
        }
        if (reason != MigrationReason.RESETTLEMENT
                && state.attractivenessAt(destinationRow) < 250) {
            return 0L;
        }

        long sourcePopulation = state.populationAt(sourceRow);
        long destinationPopulation = state.populationAt(destinationRow);
        long minimumRetention = sourceStatus == SettlementStatus.ABANDONED
                ? 0L
                : (long) policy.minimumViablePopulation()
                        * reason.minimumViableRetentionPermille() / 1000L;
        long available = Math.max(0L, sourcePopulation - minimumRetention);
        long maximumShare = scaled(sourcePopulation, reason.maximumSharePermille());
        if (maximumShare == 0L && available > 0L) maximumShare = 1L;
        long housingHeadroom = Math.max(
                0L,
                state.housingCapacityAt(destinationRow) - destinationPopulation);
        long moved = Math.min(
                requestedPeople,
                Math.min(available, Math.min(maximumShare, housingHeadroom)));
        if (moved <= 0L) return 0L;

        state.populationAt(sourceRow, sourcePopulation - moved);
        state.populationAt(destinationRow, saturatedAdd(destinationPopulation, moved));
        state.changed();
        relocatedPopulation = saturatedAdd(relocatedPopulation, moved);
        emitMigration(sourceRow, destinationRow, moved, sourcePopulation, reason,
                gameTime / policy.cycleIntervalTicks());
        return moved;
    }

    public void tick(long gameTime) {
        requireOwnerThread();
        if (gameTime < 0L) throw new IllegalArgumentException("Negative gameTime");
        if (gameTime == lastGameTime) return;
        if (lastGameTime != Long.MIN_VALUE && gameTime < lastGameTime) {
            throw new IllegalStateException("Game time moved backwards");
        }
        lastGameTime = gameTime;
        currentCycle = gameTime / policy.cycleIntervalTicks();
        shocks.prune(currentCycle);
        lastTickWorkUnits = 0;
        int count = state.size();
        if (count == 0) return;

        boolean changed = false;
        int budget = Math.min(policy.rowsPerTick(), count);
        for (int inspected = 0; inspected < budget; inspected++) {
            if (cursor >= count) cursor = 0;
            int row = cursor++;
            lastTickWorkUnits++;
            long due = state.nextDueTickAt(row);
            if (due > gameTime) continue;
            long pending = (gameTime - due) / policy.cycleIntervalTicks() + 1L;
            int cycles = (int) Math.min(policy.maximumCatchUpCycles(), pending);
            for (int step = 0; step < cycles; step++) {
                long cycle = due / policy.cycleIntervalTicks();
                simulateCycle(row, cycle);
                due = saturatedAdd(due, policy.cycleIntervalTicks());
                simulatedCycles++;
                lastTickWorkUnits++;
            }
            state.nextDueTickAt(row, due);
            changed = true;
        }
        if (changed) state.changed();
    }

    private void simulateCycle(int row, long cycle) {
        int elapsedMilliYears = advanceHistoricalClock(row);
        if (!state.physicallyPresentAt(row)) {
            if (elapsedMilliYears > 0) {
                int missing = saturatedAddInt(
                        state.missingMilliYearsAt(row), elapsedMilliYears);
                state.missingMilliYearsAt(row, missing);
                if (missing >= policy.missingMilliYearsBeforeRuin()
                        && state.statusAt(row) != SettlementStatus.RUINED) {
                    state.statusAt(row, SettlementStatus.RUINED);
                    state.populationAt(row, 0L);
                    emit(row, SimulationEventType.RUINED, 1000, REASON_MISSING, cycle);
                }
                decayAbandonedStock(row, elapsedMilliYears);
            }
            return;
        }
        state.missingMilliYearsAt(row, 0);
        if (state.statusAt(row) == SettlementStatus.RUINED) {
            // A physical village reappeared with the same stable id: treat it as reconstruction.
            state.statusAt(row, SettlementStatus.DECLINING);
            state.populationAt(row, Math.max(1L, state.observedPopulationAt(row)));
            state.declineMilliYearsAt(row, 0);
            emit(row, SimulationEventType.RECOVERED, 500, REASON_MISSING, cycle);
        }
        if (elapsedMilliYears <= 0) return;

        long population = reconcileObservedPopulation(row, elapsedMilliYears);
        ShockEffects shock = shockEffects(row, cycle);
        int effectiveMarket = clampIndex(state.marketAccessAt(row) + shock.marketDelta);
        int effectiveSecurity = clampIndex(state.securityAt(row) + shock.securityDelta);
        int effectiveDamage = clampIndex(state.damageAt(row) + shock.damageDelta);

        long workers = workers(row, population);
        int specializationMultiplier = 750 + state.specializationAt(row) / 2;
        long foodCoverage = 1000L;
        long prosperitySum = 0L;
        for (int commodity = 0; commodity < commodities.length; commodity++) {
            CommodityProfile profile = commodities[commodity];
            long annualProduction = scaled(workers, profile.productionPerWorkerMilli());
            annualProduction = scaled(annualProduction, state.productivityAt(row));
            annualProduction = scaled(annualProduction, specializationMultiplier);
            annualProduction = scaled(
                    annualProduction, Math.max(0, 1000 + shock.productionDelta));
            long annualConsumption = scaled(
                    population, profile.consumptionPerPersonMilli());
            long annualNet = saturatedSubtract(annualProduction, annualConsumption);
            long net = scaleWithRemainder(
                    annualNet,
                    elapsedMilliYears,
                    state.flowRemainderAt(row, commodity),
                    MILLI_YEARS_PER_YEAR);
            state.flowRemainderAt(row, commodity, scaledRemainder);
            long oldStock = state.stockAt(row, commodity);
            long newStock = net >= 0L
                    ? saturatedAdd(oldStock, net)
                    : Math.max(0L, oldStock - Math.min(oldStock, absSaturated(net)));
            state.stockAt(row, commodity, newStock);
            // Expose an annualized flow so Realm/regional policies are cadence-independent.
            state.netFlowAt(row, commodity, annualNet);

            long targetStock = Math.max(
                    1L, scaled(population, profile.targetStockPerPersonMilli()));
            if (commodity == 0) {
                foodCoverage = Math.min(1500L, ratioLong(newStock, targetStock, 1000L));
            }
            prosperitySum = saturatedAdd(
                    prosperitySum, Math.min(2000L, ratioLong(newStock, targetStock, 1000L)));
            updatePrice(
                    row,
                    commodity,
                    oldStock,
                    newStock,
                    targetStock,
                    effectiveMarket,
                    effectiveSecurity,
                    effectiveDamage,
                    annualNet,
                    cycle,
                    elapsedMilliYears);
        }

        int prosperity = commodities.length == 0
                ? 500
                : clampIndex((int) Math.min(1000L, prosperitySum / commodities.length));
        int capitalTarget = clampIndex(
                80
                        + productiveBuildingRatio(row) * 350 / 1000
                        + effectiveMarket * 200 / 1000
                        + state.educationAt(row) * 180 / 1000
                        + prosperity * 190 / 1000
                        - effectiveDamage * 300 / 1000);
        int capital = moveHistorical(
                row,
                state.productiveCapitalAt(row),
                capitalTarget,
                CAPITAL_MOVE_PER_YEAR,
                elapsedMilliYears,
                0);
        state.productiveCapitalAt(row, capital);

        int productivityTarget = clampIndex(
                130
                        + capital * 300 / 1000
                        + state.educationAt(row) * 230 / 1000
                        + effectiveMarket * 130 / 1000
                        + effectiveSecurity * 130 / 1000
                        + state.specializationAt(row) * 130 / 1000
                        - effectiveDamage * 320 / 1000
                        + shock.productivityDelta);
        int productivity = moveHistorical(
                row,
                state.productivityAt(row),
                productivityTarget,
                PRODUCTIVITY_MOVE_PER_YEAR,
                elapsedMilliYears,
                1);
        state.productivityAt(row, productivity);

        int stabilityTarget = clampIndex(
                effectiveSecurity * 330 / 1000
                        + (int) Math.min(1000L, foodCoverage) * 300 / 1000
                        + productivity * 130 / 1000
                        + prosperity * 110 / 1000
                        + state.fertilityAt(row) * 130 / 1000
                        - effectiveDamage * 350 / 1000);
        int stability = moveHistorical(
                row,
                state.stabilityAt(row),
                stabilityTarget,
                STABILITY_MOVE_PER_YEAR,
                elapsedMilliYears,
                2);
        state.stabilityAt(row, stability);

        int attractivenessTarget = clampIndex(
                stability * 300 / 1000
                        + productivity * 250 / 1000
                        + effectiveMarket * 180 / 1000
                        + state.geographicCapacityAt(row) * 150 / 1000
                        + state.fertilityAt(row) * 120 / 1000
                        - effectiveDamage * 280 / 1000);
        int attractiveness = moveHistorical(
                row,
                state.attractivenessAt(row),
                attractivenessTarget,
                ATTRACTIVENESS_MOVE_PER_YEAR,
                elapsedMilliYears,
                3);
        state.attractivenessAt(row, attractiveness);

        advancePopulation(
                row,
                population,
                foodCoverage,
                attractiveness,
                shock.populationPermille,
                elapsedMilliYears);
        updateTier(row, cycle);
        updateLifecycle(row, foodCoverage, cycle, elapsedMilliYears);
        updateFounding(row, foodCoverage, cycle, elapsedMilliYears);
    }

    private void updatePrice(
            int row,
            int commodity,
            long oldStock,
            long newStock,
            long targetStock,
            int market,
            int security,
            int damage,
            long annualNet,
            long cycle,
            int elapsedMilliYears) {
        CommodityProfile profile = commodities[commodity];
        long shortage = clampLong(
                signedRatio(targetStock - newStock, targetStock, 1000L), -1000L, 3000L);
        long flowPressure = annualNet >= 0L
                ? 0L
                : Math.min(2000L, ratioLong(absSaturated(annualNet), targetStock, 1000L));
        long risk = damage + (1000L - security) / 2L;
        long multiplier = 1000L
                + shortage * profile.scarcityElasticity() / 1000L
                + flowPressure / 3L
                + risk / 3L
                - market / 5L;
        multiplier = clampLong(multiplier, 250L, 8000L);
        int targetPrice = (int) Math.min(
                MAX_PRICE_INDEX,
                Math.max(1L, (long) profile.basePrice() * multiplier / 1000L));
        int oldPrice = state.priceIndexAt(row, commodity);
        if (oldPrice <= 0) oldPrice = profile.basePrice();
        if (oldPrice == targetPrice) {
            state.priceMoveRemainderAt(row, commodity, 0L);
            return;
        }
        long annualNumerator = (long) (targetPrice - oldPrice) * profile.priceSmoothing();
        long delta = scaleWithRemainder(
                annualNumerator,
                elapsedMilliYears,
                state.priceMoveRemainderAt(row, commodity),
                PERMILLE_MILLI_YEAR_DENOMINATOR);
        state.priceMoveRemainderAt(row, commodity, scaledRemainder);
        long candidate = oldPrice + delta;
        int nextPrice;
        if (targetPrice > oldPrice && candidate >= targetPrice
                || targetPrice < oldPrice && candidate <= targetPrice) {
            nextPrice = targetPrice;
            state.priceMoveRemainderAt(row, commodity, 0L);
        } else {
            nextPrice = (int) Math.max(1L, Math.min(MAX_PRICE_INDEX, candidate));
        }
        state.priceIndexAt(row, commodity, nextPrice);
        if ((long) Math.abs(nextPrice - oldPrice) * 100L >= (long) oldPrice * 35L) {
            int score = Math.min(
                    1000, Math.abs(nextPrice - oldPrice) * 1000 / Math.max(1, oldPrice));
            emit(row, SimulationEventType.PRICE_SHOCK, score,
                    REASON_PRICE | commodity << 16, cycle);
        }
    }

    private void advancePopulation(
            int row,
            long population,
            long foodCoverage,
            int attractiveness,
            int shockPermille,
            int elapsedMilliYears) {
        if (population == 0L && state.observedPopulationAt(row) == 0L) return;
        int annualRatePermille = (state.fertilityAt(row) - 500) / 80
                + (attractiveness - 500) / 50
                + shockPermille;
        if (foodCoverage < 500L) {
            annualRatePermille -= (int) ((500L - foodCoverage) / 20L);
        }
        long capacity = state.housingCapacityAt(row);
        if (capacity > 0L && population > capacity) {
            annualRatePermille -= (int) Math.min(
                    25L, ratioLong(population - capacity, capacity, 25L) + 1L);
        }
        if (state.statusAt(row) == SettlementStatus.ABANDONED) annualRatePermille -= 15;
        annualRatePermille = Math.max(-50, Math.min(30, annualRatePermille));
        long annualNumerator = saturatedMultiplySigned(population, annualRatePermille);
        long delta = scaleWithRemainder(
                annualNumerator,
                elapsedMilliYears,
                state.populationGrowthRemainderAt(row),
                PERMILLE_MILLI_YEAR_DENOMINATOR);
        state.populationGrowthRemainderAt(row, scaledRemainder);
        state.populationAt(row, delta >= 0L
                ? saturatedAdd(population, delta)
                : Math.max(0L, population + delta));
    }

    private void updateTier(int row, long cycle) {
        SettlementTier previous = state.tierAt(row);
        SettlementTier next = SettlementTier.forPopulation(state.populationAt(row));
        if (previous != next) {
            state.tierAt(row, next);
            int score = 100 + Math.abs(next.ordinal() - previous.ordinal()) * 180;
            emit(row, SimulationEventType.TIER_CHANGED, score,
                    previous.ordinal() | next.ordinal() << 8, cycle);
        }
    }

    private void updateLifecycle(
            int row,
            long foodCoverage,
            long cycle,
            int elapsedMilliYears) {
        SettlementStatus status = state.statusAt(row);
        boolean viable = state.populationAt(row) >= policy.minimumViablePopulation()
                && state.stabilityAt(row) >= 280
                && foodCoverage >= 350L;
        int previousDecline = state.declineMilliYearsAt(row);
        int decline = viable
                ? Math.max(0, previousDecline - saturatedMultiplyInt(elapsedMilliYears, 2))
                : saturatedAddInt(previousDecline, elapsedMilliYears);
        state.declineMilliYearsAt(row, decline);

        if (status == SettlementStatus.ACTIVE
                && decline >= policy.declineGraceMilliYears()) {
            state.statusAt(row, SettlementStatus.DECLINING);
            emit(row, SimulationEventType.DECLINE_STARTED,
                    historicalProgressScore(decline, policy.abandonmentGraceMilliYears()),
                    declineReasons(row, foodCoverage), cycle);
            return;
        }
        if ((status == SettlementStatus.DECLINING || status == SettlementStatus.ABANDONED)
                && viable && decline == 0) {
            state.statusAt(row, SettlementStatus.ACTIVE);
            emit(row, SimulationEventType.RECOVERED, state.stabilityAt(row),
                    REASON_STABILITY | REASON_FOOD, cycle);
            return;
        }
        if (status == SettlementStatus.DECLINING
                && decline >= policy.abandonmentGraceMilliYears()
                && (state.populationAt(row) < policy.minimumViablePopulation()
                        || state.stabilityAt(row) < 160)) {
            state.statusAt(row, SettlementStatus.ABANDONED);
            emit(row, SimulationEventType.ABANDONMENT_CANDIDATE,
                    historicalProgressScore(decline, policy.abandonmentGraceMilliYears()),
                    declineReasons(row, foodCoverage), cycle);
        }
    }

    private void updateFounding(
            int row,
            long foodCoverage,
            long cycle,
            int elapsedMilliYears) {
        int cooldown = state.foundingCooldownMilliYearsAt(row);
        if (cooldown > 0) {
            state.foundingCooldownMilliYearsAt(
                    row, Math.max(0, cooldown - elapsedMilliYears));
            return;
        }
        long population = state.populationAt(row);
        long capacity = state.housingCapacityAt(row);
        if (state.statusAt(row) != SettlementStatus.ACTIVE
                || population < policy.foundingPopulation()
                || capacity <= 0L
                || population * 1000L < capacity * 850L
                || state.attractivenessAt(row) < 700
                || state.stabilityAt(row) < 600
                || foodCoverage < 900L
                || state.geographicCapacityAt(row) < 600) {
            return;
        }
        int score = clampIndex((int) Math.min(1000L,
                population * 1000L / Math.max(1L, capacity)) / 4
                + state.attractivenessAt(row) / 4
                + state.stabilityAt(row) / 5
                + state.geographicCapacityAt(row) / 5
                + (int) Math.min(1000L, foodCoverage) / 10);
        state.foundingCooldownMilliYearsAt(row, policy.foundingCooldownMilliYears());
        emit(row, SimulationEventType.FOUNDING_CANDIDATE, score,
                REASON_POPULATION | REASON_CAPACITY | REASON_ATTRACTIVENESS
                        | REASON_STABILITY | REASON_FOOD | REASON_GEOGRAPHY,
                cycle);
    }

    private ShockEffects shockEffects(int row, long cycle) {
        ShockEffects result = new ShockEffects();
        for (int index = 0; index < shocks.size(); index++) {
            if (!shocks.matchesAt(
                    index,
                    state.settlementIdAt(row),
                    state.regionKeyAt(row),
                    state.cultureKeyAt(row),
                    cycle)) {
                continue;
            }
            int magnitude = shocks.magnitudeAt(index);
            switch (shocks.typeAt(index)) {
                case HARVEST_FAILURE -> result.productionDelta -= magnitude * 3 / 4;
                case EPIDEMIC -> {
                    result.populationPermille -= magnitude / 20;
                    result.productivityDelta -= magnitude / 4;
                }
                case TRADE_BOOM -> {
                    result.marketDelta += magnitude / 2;
                    result.productivityDelta += magnitude / 5;
                }
                case MIGRATION_WAVE -> result.populationPermille += magnitude / 18;
                case WAR_DEVASTATION -> {
                    result.damageDelta += magnitude / 2;
                    result.securityDelta -= magnitude / 2;
                    result.populationPermille -= magnitude / 25;
                }
                case TECHNOLOGY_DIFFUSION -> result.productivityDelta += magnitude / 2;
            }
        }
        result.productionDelta = Math.max(-1000, Math.min(1000, result.productionDelta));
        result.populationPermille = Math.max(-80, Math.min(80, result.populationPermille));
        return result;
    }

    private void decayAbandonedStock(int row, int elapsedMilliYears) {
        for (int commodity = 0; commodity < commodities.length; commodity++) {
            long stock = state.stockAt(row, commodity);
            long annualDecay = -scaled(stock, 50); // five percent per historical year
            long decay = scaleWithRemainder(
                    annualDecay,
                    elapsedMilliYears,
                    state.flowRemainderAt(row, commodity),
                    MILLI_YEARS_PER_YEAR);
            state.flowRemainderAt(row, commodity, scaledRemainder);
            state.stockAt(row, commodity, Math.max(0L, stock + decay));
            state.netFlowAt(row, commodity, annualDecay);
        }
    }

    private int advanceHistoricalClock(int row) {
        long historicalYearTicks = policy.historicalYearTicks();
        long carried = state.historicalTimeRemainderAt(row);
        long carriedMilliYears = carried / historicalYearTicks;
        carried %= historicalYearTicks;
        long cycleMilliTicks = policy.cycleIntervalTicks() * 1000L;
        long total = saturatedAdd(cycleMilliTicks, carried);
        long elapsed = saturatedAdd(carriedMilliYears, total / historicalYearTicks);
        state.historicalTimeRemainderAt(row, total % historicalYearTicks);
        return saturatedInt(elapsed);
    }

    private long reconcileObservedPopulation(int row, int elapsedMilliYears) {
        long population = state.populationAt(row);
        long observed = state.observedPopulationAt(row);
        long difference = saturatedSubtract(observed, population);
        if (difference == 0L) {
            state.populationObservationRemainderAt(row, 0L);
            return population;
        }
        long annualNumerator = saturatedMultiplySigned(
                difference, OBSERVATION_RECONCILIATION_PERMILLE_PER_YEAR);
        long delta = scaleWithRemainder(
                annualNumerator,
                elapsedMilliYears,
                state.populationObservationRemainderAt(row),
                PERMILLE_MILLI_YEAR_DENOMINATOR);
        state.populationObservationRemainderAt(row, scaledRemainder);
        long candidate = delta >= 0L
                ? saturatedAdd(population, delta)
                : Math.max(0L, population + delta);
        if (difference > 0L && candidate >= observed
                || difference < 0L && candidate <= observed) {
            candidate = observed;
            state.populationObservationRemainderAt(row, 0L);
        }
        state.populationAt(row, candidate);
        return candidate;
    }

    private int moveHistorical(
            int row,
            int value,
            int target,
            int annualStep,
            int elapsedMilliYears,
            int remainderKind) {
        if (value == target) {
            setMoveRemainder(row, remainderKind, 0L);
            return value;
        }
        int distance = target - value;
        int annualDelta = Math.max(-annualStep, Math.min(annualStep, distance));
        long delta = scaleWithRemainder(
                annualDelta,
                elapsedMilliYears,
                moveRemainder(row, remainderKind),
                MILLI_YEARS_PER_YEAR);
        setMoveRemainder(row, remainderKind, scaledRemainder);
        long candidate = value + delta;
        if (distance > 0 && candidate >= target || distance < 0 && candidate <= target) {
            setMoveRemainder(row, remainderKind, 0L);
            return target;
        }
        return clampIndex((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, candidate)));
    }

    private long moveRemainder(int row, int kind) {
        return switch (kind) {
            case 0 -> state.capitalMoveRemainderAt(row);
            case 1 -> state.productivityMoveRemainderAt(row);
            case 2 -> state.stabilityMoveRemainderAt(row);
            case 3 -> state.attractivenessMoveRemainderAt(row);
            default -> throw new IllegalArgumentException("Unknown Simulation move remainder " + kind);
        };
    }

    private void setMoveRemainder(int row, int kind, long value) {
        switch (kind) {
            case 0 -> state.capitalMoveRemainderAt(row, value);
            case 1 -> state.productivityMoveRemainderAt(row, value);
            case 2 -> state.stabilityMoveRemainderAt(row, value);
            case 3 -> state.attractivenessMoveRemainderAt(row, value);
            default -> throw new IllegalArgumentException("Unknown Simulation move remainder " + kind);
        }
    }

    private long scaleWithRemainder(
            long value,
            int multiplier,
            long previousRemainder,
            long denominator) {
        if (multiplier < 0 || denominator <= 0L) {
            throw new IllegalArgumentException("Invalid historical fixed-point scale");
        }
        if (value == 0L || multiplier == 0) {
            scaledRemainder = previousRemainder;
            return 0L;
        }
        long quotient = value / denominator;
        long remainder = value % denominator;
        long major = saturatedMultiplySigned(quotient, multiplier);
        long minor = saturatedAddSigned(
                saturatedMultiplySigned(remainder, multiplier), previousRemainder);
        long result = saturatedAddSigned(major, minor / denominator);
        scaledRemainder = minor % denominator;
        return result;
    }

    private static int historicalProgressScore(int milliYears, int targetMilliYears) {
        if (milliYears <= 0 || targetMilliYears <= 0) return 0;
        return (int) Math.min(1000L, ratioLong(milliYears, targetMilliYears, 1000L));
    }

    private long workers(int row, long population) {
        int buildings = Math.max(1, state.buildingCountAt(row));
        long supported = population * state.productiveBuildingsAt(row) / buildings;
        return supported * 500L / 1000L;
    }

    private int productiveBuildingRatio(int row) {
        int buildings = state.buildingCountAt(row);
        return buildings <= 0 ? 0 : state.productiveBuildingsAt(row) * 1000 / buildings;
    }

    private int declineReasons(int row, long foodCoverage) {
        int reasons = 0;
        if (state.populationAt(row) < policy.minimumViablePopulation()) reasons |= REASON_POPULATION;
        if (state.stabilityAt(row) < 280) reasons |= REASON_STABILITY;
        if (foodCoverage < 350L) reasons |= REASON_FOOD;
        return reasons;
    }

    private void emitMigration(
            int sourceRow,
            int destinationRow,
            long moved,
            long sourcePopulation,
            MigrationReason reason,
            long cycle) {
        int score = sourcePopulation <= 0L
                ? 0
                : (int) Math.min(1000.0, (double) moved * 1000.0 / (double) sourcePopulation);
        events.accept(new SimulationEvent(
                SimulationEventType.REFUGEE_FLOW,
                state.settlementIdAt(destinationRow),
                state.settlementIdAt(sourceRow),
                state.cultureKeyAt(sourceRow),
                state.realmIdAt(destinationRow),
                state.regionKeyAt(destinationRow),
                score,
                REASON_MIGRATION | reason.reasonMask(),
                Math.max(0L, cycle)));
        emittedEvents++;
    }

    private void emit(
            int row,
            SimulationEventType type,
            int score,
            int reasonMask,
            long cycle) {
        events.accept(new SimulationEvent(
                type,
                state.settlementIdAt(row),
                type == SimulationEventType.FOUNDING_CANDIDATE ? state.settlementIdAt(row) : 0L,
                state.cultureKeyAt(row),
                state.realmIdAt(row),
                state.regionKeyAt(row),
                Math.max(0, Math.min(1000, score)),
                reasonMask,
                Math.max(0L, cycle)));
        emittedEvents++;
    }

    public PackedSettlementSimulationState state() { return state; }
    public SimulationShockLedger shockLedger() { return shocks; }
    public int activeShockCount() { return shocks.size(); }
    public int lastTickWorkUnits() { return lastTickWorkUnits; }
    public long simulatedCycleCount() { return simulatedCycles; }
    public long emittedEventCount() { return emittedEvents; }
    public long relocatedPopulationCount() { return relocatedPopulation; }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Simulation commits escaped their owning thread");
        }
    }

    private static long scaled(long value, int milli) {
        if (value <= 0L || milli <= 0) return 0L;
        long whole = value / 1000L;
        long remainder = value % 1000L;
        return saturatedAddSigned(
                saturatedMultiplySigned(whole, milli),
                saturatedMultiplySigned(remainder, milli) / 1000L);
    }

    private static long ratioLong(long numerator, long denominator, long scale) {
        if (numerator <= 0L || denominator <= 0L || scale <= 0L) return 0L;
        long whole = numerator / denominator;
        long remainder = numerator % denominator;
        return saturatedAddSigned(
                saturatedMultiplySigned(whole, scale),
                saturatedMultiplySigned(remainder, scale) / denominator);
    }

    private static long signedRatio(long numerator, long denominator, long scale) {
        if (numerator == 0L || denominator <= 0L || scale <= 0L) return 0L;
        long whole = numerator / denominator;
        long remainder = numerator % denominator;
        return saturatedAddSigned(
                saturatedMultiplySigned(whole, scale),
                saturatedMultiplySigned(remainder, scale) / denominator);
    }

    private static int saturatedAddInt(int left, int right) {
        if (right > 0 && left > Integer.MAX_VALUE - right) return Integer.MAX_VALUE;
        if (right < 0 && left < Integer.MIN_VALUE - right) return Integer.MIN_VALUE;
        return left + right;
    }

    private static int saturatedMultiplyInt(int left, int right) {
        long value = (long) left * right;
        if (value > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (value < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) value;
    }

    private static int saturatedInt(long value) {
        if (value > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        if (value < Integer.MIN_VALUE) return Integer.MIN_VALUE;
        return (int) value;
    }

    private static long saturatedMultiplySigned(long left, long right) {
        if (left == 0L || right == 0L) return 0L;
        if (left > 0L) {
            if (right > 0L && left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
            if (right < 0L && right < Long.MIN_VALUE / left) return Long.MIN_VALUE;
        } else {
            if (right > 0L && left < Long.MIN_VALUE / right) return Long.MIN_VALUE;
            if (right < 0L && left < Long.MAX_VALUE / right) return Long.MAX_VALUE;
        }
        return left * right;
    }

    private static long saturatedAddSigned(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0L && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }

    private static long saturatedAdd(long left, long right) {
        return saturatedAddSigned(left, right);
    }

    private static long saturatedSubtract(long left, long right) {
        if (right > 0L && left < Long.MIN_VALUE + right) return Long.MIN_VALUE;
        if (right < 0L && left > Long.MAX_VALUE + right) return Long.MAX_VALUE;
        return left - right;
    }

    private static long absSaturated(long value) {
        return value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);
    }

    private static int clampIndex(int value) {
        return Math.max(0, Math.min(1000, value));
    }

    private static long clampLong(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class ShockEffects {
        int productionDelta;
        int populationPermille;
        int marketDelta;
        int securityDelta;
        int damageDelta;
        int productivityDelta;
    }
}
