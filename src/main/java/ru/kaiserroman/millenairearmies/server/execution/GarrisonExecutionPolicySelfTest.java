package ru.kaiserroman.millenairearmies.server.execution;

/** Deterministic geometry/readiness checks for bounded physical garrison behavior. */
public final class GarrisonExecutionPolicySelfTest {
    private GarrisonExecutionPolicySelfTest() {
    }

    public static void main(String[] args) {
        check(!GarrisonExecutionPolicy.outsideRadius(31.9, 0.0, 0.0, 0.0, 32),
                "unit inside radius remains on station");
        check(GarrisonExecutionPolicy.outsideRadius(32.1, 0.0, 0.0, 0.0, 32),
                "unit outside radius returns to muster");
        check(GarrisonExecutionPolicy.targetInsideRadius(12.0, 12.0, 0.0, 0.0, 32),
                "defensive target inside post accepted");
        check(!GarrisonExecutionPolicy.targetInsideRadius(40.0, 0.0, 0.0, 0.0, 32),
                "target outside post rejected");
        check(GarrisonExecutionPolicy.mayAcquireTarget(20, 20), "minimum readiness can defend");
        check(!GarrisonExecutionPolicy.mayAcquireTarget(19, 100), "exhausted garrison will not chase");
        check(GarrisonExecutionPolicy.mustRegroup(100, 19), "broken morale returns to banner");
        check(GarrisonExecutionPolicy.movementSpeed(100, 100)
                        > GarrisonExecutionPolicy.movementSpeed(10, 10),
                "supply/readiness reduce movement without stopping physical navigation");
        System.out.println("Garrison execution policy self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
