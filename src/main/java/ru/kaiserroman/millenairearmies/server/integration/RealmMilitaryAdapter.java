package ru.kaiserroman.millenairearmies.server.integration;

import java.util.Arrays;
import java.util.Objects;
import ru.kaiserroman.millenaire.realm.BattleOutcome;
import ru.kaiserroman.millenaire.realm.RealmMilitaryPolicy;
import ru.kaiserroman.millenairearmies.server.execution.ArmyHostilityPolicy;
import ru.kaiserroman.millenairearmies.server.execution.PhysicalBattleEventLog;

/**
 * Consumes neutral physical Armies events and reports only complete outcomes to Realm.
 *
 * <p>Realm supplies stable identity mapping and the read-only war policy. The adapter never mutates
 * army ECS state and drops in-progress aggregation if the bounded event cursor reports overwritten
 * rows, preferring no outcome over a fabricated loss count.</p>
 */
public final class RealmMilitaryAdapter implements ArmyHostilityPolicy {
    private static final int MAX_ACTIVE_SIEGES = 128;

    private final RealmMilitaryPolicy policy;
    private final ArmyRealmIdentityResolver identities;
    private final ArmyHostilityPolicy fallbackHostility;
    private final PhysicalBattleEventLog.Cursor events;
    private final int[] siegeArmies = new int[MAX_ACTIVE_SIEGES];
    private final int[] dimensions = new int[MAX_ACTIVE_SIEGES];
    private final long[] positions = new long[MAX_ACTIVE_SIEGES];
    private final long[] attackerRealms = new long[MAX_ACTIVE_SIEGES];
    private final long[] defenderRealms = new long[MAX_ACTIVE_SIEGES];
    private final long[] settlements = new long[MAX_ACTIVE_SIEGES];
    private final int[] attackerLosses = new int[MAX_ACTIVE_SIEGES];
    private final int[] defenderLosses = new int[MAX_ACTIVE_SIEGES];

    private int siegeSize;
    private long observedDropped;

    public RealmMilitaryAdapter(
            RealmMilitaryPolicy policy,
            ArmyRealmIdentityResolver identities,
            PhysicalBattleEventLog battleEvents) {
        this(policy, identities, battleEvents,
                (sourceArmy, targetArmy, sourceFaction, targetFaction) -> false);
    }

    public RealmMilitaryAdapter(
            RealmMilitaryPolicy policy,
            ArmyRealmIdentityResolver identities,
            PhysicalBattleEventLog battleEvents,
            ArmyHostilityPolicy fallbackHostility) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.fallbackHostility = Objects.requireNonNull(fallbackHostility, "fallbackHostility");
        PhysicalBattleEventLog journal = Objects.requireNonNull(battleEvents, "battleEvents");
        events = journal.cursorAfter(journal.latestSequence());
    }

    @Override
    public boolean hostile(int sourceArmy, int targetArmy, int sourceFaction, int targetFaction) {
        long sourceRealm = identities.realmForArmy(sourceArmy);
        long targetRealm = identities.realmForArmy(targetArmy);
        if (sourceRealm > 0L && targetRealm > 0L) {
            return sourceRealm != targetRealm && policy.isAtWar(sourceRealm, targetRealm);
        }
        return fallbackHostility.hostile(sourceArmy, targetArmy, sourceFaction, targetFaction);
    }

    /** Returns the number of physical events consumed in this bounded tick. */
    public int tick(int maximumEvents) {
        if (maximumEvents < 1) {
            throw new IllegalArgumentException("Realm military event budget must be positive");
        }
        int consumed = 0;
        while (consumed < maximumEvents && events.advance()) {
            consumed++;
            if (events.droppedCount() != observedDropped) {
                observedDropped = events.droppedCount();
                clearSieges();
            }
            switch (events.kind()) {
                case PhysicalBattleEventLog.SIEGE_STARTED -> startSiege();
                case PhysicalBattleEventLog.UNIT_DEFEATED -> recordLoss();
                case PhysicalBattleEventLog.SIEGE_SECURED -> finishSiege();
                default -> {
                    // Contact, hit and progress rows remain available to Simulation but do not
                    // independently constitute a Realm battle outcome.
                }
            }
        }
        return consumed;
    }

    public int activeSiegeCount() {
        return siegeSize;
    }

    public long droppedEventCount() {
        return observedDropped;
    }

    public void clear() {
        clearSieges();
        observedDropped = events.droppedCount();
    }

    private void startSiege() {
        long attackerRealm = identities.realmForArmy(events.sourceArmy());
        long defenderRealm = identities.realmAtObjective(events.dimensionId(), events.packedPosition());
        long settlement = identities.settlementAtObjective(events.dimensionId(), events.packedPosition());
        if (!validPair(attackerRealm, defenderRealm)
                || settlement <= 0L
                || !policy.isAtWar(attackerRealm, defenderRealm)) {
            return;
        }
        int row = findSiege(events.sourceArmy(), events.dimensionId(), events.packedPosition());
        if (row < 0) {
            if (siegeSize == MAX_ACTIVE_SIEGES) {
                // Fail closed: overwrite no active result whose losses may still be needed.
                return;
            }
            row = siegeSize++;
        }
        siegeArmies[row] = events.sourceArmy();
        dimensions[row] = events.dimensionId();
        positions[row] = events.packedPosition();
        attackerRealms[row] = attackerRealm;
        defenderRealms[row] = defenderRealm;
        settlements[row] = settlement;
        attackerLosses[row] = 0;
        defenderLosses[row] = 0;
    }

    private void recordLoss() {
        int sourceArmy = events.sourceArmy();
        int targetArmy = events.targetArmy();
        for (int row = 0; row < siegeSize; row++) {
            int siegeArmy = siegeArmies[row];
            if (targetArmy == siegeArmy) {
                attackerLosses[row] = saturatedIncrement(attackerLosses[row]);
            } else if (sourceArmy == siegeArmy) {
                long defeatedRealm = identities.realmForArmy(targetArmy);
                if (defeatedRealm == defenderRealms[row]) {
                    defenderLosses[row] = saturatedIncrement(defenderLosses[row]);
                }
            }
        }
    }

    private void finishSiege() {
        int row = findSiege(events.sourceArmy(), events.dimensionId(), events.packedPosition());
        if (row < 0) {
            return;
        }
        long attackerRealm = attackerRealms[row];
        long defenderRealm = defenderRealms[row];
        long settlement = settlements[row];
        if (validPair(attackerRealm, defenderRealm)
                && policy.isAtWar(attackerRealm, defenderRealm)) {
            policy.recordBattleOutcome(new BattleOutcome(
                    attackerRealm,
                    defenderRealm,
                    true,
                    attackerLosses[row],
                    defenderLosses[row],
                    settlement,
                    identities.isCapital(defenderRealm, settlement),
                    true));
        }
        removeSiege(row);
    }

    private int findSiege(int army, int dimension, long position) {
        for (int row = 0; row < siegeSize; row++) {
            if (siegeArmies[row] == army
                    && dimensions[row] == dimension
                    && positions[row] == position) {
                return row;
            }
        }
        return -1;
    }

    private void removeSiege(int row) {
        int last = --siegeSize;
        if (row != last) {
            siegeArmies[row] = siegeArmies[last];
            dimensions[row] = dimensions[last];
            positions[row] = positions[last];
            attackerRealms[row] = attackerRealms[last];
            defenderRealms[row] = defenderRealms[last];
            settlements[row] = settlements[last];
            attackerLosses[row] = attackerLosses[last];
            defenderLosses[row] = defenderLosses[last];
        }
        siegeArmies[last] = 0;
        dimensions[last] = 0;
        positions[last] = 0L;
        attackerRealms[last] = 0L;
        defenderRealms[last] = 0L;
        settlements[last] = 0L;
        attackerLosses[last] = 0;
        defenderLosses[last] = 0;
    }

    private void clearSieges() {
        Arrays.fill(siegeArmies, 0, siegeSize, 0);
        Arrays.fill(dimensions, 0, siegeSize, 0);
        Arrays.fill(positions, 0, siegeSize, 0L);
        Arrays.fill(attackerRealms, 0, siegeSize, 0L);
        Arrays.fill(defenderRealms, 0, siegeSize, 0L);
        Arrays.fill(settlements, 0, siegeSize, 0L);
        Arrays.fill(attackerLosses, 0, siegeSize, 0);
        Arrays.fill(defenderLosses, 0, siegeSize, 0);
        siegeSize = 0;
    }

    private static boolean validPair(long sourceRealm, long targetRealm) {
        return sourceRealm > 0L && targetRealm > 0L && sourceRealm != targetRealm;
    }

    private static int saturatedIncrement(int value) {
        return value == Integer.MAX_VALUE ? value : value + 1;
    }
}
