package ru.kaiserroman.millenairearmies.server.telemetry;

import ru.kaiserroman.millenairearmies.ArmiesConfig;

/** Proves that a stress-harness request cannot silently activate an unprofiled worker kernel. */
public final class StrategicWorkerGateSelfTest {
    private StrategicWorkerGateSelfTest() {}

    public static void main(String[] args) {
        if (ArmiesConfig.REQUESTED_STRATEGIC_WORKER_COUNT != 2) {
            throw new AssertionError("test JVM did not expose bannerok.experimental.workerCount=2");
        }
        if (ArmiesConfig.ACTIVE_STRATEGIC_WORKER_COUNT != 0) {
            throw new AssertionError("unprofiled strategic workers became active");
        }
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getName().startsWith("millarmies-strategy-")) {
                throw new AssertionError("unexpected strategic worker thread " + thread.getName());
            }
        }
        System.out.println("StrategicWorkerGateSelfTest PASS; requested=2 active=0 status=NOT_APPLICABLE");
    }
}
