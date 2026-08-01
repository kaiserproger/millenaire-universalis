package ru.kaiserroman.millenairearmies.server.diplomacy;

import ru.kaiserroman.millenairearmies.model.FactionAllegiance;
import ru.kaiserroman.millenairearmies.persistence.PackedFactionState;

/** Lightweight deterministic test runnable without a Minecraft client or world. */
public final class PackedDiplomacyEngineSelfTest {
    private PackedDiplomacyEngineSelfTest() {}

    public static void main(String[] arguments) {
        PackedFactionState firstState = new PackedFactionState(16);
        PackedDiplomacyEngine first = new PackedDiplomacyEngine(firstState, 8, 8);

        check(first.relationState(0, 1) == DiplomacyRelation.STATE_NEUTRAL, "initial neutral state");
        check(first.apply(DiplomacyCommand.DECLARE_WAR, 0, 1, 0) == PackedDiplomacyEngine.APPLIED, "war");
        check((first.relationFlags(0, 1) & DiplomacyRelation.WAR) != 0, "symmetric war flag");
        check(first.reputation(0, 1) == -750 && first.reputation(1, 0) == -750, "war reputation");

        check(first.schedule(20, DiplomacyCommand.SET_REPUTATION, 0, 1, -100)
                        == PackedDiplomacyEngine.APPLIED,
                "schedule reputation");
        check(first.schedule(20, DiplomacyCommand.MAKE_PEACE, 0, 1, 0)
                        == PackedDiplomacyEngine.APPLIED,
                "schedule peace");
        check(first.schedule(30, DiplomacyCommand.FORM_ALLIANCE, 0, 1, 0)
                        == PackedDiplomacyEngine.APPLIED,
                "schedule alliance");
        check(first.schedule(30, DiplomacyCommand.BECOME_VASSAL, 0, 1, 0)
                        == PackedDiplomacyEngine.APPLIED,
                "schedule vassalage");
        check(first.processDue(19, 8) == 0, "nothing early");
        check(first.processDue(20, 1) == 1, "budgeted first command");
        check(first.relationState(0, 1) == DiplomacyRelation.STATE_WAR, "same-tick sequence is retained");
        check(first.processDue(20, 8) == 1, "budgeted second command");
        check(first.relationState(0, 1) == DiplomacyRelation.STATE_NEUTRAL, "peace");
        check(first.processDue(30, 8) == 2, "two deterministic treaty commands");
        check(first.relationState(0, 1) == DiplomacyRelation.STATE_VASSAL, "vassal state wins by sequence");
        check(first.relationState(1, 0) == DiplomacyRelation.STATE_OVERLORD, "reverse overlord state");
        check(first.influence(1) > first.influence(0), "overlord influence bonus");
        check(first.scheduledCapacity() == 8 && first.scheduledSize() == 0, "bounded queue retained");

        // A projection/persistence subsystem may mutate the canonical store between ticks. The
        // revision fence must rebuild the cache without a second source of truth.
        firstState.put(2, 3, FactionAllegiance.ALLIED.code(), (short) 600);
        check(first.relationState(2, 3) == DiplomacyRelation.STATE_ALLY, "external revision sync");

        PackedFactionState secondState = new PackedFactionState(16);
        PackedDiplomacyEngine second = new PackedDiplomacyEngine(secondState, 8, 8);
        second.apply(DiplomacyCommand.DECLARE_WAR, 0, 1, 0);
        second.schedule(20, DiplomacyCommand.SET_REPUTATION, 0, 1, -100);
        second.schedule(20, DiplomacyCommand.MAKE_PEACE, 0, 1, 0);
        second.schedule(30, DiplomacyCommand.FORM_ALLIANCE, 0, 1, 0);
        second.schedule(30, DiplomacyCommand.BECOME_VASSAL, 0, 1, 0);
        second.processDue(19, 8);
        second.processDue(20, 1);
        second.processDue(20, 8);
        second.processDue(30, 8);
        secondState.put(2, 3, FactionAllegiance.ALLIED.code(), (short) 600);
        check(first.stateHash() == second.stateHash(), "deterministic replay hash");

        check(first.processDue(29, 1) == PackedDiplomacyEngine.TIME_REVERSED, "time reversal rejected");

        PackedDiplomacyEngine bounded = new PackedDiplomacyEngine(new PackedFactionState(), 4, 2);
        check(bounded.schedule(50, DiplomacyCommand.SET_REPUTATION, 0, 1, 50)
                        == PackedDiplomacyEngine.APPLIED,
                "late command queued");
        check(bounded.schedule(10, DiplomacyCommand.SET_REPUTATION, 0, 1, 10)
                        == PackedDiplomacyEngine.APPLIED,
                "early command queued out of order");
        check(bounded.schedule(20, DiplomacyCommand.SET_REPUTATION, 0, 1, 20)
                        == PackedDiplomacyEngine.QUEUE_FULL,
                "bounded queue refuses growth");
        check(bounded.processDue(10, 2) == 1 && bounded.reputation(0, 1) == 10, "min-heap due order");
        bounded.apply(DiplomacyCommand.ADJUST_REPUTATION, 0, 1, Integer.MAX_VALUE);
        check(bounded.reputation(0, 1) == DiplomacyRelation.MAX_REPUTATION, "positive saturation");
        bounded.apply(DiplomacyCommand.ADJUST_REPUTATION, 0, 1, Integer.MIN_VALUE);
        check(bounded.reputation(0, 1) == DiplomacyRelation.MIN_REPUTATION, "negative saturation");
        System.out.println("PackedDiplomacyEngineSelfTest: PASS");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
