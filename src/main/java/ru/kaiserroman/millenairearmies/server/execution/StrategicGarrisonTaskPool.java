package ru.kaiserroman.millenairearmies.server.execution;

import java.util.Arrays;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.integration.millenaire.FactionProjectionService;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireEntityBridge;
import ru.kaiserroman.millenairearmies.server.supply.ArmySupplyAccess;

/** Two retained garrison tasks per unit slot, alternating scheduler identity per revision. */
final class StrategicGarrisonTaskPool {
    private int[] unitHandles = new int[0];
    private byte[] nextLanes = new byte[0];
    private StrategicGarrisonTask[] laneZero = new StrategicGarrisonTask[0];
    private StrategicGarrisonTask[] laneOne = new StrategicGarrisonTask[0];

    StrategicGarrisonTask acquire(
            PackedUnitExecutionState state,
            OrderExecutionTelemetry telemetry,
            ArmyFormationCoordinator formations,
            ArmyBattleCoordinator battles,
            MillenaireEntityBridge entities,
            FactionProjectionService factions,
            ArmySupplyAccess supplies,
            int unitHandle,
            int armyHandle,
            long revision,
            long packedMuster,
            int guardRadius,
            int formationCode,
            int expectedUnits,
            int sourceFaction,
            int supplyPercent,
            int readinessPercent,
            int moralePercent,
            boolean shieldWall,
            boolean fireAtWill) {
        int slot = PackedArmyEcs.handleSlotIndex(unitHandle);
        ensureCapacity(slot + 1);
        if (unitHandles[slot] != unitHandle) {
            unitHandles[slot] = unitHandle;
            nextLanes[slot] = 0;
            laneZero[slot] = new StrategicGarrisonTask(
                    state, telemetry, formations, battles, entities, factions, supplies, unitHandle);
            laneOne[slot] = new StrategicGarrisonTask(
                    state, telemetry, formations, battles, entities, factions, supplies, unitHandle);
        }
        int lane = nextLanes[slot];
        nextLanes[slot] = (byte) (lane ^ 1);
        StrategicGarrisonTask task = lane == 0 ? laneZero[slot] : laneOne[slot];
        task.rearm(
                armyHandle,
                revision,
                packedMuster,
                guardRadius,
                formationCode,
                expectedUnits,
                sourceFaction,
                supplyPercent,
                readinessPercent,
                moralePercent,
                shieldWall,
                fireAtWill);
        return task;
    }

    void clear() {
        Arrays.fill(unitHandles, 0);
        Arrays.fill(nextLanes, (byte) 0);
        Arrays.fill(laneZero, null);
        Arrays.fill(laneOne, null);
    }

    private void ensureCapacity(int required) {
        if (required <= unitHandles.length) {
            return;
        }
        int current = unitHandles.length;
        int capacity = Math.max(required, current < 16 ? 16 : current + (current >>> 1));
        unitHandles = Arrays.copyOf(unitHandles, capacity);
        nextLanes = Arrays.copyOf(nextLanes, capacity);
        laneZero = Arrays.copyOf(laneZero, capacity);
        laneOne = Arrays.copyOf(laneOne, capacity);
    }
}
