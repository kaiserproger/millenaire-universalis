package ru.kaiserroman.millenaire.realm;

import java.util.Arrays;

/**
 * Sparse, persistable bilateral Realm relations. Pair identity is unordered; trust, grievances,
 * fear, claims, war goals, exhaustion and war score remain directional.
 */
public final class RealmDiplomacyLedger {
    private final int maximumRelations;
    private int size;
    private long revision;

    private long[] firstRealms;
    private long[] secondRealms;
    private byte[] statuses;
    private byte[] firstGoals;
    private byte[] secondGoals;
    private int[] firstTrust;
    private int[] secondTrust;
    private int[] firstGrievances;
    private int[] secondGrievances;
    private int[] firstFear;
    private int[] secondFear;
    private int[] firstClaims;
    private int[] secondClaims;
    private int[] firstExhaustion;
    private int[] secondExhaustion;
    private int[] firstWarScores;
    private int[] secondWarScores;
    private int[] tradeInterdependence;
    private int[] borderFriction;
    private int[] ideologicalDistance;
    private int[] commonThreat;
    private long[] truceUntilCycles;
    private long[] lastEvaluationCycles;

    public RealmDiplomacyLedger(int maximumRelations) {
        if (maximumRelations <= 0) {
            throw new IllegalArgumentException("maximumRelations must be positive");
        }
        this.maximumRelations = maximumRelations;
        int capacity = Math.min(16, maximumRelations);
        firstRealms = new long[capacity];
        secondRealms = new long[capacity];
        statuses = new byte[capacity];
        firstGoals = new byte[capacity];
        secondGoals = new byte[capacity];
        firstTrust = new int[capacity];
        secondTrust = new int[capacity];
        firstGrievances = new int[capacity];
        secondGrievances = new int[capacity];
        firstFear = new int[capacity];
        secondFear = new int[capacity];
        firstClaims = new int[capacity];
        secondClaims = new int[capacity];
        firstExhaustion = new int[capacity];
        secondExhaustion = new int[capacity];
        firstWarScores = new int[capacity];
        secondWarScores = new int[capacity];
        tradeInterdependence = new int[capacity];
        borderFriction = new int[capacity];
        ideologicalDistance = new int[capacity];
        commonThreat = new int[capacity];
        truceUntilCycles = new long[capacity];
        lastEvaluationCycles = new long[capacity];
    }

    public int ensureRelation(long sourceRealm, long targetRealm, long cycle) {
        requirePair(sourceRealm, targetRealm);
        if (cycle < 0L) throw new IllegalArgumentException("Negative diplomacy cycle");
        int row = find(sourceRealm, targetRealm);
        if (row >= 0) return row;
        if (size == maximumRelations) return -1;
        ensureCapacity(size + 1);
        row = size++;
        long first = Math.min(sourceRealm, targetRealm);
        long second = Math.max(sourceRealm, targetRealm);
        firstRealms[row] = first;
        secondRealms[row] = second;
        statuses[row] = (byte) DiplomaticStatus.PEACE.ordinal();
        firstGoals[row] = (byte) WarGoal.NONE.ordinal();
        secondGoals[row] = (byte) WarGoal.NONE.ordinal();
        firstTrust[row] = 500;
        secondTrust[row] = 500;
        ideologicalDistance[row] = 500;
        lastEvaluationCycles[row] = cycle;
        changed();
        return row;
    }

    public boolean updateDrivers(
            long sourceRealm,
            long targetRealm,
            int trust,
            int grievances,
            int fear,
            int claimStrength,
            int trade,
            int border,
            int ideology,
            int sharedThreat,
            long cycle) {
        requireIndex(trust, "trust");
        requireIndex(grievances, "grievances");
        requireIndex(fear, "fear");
        requireIndex(claimStrength, "claimStrength");
        requireIndex(trade, "trade");
        requireIndex(border, "border");
        requireIndex(ideology, "ideology");
        requireIndex(sharedThreat, "commonThreat");
        int row = ensureRelation(sourceRealm, targetRealm, cycle);
        if (row < 0) return false;
        boolean first = sourceRealm == firstRealms[row];
        boolean mutated;
        if (first) {
            mutated = firstTrust[row] != trust
                    || firstGrievances[row] != grievances
                    || firstFear[row] != fear
                    || firstClaims[row] != claimStrength;
            firstTrust[row] = trust;
            firstGrievances[row] = grievances;
            firstFear[row] = fear;
            firstClaims[row] = claimStrength;
        } else {
            mutated = secondTrust[row] != trust
                    || secondGrievances[row] != grievances
                    || secondFear[row] != fear
                    || secondClaims[row] != claimStrength;
            secondTrust[row] = trust;
            secondGrievances[row] = grievances;
            secondFear[row] = fear;
            secondClaims[row] = claimStrength;
        }
        mutated |= tradeInterdependence[row] != trade
                || borderFriction[row] != border
                || ideologicalDistance[row] != ideology
                || commonThreat[row] != sharedThreat;
        tradeInterdependence[row] = trade;
        borderFriction[row] = border;
        ideologicalDistance[row] = ideology;
        commonThreat[row] = sharedThreat;
        if (mutated) changed();
        return true;
    }

    public DiplomacyInputs inputs(
            long sourceRealm,
            long targetRealm,
            int powerAdvantage,
            long currentCycle) {
        requireIndex(powerAdvantage, "powerAdvantage");
        int row = find(sourceRealm, targetRealm);
        if (row < 0) {
            throw new IllegalArgumentException("Unknown Realm relation");
        }
        boolean first = sourceRealm == firstRealms[row];
        return new DiplomacyInputs(
                first ? firstTrust[row] : secondTrust[row],
                first ? firstGrievances[row] : secondGrievances[row],
                first ? firstFear[row] : secondFear[row],
                tradeInterdependence[row],
                borderFriction[row],
                first ? firstClaims[row] : secondClaims[row],
                powerAdvantage,
                ideologicalDistance[row],
                first ? firstExhaustion[row] : secondExhaustion[row],
                commonThreat[row],
                truceCyclesRemaining(row, currentCycle));
    }

    public boolean applyDecision(
            long sourceRealm,
            long targetRealm,
            DiplomaticDecision decision,
            long cycle,
            int truceDurationCycles) {
        if (decision == null || cycle < 0L || truceDurationCycles <= 0) {
            throw new IllegalArgumentException("Invalid diplomatic decision commit");
        }
        int row = ensureRelation(sourceRealm, targetRealm, cycle);
        if (row < 0) return false;
        DiplomaticStatus oldStatus = statusAt(row, cycle);
        WarGoal oldSourceGoal = goalAt(row, sourceRealm);
        WarGoal oldTargetGoal = goalAt(row, targetRealm);
        long oldTruce = truceUntilCycles[row];
        statuses[row] = (byte) decision.status().ordinal();
        if (decision.status() == DiplomaticStatus.WAR) {
            setGoal(row, sourceRealm, decision.warGoal());
            setGoal(row, targetRealm, WarGoal.DEFEND);
            truceUntilCycles[row] = 0L;
        } else {
            setGoal(row, sourceRealm, WarGoal.NONE);
            setGoal(row, targetRealm, WarGoal.NONE);
            if (decision.status() == DiplomaticStatus.TRUCE) {
                truceUntilCycles[row] = saturatedAdd(cycle, truceDurationCycles);
            } else {
                truceUntilCycles[row] = 0L;
            }
            if (oldStatus == DiplomaticStatus.WAR) {
                firstWarScores[row] = 0;
                secondWarScores[row] = 0;
            }
        }
        lastEvaluationCycles[row] = cycle;
        boolean mutated = oldStatus != decision.status()
                || oldSourceGoal != goalAt(row, sourceRealm)
                || oldTargetGoal != goalAt(row, targetRealm)
                || oldTruce != truceUntilCycles[row];
        if (mutated) changed();
        return true;
    }

    public boolean recordBattleOutcome(BattleOutcome outcome, RealmDiplomacyEngine engine) {
        if (outcome == null || engine == null) throw new NullPointerException("battle outcome");
        int row = find(outcome.attackerRealmId(), outcome.defenderRealmId());
        if (row < 0 || statuses[row] != (byte) DiplomaticStatus.WAR.ordinal()) return false;
        WarImpact impact = engine.battleImpact(outcome);
        boolean attackerFirst = outcome.attackerRealmId() == firstRealms[row];
        if (attackerFirst) {
            firstWarScores[row] = saturatedScore(firstWarScores[row], impact.attackerWarScoreDelta());
            secondWarScores[row] = saturatedScore(secondWarScores[row], impact.defenderWarScoreDelta());
            firstExhaustion[row] = clamp(firstExhaustion[row] + impact.attackerExhaustionDelta());
            secondExhaustion[row] = clamp(secondExhaustion[row] + impact.defenderExhaustionDelta());
            secondGrievances[row] = clamp(secondGrievances[row] + impact.grievanceDelta());
        } else {
            secondWarScores[row] = saturatedScore(secondWarScores[row], impact.attackerWarScoreDelta());
            firstWarScores[row] = saturatedScore(firstWarScores[row], impact.defenderWarScoreDelta());
            secondExhaustion[row] = clamp(secondExhaustion[row] + impact.attackerExhaustionDelta());
            firstExhaustion[row] = clamp(firstExhaustion[row] + impact.defenderExhaustionDelta());
            firstGrievances[row] = clamp(firstGrievances[row] + impact.grievanceDelta());
        }
        changed();
        return true;
    }

    /** Coarse peacetime/war recovery used once per diplomatic evaluation interval. */
    public boolean markEvaluated(long firstRealm, long secondRealm, long cycle) {
        if (cycle < 0L) throw new IllegalArgumentException("Negative diplomacy cycle");
        int row = find(firstRealm, secondRealm);
        if (row < 0) return false;
        if (lastEvaluationCycles[row] != cycle) {
            lastEvaluationCycles[row] = cycle;
            changed();
        }
        return true;
    }

    public boolean recover(long firstRealm, long secondRealm, int cycles) {
        if (cycles <= 0) throw new IllegalArgumentException("cycles must be positive");
        int row = find(firstRealm, secondRealm);
        if (row < 0) return false;
        int exhaustionDecay = Math.min(200, cycles * 8);
        int grievanceDecay = Math.min(100, cycles * 2);
        int oldFirstExhaustion = firstExhaustion[row];
        int oldSecondExhaustion = secondExhaustion[row];
        int oldFirstGrievance = firstGrievances[row];
        int oldSecondGrievance = secondGrievances[row];
        firstExhaustion[row] = Math.max(0, firstExhaustion[row] - exhaustionDecay);
        secondExhaustion[row] = Math.max(0, secondExhaustion[row] - exhaustionDecay);
        if (statuses[row] != (byte) DiplomaticStatus.WAR.ordinal()) {
            firstGrievances[row] = Math.max(0, firstGrievances[row] - grievanceDecay);
            secondGrievances[row] = Math.max(0, secondGrievances[row] - grievanceDecay);
        }
        boolean mutated = oldFirstExhaustion != firstExhaustion[row]
                || oldSecondExhaustion != secondExhaustion[row]
                || oldFirstGrievance != firstGrievances[row]
                || oldSecondGrievance != secondGrievances[row];
        if (mutated) changed();
        return mutated;
    }

    public DiplomaticStatus status(long firstRealm, long secondRealm, long cycle) {
        int row = find(firstRealm, secondRealm);
        return row < 0 ? DiplomaticStatus.PEACE : statusAt(row, cycle);
    }

    public boolean isAtWar(long firstRealm, long secondRealm) {
        int row = find(firstRealm, secondRealm);
        return row >= 0 && statuses[row] == (byte) DiplomaticStatus.WAR.ordinal();
    }

    public WarGoal warGoal(long sourceRealm, long targetRealm) {
        int row = find(sourceRealm, targetRealm);
        return row < 0 ? WarGoal.NONE : goalAt(row, sourceRealm);
    }

    public int warScore(long sourceRealm, long targetRealm) {
        int row = find(sourceRealm, targetRealm);
        if (row < 0) return 0;
        return sourceRealm == firstRealms[row] ? firstWarScores[row] : secondWarScores[row];
    }

    public int exhaustion(long sourceRealm, long targetRealm) {
        int row = find(sourceRealm, targetRealm);
        if (row < 0) return 0;
        return sourceRealm == firstRealms[row] ? firstExhaustion[row] : secondExhaustion[row];
    }

    public int grievances(long sourceRealm, long targetRealm) {
        int row = find(sourceRealm, targetRealm);
        if (row < 0) return 0;
        return sourceRealm == firstRealms[row] ? firstGrievances[row] : secondGrievances[row];
    }

    public long lastEvaluationCycle(long firstRealm, long secondRealm) {
        int row = find(firstRealm, secondRealm);
        return row < 0 ? -1L : lastEvaluationCycles[row];
    }

    public boolean removeRealm(long realmId) {
        boolean mutated = false;
        for (int row = size - 1; row >= 0; row--) {
            if (firstRealms[row] == realmId || secondRealms[row] == realmId) {
                removeAt(row);
                mutated = true;
            }
        }
        if (mutated) changed();
        return mutated;
    }

    public int size() { return size; }
    public long revision() { return revision; }

    public void visit(Visitor visitor) {
        if (visitor == null) throw new NullPointerException("visitor");
        for (int row = 0; row < size; row++) {
            visitor.accept(
                    firstRealms[row], secondRealms[row],
                    DiplomaticStatus.values()[Byte.toUnsignedInt(statuses[row])],
                    WarGoal.values()[Byte.toUnsignedInt(firstGoals[row])],
                    WarGoal.values()[Byte.toUnsignedInt(secondGoals[row])],
                    firstTrust[row], secondTrust[row],
                    firstGrievances[row], secondGrievances[row],
                    firstFear[row], secondFear[row],
                    firstClaims[row], secondClaims[row],
                    firstExhaustion[row], secondExhaustion[row],
                    firstWarScores[row], secondWarScores[row],
                    tradeInterdependence[row], borderFriction[row],
                    ideologicalDistance[row], commonThreat[row],
                    truceUntilCycles[row], lastEvaluationCycles[row]);
        }
    }

    public void restore(
            long firstRealm,
            long secondRealm,
            DiplomaticStatus status,
            WarGoal firstGoal,
            WarGoal secondGoal,
            int firstTrustValue,
            int secondTrustValue,
            int firstGrievanceValue,
            int secondGrievanceValue,
            int firstFearValue,
            int secondFearValue,
            int firstClaimValue,
            int secondClaimValue,
            int firstExhaustionValue,
            int secondExhaustionValue,
            int firstWarScore,
            int secondWarScore,
            int trade,
            int border,
            int ideology,
            int sharedThreat,
            long truceUntil,
            long lastEvaluation) {
        requirePair(firstRealm, secondRealm);
        if (firstRealm >= secondRealm || status == null || firstGoal == null || secondGoal == null
                || truceUntil < 0L || lastEvaluation < 0L || find(firstRealm, secondRealm) >= 0
                || size == maximumRelations) {
            throw new IllegalArgumentException("Invalid restored Realm relation");
        }
        requireIndex(firstTrustValue, "firstTrust");
        requireIndex(secondTrustValue, "secondTrust");
        requireIndex(firstGrievanceValue, "firstGrievance");
        requireIndex(secondGrievanceValue, "secondGrievance");
        requireIndex(firstFearValue, "firstFear");
        requireIndex(secondFearValue, "secondFear");
        requireIndex(firstClaimValue, "firstClaim");
        requireIndex(secondClaimValue, "secondClaim");
        requireIndex(firstExhaustionValue, "firstExhaustion");
        requireIndex(secondExhaustionValue, "secondExhaustion");
        requireIndex(trade, "trade");
        requireIndex(border, "border");
        requireIndex(ideology, "ideology");
        requireIndex(sharedThreat, "commonThreat");
        ensureCapacity(size + 1);
        int row = size++;
        firstRealms[row] = firstRealm;
        secondRealms[row] = secondRealm;
        statuses[row] = (byte) status.ordinal();
        firstGoals[row] = (byte) firstGoal.ordinal();
        secondGoals[row] = (byte) secondGoal.ordinal();
        firstTrust[row] = firstTrustValue;
        secondTrust[row] = secondTrustValue;
        firstGrievances[row] = firstGrievanceValue;
        secondGrievances[row] = secondGrievanceValue;
        firstFear[row] = firstFearValue;
        secondFear[row] = secondFearValue;
        firstClaims[row] = firstClaimValue;
        secondClaims[row] = secondClaimValue;
        firstExhaustion[row] = firstExhaustionValue;
        secondExhaustion[row] = secondExhaustionValue;
        firstWarScores[row] = firstWarScore;
        secondWarScores[row] = secondWarScore;
        tradeInterdependence[row] = trade;
        borderFriction[row] = border;
        ideologicalDistance[row] = ideology;
        commonThreat[row] = sharedThreat;
        truceUntilCycles[row] = truceUntil;
        lastEvaluationCycles[row] = lastEvaluation;
    }

    public void restoreRevision(long value) {
        if (value < 0L) throw new IllegalArgumentException("Negative diplomacy revision");
        revision = value;
    }

    public int estimatedPrimitiveBytes() {
        return firstRealms.length * Long.BYTES
                + secondRealms.length * Long.BYTES
                + statuses.length + firstGoals.length + secondGoals.length
                + (firstTrust.length + secondTrust.length
                        + firstGrievances.length + secondGrievances.length
                        + firstFear.length + secondFear.length
                        + firstClaims.length + secondClaims.length
                        + firstExhaustion.length + secondExhaustion.length
                        + firstWarScores.length + secondWarScores.length
                        + tradeInterdependence.length + borderFriction.length
                        + ideologicalDistance.length + commonThreat.length) * Integer.BYTES
                + truceUntilCycles.length * Long.BYTES
                + lastEvaluationCycles.length * Long.BYTES;
    }

    private DiplomaticStatus statusAt(int row, long cycle) {
        DiplomaticStatus stored = DiplomaticStatus.values()[Byte.toUnsignedInt(statuses[row])];
        if (stored == DiplomaticStatus.TRUCE && cycle >= truceUntilCycles[row]) {
            return DiplomaticStatus.PEACE;
        }
        return stored;
    }

    private WarGoal goalAt(int row, long realmId) {
        return WarGoal.values()[Byte.toUnsignedInt(
                realmId == firstRealms[row] ? firstGoals[row] : secondGoals[row])];
    }

    private void setGoal(int row, long realmId, WarGoal goal) {
        if (realmId == firstRealms[row]) firstGoals[row] = (byte) goal.ordinal();
        else secondGoals[row] = (byte) goal.ordinal();
    }

    private int truceCyclesRemaining(int row, long cycle) {
        if (statuses[row] != (byte) DiplomaticStatus.TRUCE.ordinal()
                || truceUntilCycles[row] <= cycle) return 0;
        long remaining = truceUntilCycles[row] - cycle;
        return remaining >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) remaining;
    }

    private int find(long sourceRealm, long targetRealm) {
        if (sourceRealm <= 0L || targetRealm <= 0L || sourceRealm == targetRealm) return -1;
        long first = Math.min(sourceRealm, targetRealm);
        long second = Math.max(sourceRealm, targetRealm);
        for (int row = 0; row < size; row++) {
            if (firstRealms[row] == first && secondRealms[row] == second) return row;
        }
        return -1;
    }

    private void removeAt(int row) {
        int last = --size;
        if (row != last) copy(last, row);
        firstRealms[last] = 0L;
        secondRealms[last] = 0L;
        statuses[last] = 0;
        firstGoals[last] = 0;
        secondGoals[last] = 0;
        firstTrust[last] = 0;
        secondTrust[last] = 0;
        firstGrievances[last] = 0;
        secondGrievances[last] = 0;
        firstFear[last] = 0;
        secondFear[last] = 0;
        firstClaims[last] = 0;
        secondClaims[last] = 0;
        firstExhaustion[last] = 0;
        secondExhaustion[last] = 0;
        firstWarScores[last] = 0;
        secondWarScores[last] = 0;
        tradeInterdependence[last] = 0;
        borderFriction[last] = 0;
        ideologicalDistance[last] = 0;
        commonThreat[last] = 0;
        truceUntilCycles[last] = 0L;
        lastEvaluationCycles[last] = 0L;
    }

    private void copy(int source, int target) {
        firstRealms[target] = firstRealms[source];
        secondRealms[target] = secondRealms[source];
        statuses[target] = statuses[source];
        firstGoals[target] = firstGoals[source];
        secondGoals[target] = secondGoals[source];
        firstTrust[target] = firstTrust[source];
        secondTrust[target] = secondTrust[source];
        firstGrievances[target] = firstGrievances[source];
        secondGrievances[target] = secondGrievances[source];
        firstFear[target] = firstFear[source];
        secondFear[target] = secondFear[source];
        firstClaims[target] = firstClaims[source];
        secondClaims[target] = secondClaims[source];
        firstExhaustion[target] = firstExhaustion[source];
        secondExhaustion[target] = secondExhaustion[source];
        firstWarScores[target] = firstWarScores[source];
        secondWarScores[target] = secondWarScores[source];
        tradeInterdependence[target] = tradeInterdependence[source];
        borderFriction[target] = borderFriction[source];
        ideologicalDistance[target] = ideologicalDistance[source];
        commonThreat[target] = commonThreat[source];
        truceUntilCycles[target] = truceUntilCycles[source];
        lastEvaluationCycles[target] = lastEvaluationCycles[source];
    }

    private void ensureCapacity(int required) {
        if (required <= firstRealms.length) return;
        int capacity = Math.min(
                maximumRelations,
                Math.max(required, firstRealms.length + Math.max(1, firstRealms.length >>> 1)));
        firstRealms = Arrays.copyOf(firstRealms, capacity);
        secondRealms = Arrays.copyOf(secondRealms, capacity);
        statuses = Arrays.copyOf(statuses, capacity);
        firstGoals = Arrays.copyOf(firstGoals, capacity);
        secondGoals = Arrays.copyOf(secondGoals, capacity);
        firstTrust = Arrays.copyOf(firstTrust, capacity);
        secondTrust = Arrays.copyOf(secondTrust, capacity);
        firstGrievances = Arrays.copyOf(firstGrievances, capacity);
        secondGrievances = Arrays.copyOf(secondGrievances, capacity);
        firstFear = Arrays.copyOf(firstFear, capacity);
        secondFear = Arrays.copyOf(secondFear, capacity);
        firstClaims = Arrays.copyOf(firstClaims, capacity);
        secondClaims = Arrays.copyOf(secondClaims, capacity);
        firstExhaustion = Arrays.copyOf(firstExhaustion, capacity);
        secondExhaustion = Arrays.copyOf(secondExhaustion, capacity);
        firstWarScores = Arrays.copyOf(firstWarScores, capacity);
        secondWarScores = Arrays.copyOf(secondWarScores, capacity);
        tradeInterdependence = Arrays.copyOf(tradeInterdependence, capacity);
        borderFriction = Arrays.copyOf(borderFriction, capacity);
        ideologicalDistance = Arrays.copyOf(ideologicalDistance, capacity);
        commonThreat = Arrays.copyOf(commonThreat, capacity);
        truceUntilCycles = Arrays.copyOf(truceUntilCycles, capacity);
        lastEvaluationCycles = Arrays.copyOf(lastEvaluationCycles, capacity);
    }

    private void changed() {
        if (revision == Long.MAX_VALUE) throw new IllegalStateException("Diplomacy revision exhausted");
        revision++;
    }

    private static void requirePair(long first, long second) {
        if (first <= 0L || second <= 0L || first == second) {
            throw new IllegalArgumentException("Invalid Realm relation pair");
        }
    }

    private static void requireIndex(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " outside 0..1000");
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(1000, value));
    }

    private static int saturatedScore(int value, int delta) {
        long result = (long) value + delta;
        return result > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : result < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) result;
    }

    private static long saturatedAdd(long left, int right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    @FunctionalInterface
    public interface Visitor {
        void accept(
                long firstRealm,
                long secondRealm,
                DiplomaticStatus status,
                WarGoal firstGoal,
                WarGoal secondGoal,
                int firstTrust,
                int secondTrust,
                int firstGrievances,
                int secondGrievances,
                int firstFear,
                int secondFear,
                int firstClaims,
                int secondClaims,
                int firstExhaustion,
                int secondExhaustion,
                int firstWarScore,
                int secondWarScore,
                int tradeInterdependence,
                int borderFriction,
                int ideologicalDistance,
                int commonThreat,
                long truceUntilCycle,
                long lastEvaluationCycle);
    }
}
