package ru.kaiserroman.millenairearmies.server.settlement;

public final class PlayerSettlementPolicySelfTest {
    private PlayerSettlementPolicySelfTest() {}

    public static void main(String[] args) {
        var hamlet = PlayerSettlementPolicy.assess(64, 2, 12, 0L, 0);
        check(hamlet.tier() == PlayerSettlementTier.HAMLET, "new settlement is a hamlet");
        check(hamlet.territoryRadius() == PlayerSettlementPolicy.MINIMUM_RADIUS + 10,
                "minimum territory and bounded building growth are enforced");

        var village = PlayerSettlementPolicy.assess(96, 8, 55, 2 * 24_000L, 0);
        check(village.tier().atLeast(PlayerSettlementTier.VILLAGE), "village progression");
        check(village.territoryRadius() > hamlet.territoryRadius(), "territory grows");

        var cityState = PlayerSettlementPolicy.assess(128, 22, 320, 8 * 24_000L, 0);
        check(cityState.tier() == PlayerSettlementTier.CITY_STATE, "city-state progression");
        check(PlayerSettlementPolicy.conquestDistance(cityState.territoryRadius()) >= 1024,
                "city-state has a regional conquest radius");

        check(PlayerSettlementPolicy.allowsExtendedPlan(
                PlayerSettlementTier.HAMLET, "farm", false, false, false, false, 400),
                "hamlet can add economic buildings");
        check(!PlayerSettlementPolicy.allowsExtendedPlan(
                PlayerSettlementTier.HAMLET, "military", false, false, false, false, 400),
                "hamlet cannot add military buildings");
        check(PlayerSettlementPolicy.allowsExtendedPlan(
                PlayerSettlementTier.CITY_STATE, "military", false, false, false, false, 50_000),
                "city-state unlocks the full non-structural catalog");
        check(!PlayerSettlementPolicy.allowsExtendedPlan(
                PlayerSettlementTier.CITY_STATE, "capital", true, false, false, false, 0),
                "a second town hall is never offered");
        System.out.println("PlayerSettlementPolicySelfTest: OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
