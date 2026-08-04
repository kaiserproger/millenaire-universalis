package ru.kaiserroman.millenairearmies.persistence;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.server.service.PackedArmyControllers;
import ru.kaiserroman.millenairearmies.server.unit.PackedUnitRoleState;

/**
 * Versioned Overworld SavedData owner for factions, the army ECS, persistent villager membership,
 * command/controller state, and logistics.
 *
 * <p>No server, level, entity, chunk, registry, or Millenaire object is retained. Call
 * {@link #setDirty()} after changing a non-ECS packed store, or {@link #markArmyChanged()} after an
 * ECS/controller/membership mutation.</p>
 */
public final class ArmySavedData extends SavedData {
    public static final String FILE_ID = "millenaire_armies";

    private static final SavedData.Factory<ArmySavedData> FACTORY =
            new SavedData.Factory<>(ArmySavedData::new, ArmySavedData::load);

    private final StableDimensionTable dimensions;
    private final StableItemTable items;
    private final PackedFactionState factions;
    private final PackedArmyEcs ecs;
    private final PackedUnitMembership memberships;
    private final PackedArmyControllers controllers;
    private final PackedCommandState commands;
    private final PackedLogisticsState logistics;
    private final PackedSettlementEconomyState settlementEconomy;
    private final PackedGarrisonState garrisons;
    private final PackedUnitRoleState unitRoles;
    private final PackedArmySupplyState armySupplies;
    private long armyRevision;

    public ArmySavedData() {
        this(
                defaultDimensions(),
                new StableItemTable(),
                new PackedFactionState(),
                new PackedArmyEcs(),
                new PackedUnitMembership(),
                new PackedArmyControllers(),
                0L,
                new PackedCommandState(),
                new PackedLogisticsState(),
                new PackedSettlementEconomyState(),
                new PackedGarrisonState(),
                new PackedUnitRoleState());
    }

    public ArmySavedData(PackedArmyEcs ecs, PackedCommandState commands) {
        this(
                defaultDimensions(),
                new StableItemTable(),
                new PackedFactionState(),
                ecs,
                new PackedUnitMembership(),
                new PackedArmyControllers(),
                0L,
                commands,
                new PackedLogisticsState(),
                new PackedSettlementEconomyState(),
                new PackedGarrisonState(),
                new PackedUnitRoleState());
    }

    public ArmySavedData(
            StableDimensionTable dimensions,
            StableItemTable items,
            PackedFactionState factions,
            PackedArmyEcs ecs,
            PackedUnitMembership memberships,
            PackedArmyControllers controllers,
            long armyRevision,
            PackedCommandState commands,
            PackedLogisticsState logistics) {
        this(
                dimensions,
                items,
                factions,
                ecs,
                memberships,
                controllers,
                armyRevision,
                commands,
                logistics,
                new PackedSettlementEconomyState());
    }

    public ArmySavedData(
            StableDimensionTable dimensions,
            StableItemTable items,
            PackedFactionState factions,
            PackedArmyEcs ecs,
            PackedUnitMembership memberships,
            PackedArmyControllers controllers,
            long armyRevision,
            PackedCommandState commands,
            PackedLogisticsState logistics,
            PackedSettlementEconomyState settlementEconomy) {
        this(
                dimensions,
                items,
                factions,
                ecs,
                memberships,
                controllers,
                armyRevision,
                commands,
                logistics,
                settlementEconomy,
                new PackedGarrisonState(),
                new PackedUnitRoleState());
    }

    public ArmySavedData(
            StableDimensionTable dimensions,
            StableItemTable items,
            PackedFactionState factions,
            PackedArmyEcs ecs,
            PackedUnitMembership memberships,
            PackedArmyControllers controllers,
            long armyRevision,
            PackedCommandState commands,
            PackedLogisticsState logistics,
            PackedSettlementEconomyState settlementEconomy,
            PackedGarrisonState garrisons) {
        this(
                dimensions,
                items,
                factions,
                ecs,
                memberships,
                controllers,
                armyRevision,
                commands,
                logistics,
                settlementEconomy,
                garrisons,
                new PackedUnitRoleState());
    }

    public ArmySavedData(
            StableDimensionTable dimensions,
            StableItemTable items,
            PackedFactionState factions,
            PackedArmyEcs ecs,
            PackedUnitMembership memberships,
            PackedArmyControllers controllers,
            long armyRevision,
            PackedCommandState commands,
            PackedLogisticsState logistics,
            PackedSettlementEconomyState settlementEconomy,
            PackedGarrisonState garrisons,
            PackedUnitRoleState unitRoles) {
        this(
                dimensions, items, factions, ecs, memberships, controllers, armyRevision,
                commands, logistics, settlementEconomy, garrisons, unitRoles,
                new PackedArmySupplyState());
    }

    public ArmySavedData(
            StableDimensionTable dimensions,
            StableItemTable items,
            PackedFactionState factions,
            PackedArmyEcs ecs,
            PackedUnitMembership memberships,
            PackedArmyControllers controllers,
            long armyRevision,
            PackedCommandState commands,
            PackedLogisticsState logistics,
            PackedSettlementEconomyState settlementEconomy,
            PackedGarrisonState garrisons,
            PackedUnitRoleState unitRoles,
            PackedArmySupplyState armySupplies) {
        if (dimensions == null
                || items == null
                || factions == null
                || ecs == null
                || memberships == null
                || controllers == null
                || commands == null
                || logistics == null
                || settlementEconomy == null
                || garrisons == null
                || unitRoles == null
                || armySupplies == null) {
            throw new NullPointerException("Army SavedData stores");
        }
        if (armyRevision < 0L) {
            throw new IllegalArgumentException("Army revision must be non-negative");
        }
        this.dimensions = dimensions;
        this.items = items;
        this.factions = factions;
        this.ecs = ecs;
        this.memberships = memberships;
        this.controllers = controllers;
        this.armyRevision = armyRevision;
        this.commands = commands;
        this.logistics = logistics;
        this.settlementEconomy = settlementEconomy;
        this.garrisons = garrisons;
        this.unitRoles = unitRoles;
        this.armySupplies = armySupplies;
    }

    public static SavedData.Factory<ArmySavedData> factory() {
        return FACTORY;
    }

    /** Resolves the singleton from the Overworld regardless of the caller's current dimension. */
    public static ArmySavedData get(ServerLevel anyLevel) {
        return get(anyLevel.getServer());
    }

    /** Resolves the singleton from the server's Overworld data storage. */
    public static ArmySavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    public PackedArmyEcs ecs() {
        return ecs;
    }

    public StableDimensionTable dimensions() {
        return dimensions;
    }

    public StableItemTable items() {
        return items;
    }

    public PackedFactionState factions() {
        return factions;
    }

    public PackedUnitMembership memberships() {
        return memberships;
    }

    public PackedArmyControllers controllers() {
        return controllers;
    }

    public PackedCommandState commands() {
        return commands;
    }

    public PackedLogisticsState logistics() {
        return logistics;
    }

    public PackedSettlementEconomyState settlementEconomy() {
        return settlementEconomy;
    }

    public PackedGarrisonState garrisons() {
        return garrisons;
    }

    public PackedUnitRoleState unitRoles() {
        return unitRoles;
    }

    public PackedArmySupplyState armySupplies() {
        return armySupplies;
    }

    public long armyRevision() {
        return armyRevision;
    }

    /** Marks an ECS mutation for subsystem-level delta sync and world saving. */
    public void markArmyChanged() {
        if (armyRevision == Long.MAX_VALUE) {
            throw new IllegalStateException("Army revision space exhausted");
        }
        armyRevision++;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return ArmyNbtCodec.save(
                tag,
                dimensions,
                items,
                factions,
                ecs,
                memberships,
                controllers,
                armyRevision,
                commands,
                logistics,
                settlementEconomy,
                garrisons,
                unitRoles,
                armySupplies);
    }

    public static ArmySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        ArmyNbtCodec.LoadedState loaded = ArmyNbtCodec.load(
                tag,
                maxFactionRelations(),
                ArmiesConfig.MAX_PENDING_ORDERS,
                ArmiesConfig.MAX_LOGISTICS_REQUESTS,
                ArmiesConfig.MAX_SETTLEMENTS,
                ArmiesConfig.MAX_SETTLEMENT_SHIPMENTS);
        return new ArmySavedData(
                loaded.dimensions(),
                loaded.items(),
                loaded.factions(),
                loaded.ecs(),
                loaded.memberships(),
                loaded.controllers(),
                loaded.armyRevision(),
                loaded.commands(),
                loaded.logistics(),
                loaded.settlementEconomy(),
                loaded.garrisons(),
                loaded.unitRoles(),
                loaded.armySupplies());
    }

    private static StableDimensionTable defaultDimensions() {
        StableDimensionTable dimensions = new StableDimensionTable();
        dimensions.intern(Level.OVERWORLD.location());
        return dimensions;
    }

    private static int maxFactionRelations() {
        long configured = (long) ArmiesConfig.MAX_FACTIONS * Math.max(0, ArmiesConfig.MAX_FACTIONS - 1);
        return (int) Math.min(4_000_000L, configured);
    }
}
