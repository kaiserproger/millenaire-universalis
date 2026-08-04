package ru.kaiserroman.millenairearmies.server.execution;

/** Pure bounded geometry/readiness policy shared by the physical task and deterministic self-tests. */
public final class GarrisonExecutionPolicy {
    private GarrisonExecutionPolicy() {
    }

    public static boolean outsideRadius(
            double x, double z, double musterX, double musterZ, int radius) {
        if (radius <= 0) {
            return true;
        }
        double dx = x - musterX;
        double dz = z - musterZ;
        return dx * dx + dz * dz > (double) radius * radius;
    }

    public static boolean targetInsideRadius(
            double targetX, double targetZ, double musterX, double musterZ, int radius) {
        return !outsideRadius(targetX, targetZ, musterX, musterZ, radius);
    }

    public static boolean mayAcquireTarget(int readinessPercent, int moralePercent) {
        return readinessPercent >= 20 && moralePercent >= 20;
    }

    public static boolean mustRegroup(int readinessPercent, int moralePercent) {
        return readinessPercent < 20 || moralePercent < 20;
    }

    public static double movementSpeed(int readinessPercent, int supplyPercent) {
        int floor = Math.max(0, Math.min(100, Math.min(readinessPercent, supplyPercent)));
        return 0.38D + floor * 0.0025D;
    }
}
