package ru.kaiserroman.millenairearmies.model;

/**
 * Allocation-free payload fields for a command. Positions use Minecraft's packed BlockPos long
 * representation; dimensions and entities use runtime integer ids resolved outside the hot loop.
 */
public record ArmyOrder(
        long orderId,
        int armyId,
        int issuerFactionId,
        byte typeCode,
        int dimensionId,
        long primaryPosition,
        long secondaryPosition,
        int subjectEntityId,
        long issuedGameTime,
        byte flags) {
    public static final byte FLAG_HAS_SECONDARY_POSITION = 1;
    public static final byte FLAG_HAS_SUBJECT_ENTITY = 1 << 1;

    public ArmyOrder {
        if (orderId < 0 || armyId < 0 || issuerFactionId < 0 || dimensionId < 0 || issuedGameTime < 0) {
            throw new IllegalArgumentException("Order, army, faction, dimension and time ids must be non-negative");
        }
        if (!ArmyOrderType.isValidCode(typeCode)) {
            throw new IllegalArgumentException("Unknown army order code: " + typeCode);
        }
        if ((flags & FLAG_HAS_SUBJECT_ENTITY) != 0 && subjectEntityId < 0) {
            throw new IllegalArgumentException("A present subject entity must have a non-negative runtime id");
        }
    }

    public ArmyOrderType type() {
        return ArmyOrderType.fromCode(typeCode);
    }

    public boolean hasSecondaryPosition() {
        return (flags & FLAG_HAS_SECONDARY_POSITION) != 0;
    }

    public boolean hasSubjectEntity() {
        return (flags & FLAG_HAS_SUBJECT_ENTITY) != 0;
    }
}
