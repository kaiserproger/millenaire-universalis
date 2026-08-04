package ru.kaiserroman.millenairearmies.server.execution;

import java.util.Arrays;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;

/** Two retained follow tasks per unit slot, alternating scheduler identity per revision. */
final class StrategicFollowTaskPool {
    private int[] unitHandles = new int[0];
    private byte[] nextLanes = new byte[0];
    private StrategicFollowTask[] laneZero = new StrategicFollowTask[0];
    private StrategicFollowTask[] laneOne = new StrategicFollowTask[0];

    StrategicFollowTask acquire(
            PackedUnitExecutionState state,
            OrderExecutionTelemetry telemetry,
            int unitHandle,
            int armyHandle,
            long revision,
            long ownerMost,
            long ownerLeast) {
        int slot = PackedArmyEcs.handleSlotIndex(unitHandle);
        ensureCapacity(slot + 1);
        if (unitHandles[slot] != unitHandle) {
            unitHandles[slot] = unitHandle;
            nextLanes[slot] = 0;
            laneZero[slot] = new StrategicFollowTask(state, telemetry, unitHandle);
            laneOne[slot] = new StrategicFollowTask(state, telemetry, unitHandle);
        }
        int lane = nextLanes[slot];
        nextLanes[slot] = (byte) (lane ^ 1);
        StrategicFollowTask task = lane == 0 ? laneZero[slot] : laneOne[slot];
        task.rearm(armyHandle, revision, ownerMost, ownerLeast);
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
