package ru.kaiserroman.millenairearmies.server.realm;

/** Deterministic loyal answer, refusal and rebellion coverage. */
public final class FeudalLevyPolicySelfTest {
    private FeudalLevyPolicySelfTest() {}

    public static void main(String[] args) {
        FeudalLevyPolicy policy = new FeudalLevyPolicy(470, 310, 24);

        FeudalLevyPolicy.Decision loyal = policy.evaluate(
                820, 350, 420, 450, 700, 640L, 20);
        check(loyal.response() == FeudalLevyPolicy.Response.ANSWER, "loyal lord answers");
        check(loyal.availableLevy() > 0 && loyal.availableLevy() <= 20,
                "answered levy is bounded by request and demography");

        FeudalLevyPolicy.Decision refusal = policy.evaluate(
                420, 780, 680, 650, 500, 500L, 20);
        check(refusal.response() == FeudalLevyPolicy.Response.REFUSE,
                "over-centralised weak crown can be refused");
        check(refusal.availableLevy() == 0, "refusal mobilises nobody");

        FeudalLevyPolicy.Decision rebel = policy.evaluate(
                180, 850, 820, 840, 780, 900L, 24);
        check(rebel.response() == FeudalLevyPolicy.Response.REBEL,
                "powerful lord rebels against illegitimate crown");
        check((rebel.reasonMask() & FeudalLevyPolicy.REASON_LOW_LEGITIMACY) != 0,
                "rebellion reason is auditable");
        check(rebel.separatism() > refusal.separatism(), "rebellion pressure is stronger");

        System.out.println("FeudalLevyPolicySelfTest: OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
