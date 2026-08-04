package ru.kaiserroman.millenairearmies.server.garrison;

import ru.kaiserroman.millenairearmies.network.ArmiesProtocol;

/** Pure fail-closed authorization/geometry policy used by the packet handler and self-tests. */
public final class GarrisonAssignmentPolicy {
    private GarrisonAssignmentPolicy() {
    }

    public static int validateHeader(
            long expectedRevision,
            long currentRevision,
            boolean armyAlive,
            boolean armyControlled) {
        if (expectedRevision != currentRevision) return ArmiesProtocol.RESULT_STALE;
        if (!armyAlive) return ArmiesProtocol.RESULT_NOT_FOUND;
        if (!armyControlled) return ArmiesProtocol.RESULT_PERMISSION_DENIED;
        return ArmiesProtocol.RESULT_ACCEPTED;
    }

    public static int validateAssignment(
            boolean sameDimension,
            boolean musterInsideWorld,
            boolean settlementFound,
            boolean settlementControlled,
            boolean factionMatches,
            long horizontalDistanceSquared,
            long maximumDistanceSquared,
            int radius,
            int minimumRadius,
            int maximumRadius) {
        if (!sameDimension || !musterInsideWorld) return ArmiesProtocol.RESULT_INVALID;
        if (!settlementFound) return ArmiesProtocol.RESULT_NOT_FOUND;
        if (!settlementControlled || !factionMatches) return ArmiesProtocol.RESULT_PERMISSION_DENIED;
        if (horizontalDistanceSquared < 0L
                || maximumDistanceSquared < 0L
                || horizontalDistanceSquared > maximumDistanceSquared
                || radius < minimumRadius
                || radius > maximumRadius) {
            return ArmiesProtocol.RESULT_INVALID;
        }
        return ArmiesProtocol.RESULT_ACCEPTED;
    }
}
