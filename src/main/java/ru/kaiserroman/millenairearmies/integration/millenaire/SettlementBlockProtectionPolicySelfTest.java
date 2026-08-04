package ru.kaiserroman.millenairearmies.integration.millenaire;

/** Deterministic policy coverage for foreign settlement blocks and bounded siege breaches. */
public final class SettlementBlockProtectionPolicySelfTest {
    private SettlementBlockProtectionPolicySelfTest() {}

    public static void main(String[] args) {
        SettlementBlockProtectionPolicy policy = new SettlementBlockProtectionPolicy(20, 125);

        check(policy.decide(false, false, false, false, false)
                == SettlementBlockProtectionPolicy.Decision.OUTSIDE, "outside");
        check(policy.decide(true, true, true, true, false)
                == SettlementBlockProtectionPolicy.Decision.AUTHORIZED, "owner bypass");
        check(policy.decide(true, false, false, false, false)
                == SettlementBlockProtectionPolicy.Decision.FOREIGN_SLOWED, "foreign slowdown");
        check(policy.decide(true, false, true, false, true)
                == SettlementBlockProtectionPolicy.Decision.DENY_CRITICAL, "critical denied");
        check(policy.decide(true, false, false, true, false)
                == SettlementBlockProtectionPolicy.Decision.DENY_INTERIOR_DURING_SIEGE,
                "siege interior denied");
        check(policy.decide(true, false, false, true, true)
                == SettlementBlockProtectionPolicy.Decision.SIEGE_BREACH_SLOWED,
                "perimeter breach allowed");

        float normal = 4.0F;
        check(close(policy.adjustedSpeed(normal,
                SettlementBlockProtectionPolicy.Decision.FOREIGN_SLOWED), 0.08F),
                "foreign speed is 2 percent");
        check(close(policy.adjustedSpeed(normal,
                SettlementBlockProtectionPolicy.Decision.SIEGE_BREACH_SLOWED), 0.5F),
                "siege breach speed is 12.5 percent");
        check(policy.cancelFinalBreak(SettlementBlockProtectionPolicy.Decision.DENY_CRITICAL),
                "critical cancellation");
        check(!policy.cancelFinalBreak(SettlementBlockProtectionPolicy.Decision.FOREIGN_SLOWED),
                "peacetime slow path remains breakable");

        System.out.println("SettlementBlockProtectionPolicySelfTest: OK");
    }

    private static boolean close(float left, float right) {
        return Math.abs(left - right) < 0.00001F;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
