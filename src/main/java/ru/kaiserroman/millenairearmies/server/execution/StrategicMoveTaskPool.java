package ru.kaiserroman.millenairearmies.server.execution;

import java.util.Arrays;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;

/**
 * Two retained tasks per executed ECS slot. Alternation makes Millenaire's scheduler see a new
 * task identity for successive committed orders while eliminating repeat task allocations.
 */
final class StrategicMoveTaskPool {
    private int[] unitHandles = new int[0];
    private byte[] nextLanes = new byte[0];
    private StrategicMoveTask[] laneZero = new StrategicMoveTask[0];
    private StrategicMoveTask[] laneOne = new StrategicMoveTask[0];

    StrategicMoveTask acquire(
            PackedUnitExecutionState state,
            int unitHandle,
            int armyHandle,
            long revision,
            long packedTarget) {
        int slot = PackedArmyEcs.handleSlotIndex(unitHandle);
        ensureCapacity(slot + 1);
        if (unitHandles[slot] != unitHandle) {
            unitHandles[slot] = unitHandle;
            nextLanes[slot] = 0;
            laneZero[slot] = new StrategicMoveTask(state, unitHandle);
            laneOne[slot] = new StrategicMoveTask(state, unitHandle);
        }
        int lane = nextLanes[slot];
        nextLanes[slot] = (byte) (lane ^ 1);
        StrategicMoveTask task = lane == 0 ? laneZero[slot] : laneOne[slot];
        task.rearm(armyHandle, revision, packedTarget);
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
