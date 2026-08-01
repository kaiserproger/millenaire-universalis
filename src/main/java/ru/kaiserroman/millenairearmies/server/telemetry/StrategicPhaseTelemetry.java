package ru.kaiserroman.millenairearmies.server.telemetry;

/** Fixed primitive counters for measuring future snapshot/compute/commit seams without allocation. */
public final class StrategicPhaseTelemetry {
    public static final int SUPPLY_PUBLISH = 0;
    public static final int LOGISTICS = 1;
    public static final int DIPLOMACY = 2;
    public static final int ORDER_EXECUTION = 3;
    public static final int MILLENAIRE_CAPTURE = 4;
    public static final int FACTION_PROJECTION = 5;
    public static final int ENTITY_RECONCILE = 6;
    public static final int PHASE_COUNT = 7;

    private final long[] calls = new long[PHASE_COUNT];
    private final long[] totalNanos = new long[PHASE_COUNT];
    private final long[] maxNanos = new long[PHASE_COUNT];
    private final long[] workUnits = new long[PHASE_COUNT];

    public void reset() {
        for (int phase = 0; phase < PHASE_COUNT; phase++) {
            calls[phase] = 0L;
            totalNanos[phase] = 0L;
            maxNanos[phase] = 0L;
            workUnits[phase] = 0L;
        }
    }

    public void record(int phase, long elapsedNanos, int observedWorkUnits) {
        if (phase < 0 || phase >= PHASE_COUNT || elapsedNanos < 0L || observedWorkUnits < 0) {
            throw new IllegalArgumentException("Invalid strategic phase measurement");
        }
        calls[phase] = calls[phase] == Long.MAX_VALUE ? Long.MAX_VALUE : calls[phase] + 1L;
        totalNanos[phase] = saturatedAdd(totalNanos[phase], elapsedNanos);
        maxNanos[phase] = Math.max(maxNanos[phase], elapsedNanos);
        workUnits[phase] = saturatedAdd(workUnits[phase], observedWorkUnits);
    }

    public long calls(int phase) {
        return calls[phase];
    }

    public long totalNanos(int phase) {
        return totalNanos[phase];
    }

    public long maxNanos(int phase) {
        return maxNanos[phase];
    }

    public long workUnits(int phase) {
        return workUnits[phase];
    }

    private static long saturatedAdd(long value, long increment) {
        return value > Long.MAX_VALUE - increment ? Long.MAX_VALUE : value + increment;
    }
}
