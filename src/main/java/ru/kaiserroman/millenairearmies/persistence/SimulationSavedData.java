package ru.kaiserroman.millenairearmies.persistence;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import ru.kaiserroman.millenaire.simulation.PackedSettlementSimulationState;
import ru.kaiserroman.millenaire.simulation.SettlementStatus;
import ru.kaiserroman.millenaire.simulation.SettlementTier;
import ru.kaiserroman.millenaire.simulation.SimulationEvent;
import ru.kaiserroman.millenaire.simulation.SimulationEventJournal;
import ru.kaiserroman.millenaire.simulation.SimulationEventType;
import ru.kaiserroman.millenaire.simulation.ShockType;
import ru.kaiserroman.millenaire.simulation.SimulationShockLedger;
import ru.kaiserroman.millenairearmies.ArmiesConfig;

/** Separate Overworld SavedData owner for the new coarse world simulation. */
public final class SimulationSavedData extends SavedData {
    public static final String FILE_ID = "millenaire_simulation";
    public static final int COMMODITY_COUNT = 8;
    public static final int SHOCK_CAPACITY = 64;
    private static final int SCHEMA_VERSION = 2;
    private static final SavedData.Factory<SimulationSavedData> FACTORY =
            new SavedData.Factory<>(SimulationSavedData::new, SimulationSavedData::load);

    private final SimulationKeyTable keys;
    private final PackedSettlementSimulationState state;
    private final SimulationEventJournal events;
    private final SimulationShockLedger shocks;

    private long mutationSequence;
    private int mutationAttempts;
    private long nextMutationAttemptTick;

    public SimulationSavedData() {
        this(
                new SimulationKeyTable(
                        ArmiesConfig.MAX_SETTLEMENTS,
                        ArmiesConfig.WORLD_SIMULATION_MAX_CULTURES,
                        ArmiesConfig.WORLD_SIMULATION_MAX_DIMENSIONS),
                new PackedSettlementSimulationState(
                        ArmiesConfig.MAX_SETTLEMENTS,
                        COMMODITY_COUNT),
                new SimulationEventJournal(ArmiesConfig.WORLD_SIMULATION_EVENT_CAPACITY),
                new SimulationShockLedger(SHOCK_CAPACITY));
    }

    SimulationSavedData(
            SimulationKeyTable keys,
            PackedSettlementSimulationState state,
            SimulationEventJournal events,
            SimulationShockLedger shocks) {
        if (keys == null || state == null || events == null || shocks == null) {
            throw new NullPointerException("Simulation SavedData stores");
        }
        if (state.commodityCount() != COMMODITY_COUNT) {
            throw new IllegalArgumentException("Unexpected simulation commodity count");
        }
        this.keys = keys;
        this.state = state;
        this.events = events;
        this.shocks = shocks;
    }

    public static SimulationSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    public SimulationKeyTable keys() { return keys; }
    public PackedSettlementSimulationState state() { return state; }
    public SimulationEventJournal events() { return events; }
    public SimulationShockLedger shocks() { return shocks; }
    public void markChanged() { setDirty(); }

    /** Prepares persisted retry state for the current FIFO head. */
    public boolean prepareMutationAttempt(long sequence, long gameTime) {
        if (sequence <= 0L || gameTime < 0L) {
            throw new IllegalArgumentException("Invalid mutation retry identity or game time");
        }
        if (mutationSequence != sequence) {
            mutationSequence = sequence;
            mutationAttempts = 0;
            nextMutationAttemptTick = 0L;
            setDirty();
        }
        return gameTime >= nextMutationAttemptTick;
    }

    public void scheduleMutationRetry(long sequence, long nextAttemptTick) {
        if (sequence <= 0L || nextAttemptTick < 0L) {
            throw new IllegalArgumentException("Invalid mutation retry schedule");
        }
        if (mutationSequence != sequence) {
            mutationSequence = sequence;
            mutationAttempts = 0;
        }
        if (mutationAttempts != Integer.MAX_VALUE) mutationAttempts++;
        nextMutationAttemptTick = nextAttemptTick;
        setDirty();
    }

    public void completeMutationAttempt(long sequence) {
        if (sequence <= 0L) throw new IllegalArgumentException("Invalid completed mutation sequence");
        if (mutationSequence == sequence) {
            mutationSequence = 0L;
            mutationAttempts = 0;
            nextMutationAttemptTick = 0L;
            setDirty();
        }
    }

    public long mutationSequence() { return mutationSequence; }
    public int mutationAttempts() { return mutationAttempts; }
    public long nextMutationAttemptTick() { return nextMutationAttemptTick; }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("SchemaVersion", SCHEMA_VERSION);
        tag.putInt("CommodityCount", COMMODITY_COUNT);
        tag.putLong("StateRevision", state.revision());
        tag.putLong("NextEventSequence", events.nextSequence());
        tag.putLong("DroppedEvents", events.droppedEventCount());
        tag.putLong("MutationSequence", mutationSequence);
        tag.putInt("MutationAttempts", mutationAttempts);
        tag.putLong("NextMutationAttemptTick", nextMutationAttemptTick);

        ListTag settlementKeys = new ListTag();
        keys.visitSettlements((key, most, least) -> {
            CompoundTag row = new CompoundTag();
            row.putLong("Most", most);
            row.putLong("Least", least);
            settlementKeys.add(row);
        });
        tag.put("SettlementKeys", settlementKeys);
        tag.put("CultureKeys", saveNames(keys::visitCultures));
        tag.put("DimensionKeys", saveNames(keys::visitDimensions));

        ListTag settlements = new ListTag();
        for (int row = 0; row < state.size(); row++) {
            CompoundTag settlement = new CompoundTag();
            settlement.putLong("SettlementId", state.settlementIdAt(row));
            settlement.putInt("CultureKey", state.cultureKeyAt(row));
            settlement.putLong("RealmId", state.realmIdAt(row));
            settlement.putLong("RegionKey", state.regionKeyAt(row));
            settlement.putLong("ObservedPopulation", state.observedPopulationAt(row));
            settlement.putLong("HousingCapacity", state.housingCapacityAt(row));
            settlement.putInt("BuildingCount", state.buildingCountAt(row));
            settlement.putInt("ProductiveBuildings", state.productiveBuildingsAt(row));
            settlement.putInt("MarketAccess", state.marketAccessAt(row));
            settlement.putInt("Security", state.securityAt(row));
            settlement.putInt("Damage", state.damageAt(row));
            settlement.putInt("Education", state.educationAt(row));
            settlement.putInt("GeographicCapacity", state.geographicCapacityAt(row));
            settlement.putInt("Fertility", state.fertilityAt(row));
            settlement.putInt("Specialization", state.specializationAt(row));
            settlement.putLong("Population", state.populationAt(row));
            settlement.putInt("Productivity", state.productivityAt(row));
            settlement.putInt("Stability", state.stabilityAt(row));
            settlement.putInt("Attractiveness", state.attractivenessAt(row));
            settlement.putInt("ProductiveCapital", state.productiveCapitalAt(row));
            settlement.putByte("Status", (byte) state.statusAt(row).ordinal());
            settlement.putByte("Tier", (byte) state.tierAt(row).ordinal());
            settlement.putInt("DeclineMilliYears", state.declineMilliYearsAt(row));
            settlement.putInt("MissingMilliYears", state.missingMilliYearsAt(row));
            settlement.putInt("FoundingCooldownMilliYears", state.foundingCooldownMilliYearsAt(row));
            settlement.putLong("NextDueTick", state.nextDueTickAt(row));
            settlement.putBoolean("PhysicallyPresent", state.physicallyPresentAt(row));
            settlement.putLong("HistoricalTimeRemainder", state.historicalTimeRemainderAt(row));
            settlement.putLong("PopulationGrowthRemainder", state.populationGrowthRemainderAt(row));
            settlement.putLong("PopulationObservationRemainder", state.populationObservationRemainderAt(row));
            settlement.putLong("CapitalMoveRemainder", state.capitalMoveRemainderAt(row));
            settlement.putLong("ProductivityMoveRemainder", state.productivityMoveRemainderAt(row));
            settlement.putLong("StabilityMoveRemainder", state.stabilityMoveRemainderAt(row));
            settlement.putLong("AttractivenessMoveRemainder", state.attractivenessMoveRemainderAt(row));
            long[] stocks = new long[COMMODITY_COUNT];
            int[] prices = new int[COMMODITY_COUNT];
            long[] flows = new long[COMMODITY_COUNT];
            long[] flowRemainders = new long[COMMODITY_COUNT];
            long[] priceMoveRemainders = new long[COMMODITY_COUNT];
            for (int commodity = 0; commodity < COMMODITY_COUNT; commodity++) {
                stocks[commodity] = state.stockAt(row, commodity);
                prices[commodity] = state.priceIndexAt(row, commodity);
                flows[commodity] = state.netFlowAt(row, commodity);
                flowRemainders[commodity] = state.flowRemainderAt(row, commodity);
                priceMoveRemainders[commodity] = state.priceMoveRemainderAt(row, commodity);
            }
            settlement.putLongArray("Stocks", stocks);
            settlement.putIntArray("Prices", prices);
            settlement.putLongArray("NetFlows", flows);
            settlement.putLongArray("FlowRemainders", flowRemainders);
            settlement.putLongArray("PriceMoveRemainders", priceMoveRemainders);
            settlements.add(settlement);
        }
        tag.put("Settlements", settlements);

        ListTag eventRows = new ListTag();
        events.visit((sequence, event) -> {
            CompoundTag row = new CompoundTag();
            row.putLong("Sequence", sequence);
            row.putByte("Type", (byte) event.type().ordinal());
            row.putLong("SettlementId", event.settlementId());
            row.putLong("SourceSettlementId", event.sourceSettlementId());
            row.putInt("CultureKey", event.cultureKey());
            row.putLong("RealmId", event.realmId());
            row.putLong("RegionKey", event.regionKey());
            row.putInt("Score", event.score());
            row.putInt("ReasonMask", event.reasonMask());
            row.putLong("Cycle", event.cycle());
            eventRows.add(row);
        });
        tag.put("Events", eventRows);

        ListTag shockRows = new ListTag();
        shocks.visit((type, targetSettlementId, targetRegionKey, targetCultureKey, magnitude, untilCycle) -> {
            CompoundTag row = new CompoundTag();
            row.putByte("Type", (byte) type.ordinal());
            row.putLong("TargetSettlementId", targetSettlementId);
            row.putLong("TargetRegionKey", targetRegionKey);
            row.putInt("TargetCultureKey", targetCultureKey);
            row.putInt("Magnitude", magnitude);
            row.putLong("UntilCycle", untilCycle);
            shockRows.add(row);
        });
        tag.put("Shocks", shockRows);
        return tag;
    }

    static SimulationSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        int schemaVersion = tag.getInt("SchemaVersion");
        if (schemaVersion < 1 || schemaVersion > SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported simulation schema " + schemaVersion);
        }
        if (tag.getInt("CommodityCount") != COMMODITY_COUNT) {
            throw new IllegalArgumentException("Simulation commodity dictionary changed without migration");
        }

        SimulationKeyTable keys = new SimulationKeyTable(
                ArmiesConfig.MAX_SETTLEMENTS,
                ArmiesConfig.WORLD_SIMULATION_MAX_CULTURES,
                ArmiesConfig.WORLD_SIMULATION_MAX_DIMENSIONS);
        ListTag settlementKeys = tag.getList("SettlementKeys", Tag.TAG_COMPOUND);
        if (settlementKeys.size() > ArmiesConfig.MAX_SETTLEMENTS) {
            throw new IllegalArgumentException("Too many restored simulation settlement keys");
        }
        for (int row = 0; row < settlementKeys.size(); row++) {
            CompoundTag key = settlementKeys.getCompound(row);
            keys.restoreSettlement(key.getLong("Most"), key.getLong("Least"));
        }
        restoreNames(tag.getList("CultureKeys", Tag.TAG_COMPOUND), true, keys);
        restoreNames(tag.getList("DimensionKeys", Tag.TAG_COMPOUND), false, keys);

        PackedSettlementSimulationState state = new PackedSettlementSimulationState(
                ArmiesConfig.MAX_SETTLEMENTS, COMMODITY_COUNT);
        ListTag settlements = tag.getList("Settlements", Tag.TAG_COMPOUND);
        if (settlements.size() > ArmiesConfig.MAX_SETTLEMENTS) {
            throw new IllegalArgumentException("Too many restored simulated settlements");
        }
        for (int row = 0; row < settlements.size(); row++) {
            CompoundTag settlement = settlements.getCompound(row);
            long settlementId = settlement.getLong("SettlementId");
            int cultureKey = settlement.getInt("CultureKey");
            if (!keys.validSettlement(settlementId) || !keys.validCulture(cultureKey)) {
                throw new IllegalArgumentException("Simulation row references an unknown stable key");
            }
            int status = Byte.toUnsignedInt(settlement.getByte("Status"));
            int tier = Byte.toUnsignedInt(settlement.getByte("Tier"));
            if (status >= SettlementStatus.values().length || tier >= SettlementTier.values().length) {
                throw new IllegalArgumentException("Unknown simulation status/tier at row " + row);
            }
            long[] stocks = settlement.getLongArray("Stocks");
            int[] prices = settlement.getIntArray("Prices");
            long[] flows = settlement.getLongArray("NetFlows");
            int declineMilliYears = schemaVersion >= 2
                    ? settlement.getInt("DeclineMilliYears")
                    : migrateCycleProgress(
                            settlement.getInt("DeclineCycles"),
                            ArmiesConfig.WORLD_SIMULATION_ABANDONMENT_GRACE_CYCLES,
                            ArmiesConfig.WORLD_SIMULATION_ABANDONMENT_GRACE_YEARS);
            int missingMilliYears = schemaVersion >= 2
                    ? settlement.getInt("MissingMilliYears")
                    : migrateCycleProgress(
                            settlement.getInt("MissingCycles"),
                            ArmiesConfig.WORLD_SIMULATION_MISSING_CYCLES_BEFORE_RUIN,
                            ArmiesConfig.WORLD_SIMULATION_MISSING_YEARS_BEFORE_RUIN);
            int foundingCooldownMilliYears = schemaVersion >= 2
                    ? settlement.getInt("FoundingCooldownMilliYears")
                    : migrateCycleProgress(
                            settlement.getInt("FoundingCooldown"),
                            ArmiesConfig.WORLD_SIMULATION_FOUNDING_COOLDOWN_CYCLES,
                            ArmiesConfig.WORLD_SIMULATION_FOUNDING_COOLDOWN_YEARS);
            int restoredRow = state.restoreRow(
                    settlementId,
                    cultureKey,
                    settlement.getLong("RealmId"),
                    settlement.getLong("RegionKey"),
                    settlement.getLong("ObservedPopulation"),
                    settlement.getLong("HousingCapacity"),
                    settlement.getInt("BuildingCount"),
                    settlement.getInt("ProductiveBuildings"),
                    settlement.getInt("MarketAccess"),
                    settlement.getInt("Security"),
                    settlement.getInt("Damage"),
                    settlement.getInt("Education"),
                    settlement.getInt("GeographicCapacity"),
                    settlement.getInt("Fertility"),
                    settlement.getInt("Specialization"),
                    settlement.getLong("Population"),
                    settlement.getInt("Productivity"),
                    settlement.getInt("Stability"),
                    settlement.getInt("Attractiveness"),
                    settlement.getInt("ProductiveCapital"),
                    SettlementStatus.values()[status],
                    SettlementTier.values()[tier],
                    declineMilliYears,
                    missingMilliYears,
                    foundingCooldownMilliYears,
                    settlement.getLong("NextDueTick"),
                    settlement.getBoolean("PhysicallyPresent"),
                    stocks,
                    prices,
                    flows);
            if (schemaVersion >= 2) {
                long[] flowRemainders = settlement.getLongArray("FlowRemainders");
                long[] priceMoveRemainders = settlement.getLongArray("PriceMoveRemainders");
                validateHistoricalResiduals(settlement, flowRemainders, priceMoveRemainders, row);
                state.restoreHistoricalState(
                        restoredRow,
                        settlement.getLong("HistoricalTimeRemainder"),
                        settlement.getLong("PopulationGrowthRemainder"),
                        settlement.getLong("PopulationObservationRemainder"),
                        settlement.getLong("CapitalMoveRemainder"),
                        settlement.getLong("ProductivityMoveRemainder"),
                        settlement.getLong("StabilityMoveRemainder"),
                        settlement.getLong("AttractivenessMoveRemainder"),
                        flowRemainders,
                        priceMoveRemainders);
            }
        }
        state.restoreRevision(tag.getLong("StateRevision"));

        SimulationEventJournal events = new SimulationEventJournal(
                ArmiesConfig.WORLD_SIMULATION_EVENT_CAPACITY);
        ListTag eventRows = tag.getList("Events", Tag.TAG_COMPOUND);
        if (eventRows.size() > events.capacity()) {
            throw new IllegalArgumentException("Restored simulation event journal exceeds capacity");
        }
        for (int row = 0; row < eventRows.size(); row++) {
            CompoundTag event = eventRows.getCompound(row);
            int type = Byte.toUnsignedInt(event.getByte("Type"));
            long settlementId = event.getLong("SettlementId");
            int cultureKey = event.getInt("CultureKey");
            if (type >= SimulationEventType.values().length
                    || !keys.validSettlement(settlementId)
                    || !keys.validCulture(cultureKey)) {
                throw new IllegalArgumentException("Invalid restored simulation event at row " + row);
            }
            long sourceSettlementId = event.getLong("SourceSettlementId");
            if (sourceSettlementId != 0L && !keys.validSettlement(sourceSettlementId)) {
                throw new IllegalArgumentException("Simulation event references unknown source settlement");
            }
            events.restore(
                    event.getLong("Sequence"),
                    new SimulationEvent(
                            SimulationEventType.values()[type],
                            settlementId,
                            sourceSettlementId,
                            cultureKey,
                            event.getLong("RealmId"),
                            event.getLong("RegionKey"),
                            event.getInt("Score"),
                            event.getInt("ReasonMask"),
                            event.getLong("Cycle")));
        }
        events.restoreMetadata(tag.getLong("NextEventSequence"), tag.getLong("DroppedEvents"));

        SimulationShockLedger shocks = new SimulationShockLedger(SHOCK_CAPACITY);
        ListTag shockRows = tag.getList("Shocks", Tag.TAG_COMPOUND);
        if (shockRows.size() > shocks.capacity()) {
            throw new IllegalArgumentException("Restored simulation shocks exceed capacity");
        }
        for (int row = 0; row < shockRows.size(); row++) {
            CompoundTag shock = shockRows.getCompound(row);
            int type = Byte.toUnsignedInt(shock.getByte("Type"));
            long targetSettlementId = shock.getLong("TargetSettlementId");
            int targetCultureKey = shock.getInt("TargetCultureKey");
            if (type >= ShockType.values().length
                    || targetSettlementId != 0L && !keys.validSettlement(targetSettlementId)
                    || targetCultureKey != 0 && !keys.validCulture(targetCultureKey)) {
                throw new IllegalArgumentException("Invalid restored simulation shock at row " + row);
            }
            shocks.restore(
                    ShockType.values()[type],
                    targetSettlementId,
                    shock.getLong("TargetRegionKey"),
                    targetCultureKey,
                    shock.getInt("Magnitude"),
                    shock.getLong("UntilCycle"));
        }
        long mutationSequence = tag.getLong("MutationSequence");
        int mutationAttempts = tag.getInt("MutationAttempts");
        long nextMutationAttemptTick = tag.getLong("NextMutationAttemptTick");
        if (mutationSequence < 0L || mutationAttempts < 0 || nextMutationAttemptTick < 0L
                || mutationSequence == 0L && (mutationAttempts != 0 || nextMutationAttemptTick != 0L)) {
            throw new IllegalArgumentException("Invalid restored world mutation retry state");
        }
        SimulationSavedData data = new SimulationSavedData(keys, state, events, shocks);
        data.mutationSequence = mutationSequence;
        data.mutationAttempts = mutationAttempts;
        data.nextMutationAttemptTick = nextMutationAttemptTick;
        return data;
    }

    private static int migrateCycleProgress(
            int oldCycles,
            int oldThresholdCycles,
            int newThresholdYears) {
        if (oldCycles < 0 || oldThresholdCycles <= 0 || newThresholdYears <= 0) {
            throw new IllegalArgumentException("Invalid schema-1 Simulation lifecycle progress");
        }
        long targetMilliYears = (long) newThresholdYears * 1000L;
        long migrated = (long) oldCycles * targetMilliYears / oldThresholdCycles;
        return migrated > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) migrated;
    }

    private static void validateHistoricalResiduals(
            CompoundTag settlement,
            long[] flowRemainders,
            long[] priceMoveRemainders,
            int row) {
        long historicalTimeRemainder = settlement.getLong("HistoricalTimeRemainder");
        if (historicalTimeRemainder < 0L
                || historicalTimeRemainder >= ArmiesConfig.HISTORICAL_YEAR_TICKS
                || flowRemainders.length != COMMODITY_COUNT
                || priceMoveRemainders.length != COMMODITY_COUNT) {
            throw new IllegalArgumentException(
                    "Invalid historical Simulation residual width/time at row " + row);
        }
        requireResidual(settlement.getLong("PopulationGrowthRemainder"), 1_000_000L, row);
        requireResidual(settlement.getLong("PopulationObservationRemainder"), 1_000_000L, row);
        requireResidual(settlement.getLong("CapitalMoveRemainder"), 1_000L, row);
        requireResidual(settlement.getLong("ProductivityMoveRemainder"), 1_000L, row);
        requireResidual(settlement.getLong("StabilityMoveRemainder"), 1_000L, row);
        requireResidual(settlement.getLong("AttractivenessMoveRemainder"), 1_000L, row);
        for (int commodity = 0; commodity < COMMODITY_COUNT; commodity++) {
            requireResidual(flowRemainders[commodity], 1_000L, row);
            requireResidual(priceMoveRemainders[commodity], 1_000_000L, row);
        }
    }

    private static void requireResidual(long value, long exclusiveBound, int row) {
        if (value <= -exclusiveBound || value >= exclusiveBound) {
            throw new IllegalArgumentException(
                    "Historical Simulation residual outside bounds at row " + row);
        }
    }

    private static ListTag saveNames(NameEmitter emitter) {
        ListTag rows = new ListTag();
        emitter.emit((key, name) -> {
            CompoundTag row = new CompoundTag();
            row.putString("Name", name);
            rows.add(row);
        });
        return rows;
    }

    private static void restoreNames(ListTag rows, boolean cultures, SimulationKeyTable keys) {
        int maximum = cultures
                ? ArmiesConfig.WORLD_SIMULATION_MAX_CULTURES
                : ArmiesConfig.WORLD_SIMULATION_MAX_DIMENSIONS;
        if (rows.size() > maximum) throw new IllegalArgumentException("Too many restored simulation names");
        for (int row = 0; row < rows.size(); row++) {
            ResourceLocation name = ResourceLocation.tryParse(rows.getCompound(row).getString("Name"));
            if (name == null) throw new IllegalArgumentException("Invalid restored simulation name");
            if (cultures) keys.restoreCulture(name); else keys.restoreDimension(name);
        }
    }

    @FunctionalInterface
    private interface NameEmitter {
        void emit(SimulationKeyTable.NameVisitor visitor);
    }
}
