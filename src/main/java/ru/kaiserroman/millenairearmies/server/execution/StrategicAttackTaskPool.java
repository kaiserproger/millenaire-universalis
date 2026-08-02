package ru.kaiserroman.millenairearmies.server.execution;

import java.util.Arrays;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;

/** Two retained attack task lanes per ECS unit slot. */
final class StrategicAttackTaskPool {
    private int[] unitHandles = new int[0];
    private byte[] nextLanes = new byte[0];
    private StrategicAttackTask[] laneZero = new StrategicAttackTask[0];
    private StrategicAttackTask[] laneOne = new StrategicAttackTask[0];

    StrategicAttackTask acquire(
            PackedUnitExecutionState state,
            PhysicalBattleCoordinator battles,
            int unitHandle,
            int armyHandle,
            long revision,
            long packedTarget) {
        int slot = PackedArmyEcs.handleSlotIndex(unitHandle);
        ensureCapacity(slot + 1);
        if (unitHandles[slot] != unitHandle) {
            unitHandles[slot] = unitHandle;
            nextLanes[slot] = 0;
            laneZero[slot] = new StrategicAttackTask(state, battles, unitHandle);
            laneOne[slot] = new StrategicAttackTask(state, battles, unitHandle);
        }
        int lane = nextLanes[slot];
        nextLanes[slot] = (byte) (lane ^ 1);
        StrategicAttackTask task = lane == 0 ? laneZero[slot] : laneOne[slot];
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
        if (required <= unitHandles.length) return;
        int current = unitHandles.length;
        int capacity = Math.max(required, current < 16 ? 16 : current + (current >>> 1));
        unitHandles = Arrays.copyOf(unitHandles, capacity);
        nextLanes = Arrays.copyOf(nextLanes, capacity);
        laneZero = Arrays.copyOf(laneZero, capacity);
        laneOne = Arrays.copyOf(laneOne, capacity);
    }
}
