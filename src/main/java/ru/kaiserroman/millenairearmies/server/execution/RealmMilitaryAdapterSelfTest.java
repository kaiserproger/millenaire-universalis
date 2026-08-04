package ru.kaiserroman.millenairearmies.server.execution;

import ru.kaiserroman.millenaire.realm.BattleOutcome;
import ru.kaiserroman.millenaire.realm.RealmMilitaryPolicy;
import ru.kaiserroman.millenaire.realm.WarGoal;
import ru.kaiserroman.millenairearmies.server.integration.ArmyRealmIdentityResolver;
import ru.kaiserroman.millenairearmies.server.integration.RealmMilitaryAdapter;

/** Deterministic Realm policy, physical loss aggregation and event-gap fail-closed checks. */
public final class RealmMilitaryAdapterSelfTest {
    private RealmMilitaryAdapterSelfTest() {}

    public static void main(String[] args) {
        realmPolicyFallsBackOnlyForUnresolvedArmies();
        completeSiegeReportsOneOutcome();
        overwrittenEventsCancelIncompleteOutcome();
        System.out.println("RealmMilitaryAdapterSelfTest: OK");
    }

    private static void realmPolicyFallsBackOnlyForUnresolvedArmies() {
        PhysicalBattleEventLog events = new PhysicalBattleEventLog(4);
        RecordingPolicy policy = new RecordingPolicy();
        RealmMilitaryAdapter adapter = new RealmMilitaryAdapter(
                policy,
                new Identities(),
                events,
                (sourceArmy, targetArmy, sourceFaction, targetFaction) ->
                        sourceFaction == 7 && targetFaction == 9);

        check(adapter.hostile(1, 2, 7, 9), "resolved Realm war overrides faction fallback");
        check(!adapter.hostile(1, 1, 7, 9),
                "same resolved Realm never falls through to faction hostility");
        check(adapter.hostile(3, 4, 7, 9),
                "unresolved armies preserve Armies faction hostility");
        check(!adapter.hostile(3, 4, 7, 8),
                "unresolved fallback still honors faction policy");
    }

    private static void completeSiegeReportsOneOutcome() {
        PhysicalBattleEventLog events = new PhysicalBattleEventLog(16);
        RecordingPolicy policy = new RecordingPolicy();
        RealmMilitaryAdapter adapter = new RealmMilitaryAdapter(policy, new Identities(), events);
        check(adapter.hostile(1, 2, 7, 8), "declared Realm war authorizes physical hostility");
        check(!adapter.hostile(1, 1, 7, 7), "same Realm never becomes hostile");

        events.append(PhysicalBattleEventLog.SIEGE_STARTED,
                10L, 1, 0, 0, 0, 7, -1, 0, 100L, 45);
        events.append(PhysicalBattleEventLog.UNIT_DEFEATED,
                11L, 2, 1, 22, 11, 8, 7, 0, 100L, 0);
        events.append(PhysicalBattleEventLog.UNIT_DEFEATED,
                12L, 1, 2, 11, 21, 7, 8, 0, 100L, 0);
        events.append(PhysicalBattleEventLog.UNIT_DEFEATED,
                13L, 1, 2, 12, 22, 7, 8, 0, 100L, 0);
        events.append(PhysicalBattleEventLog.SIEGE_SECURED,
                120L, 1, 0, 0, 0, 7, -1, 0, 100L, 85);

        check(adapter.tick(16) == 5, "all physical rows consumed within budget");
        check(policy.outcomes == 1, "exactly one completed outcome reported");
        check(policy.last != null
                        && policy.last.attackerRealmId() == 10L
                        && policy.last.defenderRealmId() == 20L
                        && policy.last.attackerVictory()
                        && policy.last.attackerLosses() == 1
                        && policy.last.defenderLosses() == 2
                        && policy.last.capitalCaptured()
                        && policy.last.settlementOccupied(),
                "Realm receives aggregated physical siege facts");
        check(adapter.activeSiegeCount() == 0, "completed aggregation released");
    }

    private static void overwrittenEventsCancelIncompleteOutcome() {
        PhysicalBattleEventLog events = new PhysicalBattleEventLog(3);
        RecordingPolicy policy = new RecordingPolicy();
        RealmMilitaryAdapter adapter = new RealmMilitaryAdapter(policy, new Identities(), events);
        events.append(PhysicalBattleEventLog.SIEGE_STARTED,
                1L, 1, 0, 0, 0, 7, -1, 0, 100L, 20);
        check(adapter.tick(1) == 1 && adapter.activeSiegeCount() == 1,
                "incomplete siege aggregation started");

        events.append(PhysicalBattleEventLog.CONTACT,
                2L, 1, 2, 11, 21, 7, 8, 0, 100L, 0);
        events.append(PhysicalBattleEventLog.MELEE_HIT,
                3L, 1, 2, 11, 21, 7, 8, 0, 100L, 100);
        events.append(PhysicalBattleEventLog.CONTACT,
                4L, 1, 2, 12, 22, 7, 8, 0, 100L, 0);
        events.append(PhysicalBattleEventLog.MELEE_HIT,
                5L, 1, 2, 12, 22, 7, 8, 0, 100L, 100);
        check(adapter.tick(8) == 3, "retained tail consumed after overwrite");
        check(adapter.droppedEventCount() == 1L && adapter.activeSiegeCount() == 0,
                "event gap invalidates incomplete loss aggregation");

        events.append(PhysicalBattleEventLog.SIEGE_SECURED,
                120L, 1, 0, 0, 0, 7, -1, 0, 100L, 85);
        adapter.tick(4);
        check(policy.outcomes == 0, "secured row without complete start never fabricates outcome");
    }

    private static final class Identities implements ArmyRealmIdentityResolver {
        @Override
        public long realmForArmy(int armyHandle) {
            return armyHandle == 1 ? 10L : armyHandle == 2 ? 20L : 0L;
        }

        @Override
        public long realmAtObjective(int dimensionId, long packedPosition) {
            return dimensionId == 0 && packedPosition == 100L ? 20L : 0L;
        }

        @Override
        public long settlementAtObjective(int dimensionId, long packedPosition) {
            return dimensionId == 0 && packedPosition == 100L ? 200L : 0L;
        }

        @Override
        public boolean isCapital(long realmId, long settlementId) {
            return realmId == 20L && settlementId == 200L;
        }
    }

    private static final class RecordingPolicy implements RealmMilitaryPolicy {
        private int outcomes;
        private BattleOutcome last;

        @Override
        public boolean isAtWar(long sourceRealmId, long targetRealmId) {
            return sourceRealmId == 10L && targetRealmId == 20L;
        }

        @Override
        public boolean mayCommandSettlement(long actorId, long settlementId) {
            return false;
        }

        @Override
        public WarGoal warGoal(long sourceRealmId, long targetRealmId) {
            return WarGoal.BORDER_CLAIM;
        }

        @Override
        public void recordBattleOutcome(BattleOutcome outcome) {
            outcomes++;
            last = outcome;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
