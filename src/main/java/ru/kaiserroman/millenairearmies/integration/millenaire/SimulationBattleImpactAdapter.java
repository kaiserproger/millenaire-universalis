package ru.kaiserroman.millenairearmies.integration.millenaire;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import ru.kaiserroman.millenaire.simulation.ShockType;
import ru.kaiserroman.millenaire.simulation.WorldShock;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.persistence.SimulationKeyTable;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;
import ru.kaiserroman.millenairearmies.persistence.StableDimensionTable;
import ru.kaiserroman.millenairearmies.server.execution.PhysicalBattleEventLog;

/**
 * Converts neutral physical Armies facts into persisted regional Simulation pressure. It consumes
 * only the battle event stream and never reads army ECS rows or mutates a Millenaire village.
 */
public final class SimulationBattleImpactAdapter {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final PhysicalBattleEventLog.Cursor cursor;
    private final StableDimensionTable armyDimensions;
    private final MillenaireWorldSimulationBridge simulation;
    private final SimulationSavedData data;
    private final SimulationKeyTable simulationKeys;

    private long processedEvents;
    private long appliedImpacts;
    private long ignoredEvents;
    private long rejectedImpacts;
    private long lastDroppedCount;

    public SimulationBattleImpactAdapter(
            PhysicalBattleEventLog battleEvents,
            StableDimensionTable armyDimensions,
            MillenaireWorldSimulationBridge simulation) {
        if (battleEvents == null || armyDimensions == null || simulation == null) {
            throw new NullPointerException("Simulation battle impact dependency");
        }
        cursor = battleEvents.cursor();
        this.armyDimensions = armyDimensions;
        this.simulation = simulation;
        data = simulation.savedData();
        simulationKeys = data.keys();
    }

    public int tick(int maximumEvents) {
        if (maximumEvents <= 0) {
            throw new IllegalArgumentException("maximumEvents must be positive");
        }
        int processed = 0;
        while (processed < maximumEvents && cursor.advance()) {
            processed++;
            processedEvents++;
            applyCurrent();
        }
        long dropped = cursor.droppedCount();
        if (dropped != lastDroppedCount) {
            long delta = dropped - lastDroppedCount;
            lastDroppedCount = dropped;
            LOGGER.warn(
                    "Simulation battle impact consumer lost {} overwritten physical events; total_dropped={}",
                    delta,
                    dropped);
        }
        return processed;
    }

    private void applyCurrent() {
        Impact impact = impact(cursor.kind(), cursor.amount());
        if (impact == null) {
            ignoredEvents++;
            return;
        }
        int dimensionId = cursor.dimensionId();
        if (dimensionId < 0 || dimensionId >= armyDimensions.size()) {
            rejectedImpacts++;
            return;
        }
        ResourceLocation dimension = armyDimensions.name(dimensionId);
        int oldDimensions = simulationKeys.dimensionCount();
        int simulationDimension = simulationKeys.internDimension(dimension);
        if (oldDimensions != simulationKeys.dimensionCount()) {
            data.markChanged();
        }
        BlockPos position = new BlockPos(
                PackedArmyEcs.unpackBlockX(cursor.packedPosition()),
                PackedArmyEcs.unpackBlockY(cursor.packedPosition()),
                PackedArmyEcs.unpackBlockZ(cursor.packedPosition()));
        long regionKey = MillenaireWorldSimulationBridge.packRegion(
                simulationDimension,
                position,
                ru.kaiserroman.millenairearmies.ArmiesConfig.WORLD_SIMULATION_REGION_SIZE_BLOCKS);
        boolean accepted = simulation.applyShock(
                new WorldShock(
                        ShockType.WAR_DEVASTATION,
                        0L,
                        regionKey,
                        0,
                        impact.magnitude(),
                        impact.remainingCycles()),
                cursor.gameTime());
        if (!accepted) {
            rejectedImpacts++;
            return;
        }
        appliedImpacts++;
        data.markChanged();
    }

    private static Impact impact(byte kind, int amount) {
        return switch (kind) {
            case PhysicalBattleEventLog.UNIT_DEFEATED -> new Impact(120, 2);
            case PhysicalBattleEventLog.SIEGE_STARTED ->
                    new Impact(clamp(180 + amount * 2), 4);
            case PhysicalBattleEventLog.SIEGE_PROGRESS ->
                    new Impact(clamp(100 + amount * 4), 4);
            case PhysicalBattleEventLog.SIEGE_SECURED ->
                    new Impact(clamp(700 + amount * 2), 8);
            default -> null;
        };
    }

    public long processedEventCount() { return processedEvents; }
    public long appliedImpactCount() { return appliedImpacts; }
    public long ignoredEventCount() { return ignoredEvents; }
    public long rejectedImpactCount() { return rejectedImpacts; }
    public long droppedEventCount() { return lastDroppedCount; }

    public void logShutdownMetrics() {
        LOGGER.info(
                "[BANNEROK_SIMULATION_BATTLE_IMPACTS] processed={} applied={} ignored={} rejected={} dropped={}",
                processedEvents,
                appliedImpacts,
                ignoredEvents,
                rejectedImpacts,
                lastDroppedCount);
    }

    private static int clamp(int value) {
        return Math.max(1, Math.min(1000, value));
    }

    private record Impact(int magnitude, int remainingCycles) {
    }
}
