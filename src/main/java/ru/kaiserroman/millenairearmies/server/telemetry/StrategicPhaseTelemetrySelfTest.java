package ru.kaiserroman.millenairearmies.server.telemetry;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;

/** Allocation and counter checks for the primitive phase-observation seam. */
public final class StrategicPhaseTelemetrySelfTest {
    private StrategicPhaseTelemetrySelfTest() {}

    public static void main(String[] args) {
        StrategicPhaseTelemetry telemetry = new StrategicPhaseTelemetry();
        for (int pass = 0; pass < 10_000; pass++) {
            telemetry.record(pass % StrategicPhaseTelemetry.PHASE_COUNT, pass & 255, pass & 7);
        }
        telemetry.reset();

        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(thread);
        long expectedNanos = 0L;
        long expectedWork = 0L;
        for (int pass = 0; pass < 1_000_000; pass++) {
            int nanos = pass & 255;
            int work = pass & 7;
            telemetry.record(StrategicPhaseTelemetry.FACTION_PROJECTION, nanos, work);
            expectedNanos += nanos;
            expectedWork += work;
        }
        long allocated = bean.getThreadAllocatedBytes(thread) - before;
        check(allocated <= 512L, "phase recording allocated proportionally: " + allocated);
        check(telemetry.calls(StrategicPhaseTelemetry.FACTION_PROJECTION) == 1_000_000L, "call count");
        check(telemetry.totalNanos(StrategicPhaseTelemetry.FACTION_PROJECTION) == expectedNanos, "nanos");
        check(telemetry.workUnits(StrategicPhaseTelemetry.FACTION_PROJECTION) == expectedWork, "work units");
        check(telemetry.maxNanos(StrategicPhaseTelemetry.FACTION_PROJECTION) == 255L, "max nanos");
        System.out.println("StrategicPhaseTelemetrySelfTest PASS; 1m records allocated " + allocated + " B");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
