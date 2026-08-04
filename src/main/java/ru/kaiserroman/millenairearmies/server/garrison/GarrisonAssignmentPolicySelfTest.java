package ru.kaiserroman.millenairearmies.server.garrison;

import ru.kaiserroman.millenairearmies.network.ArmiesProtocol;

/** Deterministic stale/authorization/geometry rejection contract for the server packet path. */
public final class GarrisonAssignmentPolicySelfTest {
    private GarrisonAssignmentPolicySelfTest() {
    }

    public static void main(String[] args) {
        check(GarrisonAssignmentPolicy.validateHeader(4L, 5L, true, true)
                        == ArmiesProtocol.RESULT_STALE,
                "stale state rejected before mutation");
        check(GarrisonAssignmentPolicy.validateHeader(5L, 5L, false, true)
                        == ArmiesProtocol.RESULT_NOT_FOUND,
                "dead/stale handle rejected");
        check(GarrisonAssignmentPolicy.validateHeader(5L, 5L, true, false)
                        == ArmiesProtocol.RESULT_PERMISSION_DENIED,
                "foreign army rejected");
        check(GarrisonAssignmentPolicy.validateAssignment(
                        false, true, true, true, true, 0L, 100L, 32, 12, 64)
                        == ArmiesProtocol.RESULT_INVALID,
                "wrong dimension rejected");
        check(GarrisonAssignmentPolicy.validateAssignment(
                        true, true, false, false, false, 0L, 100L, 32, 12, 64)
                        == ArmiesProtocol.RESULT_NOT_FOUND,
                "unknown settlement rejected");
        check(GarrisonAssignmentPolicy.validateAssignment(
                        true, true, true, false, true, 0L, 100L, 32, 12, 64)
                        == ArmiesProtocol.RESULT_PERMISSION_DENIED,
                "foreign settlement rejected");
        check(GarrisonAssignmentPolicy.validateAssignment(
                        true, true, true, true, false, 0L, 100L, 32, 12, 64)
                        == ArmiesProtocol.RESULT_PERMISSION_DENIED,
                "wrong-faction settlement rejected");
        check(GarrisonAssignmentPolicy.validateAssignment(
                        true, true, true, true, true, 101L, 100L, 32, 12, 64)
                        == ArmiesProtocol.RESULT_INVALID,
                "muster outside settlement bound rejected");
        check(GarrisonAssignmentPolicy.validateAssignment(
                        true, true, true, true, true, 100L, 100L, 32, 12, 64)
                        == ArmiesProtocol.RESULT_ACCEPTED,
                "boundary-valid controlled assignment accepted");
        System.out.println("Garrison assignment policy self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
