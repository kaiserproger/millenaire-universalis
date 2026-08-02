package ru.kaiserroman.millenairearmies.server.execution;

/** Server-thread aggregate counters. State transitions are observable without per-unit log spam. */
public final class OrderExecutionTelemetry {
    private long accepted;
    private long executing;
    private long arrived;
    private long blocked;

    void accepted() {
        accepted++;
    }

    void executing() {
        executing++;
    }

    void arrived() {
        arrived++;
    }

    void blocked() {
        blocked++;
    }

    public long acceptedCount() {
        return accepted;
    }

    public long executingCount() {
        return executing;
    }

    public long arrivedCount() {
        return arrived;
    }

    public long blockedCount() {
        return blocked;
    }

    public long transitionCount() {
        return accepted + executing + arrived + blocked;
    }

    void reset() {
        accepted = 0L;
        executing = 0L;
        arrived = 0L;
        blocked = 0L;
    }
}
