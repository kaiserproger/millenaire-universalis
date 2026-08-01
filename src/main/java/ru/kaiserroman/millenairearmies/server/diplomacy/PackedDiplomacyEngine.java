package ru.kaiserroman.millenairearmies.server.diplomacy;

import java.util.Arrays;
import java.util.Objects;
import ru.kaiserroman.millenairearmies.model.FactionAllegiance;
import ru.kaiserroman.millenairearmies.persistence.PackedFactionState;

/**
 * Deterministic strategic diplomacy kernel backed by {@link PackedFactionState}.
 *
 * <p>The persisted relation store remains the sole source of truth. War, alliance and vassalage
 * are compact allegiance states; overlordship is the reverse side of a vassal relation. Influence
 * is a deterministic derived cache, not a second persisted model. Scheduled commands live in a
     * bounded primitive min-heap ordered by {@code (dueTick, sequence)}. Once constructed, relation
     * queries, command application and ticking allocate no objects. The heap is deliberately a
     * runtime scheduler (the persistence codec is outside this layer); lifecycle code must
     * rederive long-lived political events after a restart.</p>
 */
public final class PackedDiplomacyEngine {
    public static final int APPLIED = 1;
    public static final int NO_CHANGE = 0;
    public static final int INVALID_COMMAND = -1;
    public static final int INVALID_FACTION = -2;
    public static final int INVALID_RELATION = -3;
    public static final int QUEUE_FULL = -4;
    public static final int TIME_REVERSED = -5;

    private static final byte HOSTILE = FactionAllegiance.HOSTILE.code();
    private static final byte NEUTRAL = FactionAllegiance.NEUTRAL.code();
    private static final byte FRIENDLY = FactionAllegiance.FRIENDLY.code();
    private static final byte ALLIED = FactionAllegiance.ALLIED.code();
    private static final byte VASSAL = FactionAllegiance.VASSAL.code();

    private final PackedFactionState relations;
    private final PackedFactionState.Cursor relationCursor;
    private final int maxFactions;
    private final int[] influence;
    private long[] pairKeys;
    private byte[] cachedAllegiances;
    private short[] cachedReputations;
    private int pairSize;
    private long observedNextRelationRevision;

    private final long[] dueTicks;
    private final long[] sequences;
    private final int[] sources;
    private final int[] targets;
    private final int[] values;
    private final byte[] commandTypes;
    private int scheduledSize;
    private long nextSequence = 1L;
    private long lastProcessedTick = -1L;
    private long revision;

    public PackedDiplomacyEngine(PackedFactionState relations, int maxFactions, int maxScheduledCommands) {
        this.relations = Objects.requireNonNull(relations, "relations");
        if (maxFactions <= 0 || maxScheduledCommands < 0) {
            throw new IllegalArgumentException("Diplomacy capacities must be non-negative and maxFactions positive");
        }
        this.maxFactions = maxFactions;
        influence = new int[maxFactions];
        dueTicks = new long[maxScheduledCommands];
        sequences = new long[maxScheduledCommands];
        sources = new int[maxScheduledCommands];
        targets = new int[maxScheduledCommands];
        values = new int[maxScheduledCommands];
        commandTypes = new byte[maxScheduledCommands];
        relationCursor = relations.newCursor();
        int initialPairs = saturatedAdd(relations.size(), saturatedMultiply(maxScheduledCommands, 2));
        ensurePairCapacity(Math.max(8, initialPairs));
        rebuildRelationCache();
    }

    public int maxFactions() {
        return maxFactions;
    }

    public int scheduledSize() {
        return scheduledSize;
    }

    public int scheduledCapacity() {
        return dueTicks.length;
    }

    public long revision() {
        return revision;
    }

    public short reputation(int sourceFactionId, int targetFactionId) {
        synchronizeExternalChanges();
        return cachedReputation(sourceFactionId, targetFactionId);
    }

    public byte allegianceCode(int sourceFactionId, int targetFactionId) {
        synchronizeExternalChanges();
        return cachedAllegiance(sourceFactionId, targetFactionId);
    }

    public int relationFlags(int sourceFactionId, int targetFactionId) {
        if (!validPair(sourceFactionId, targetFactionId)) {
            return 0;
        }
        byte direct = allegianceCode(sourceFactionId, targetFactionId);
        byte reverse = allegianceCode(targetFactionId, sourceFactionId);
        int flags = 0;
        if (direct == HOSTILE || reverse == HOSTILE) {
            flags |= DiplomacyRelation.WAR;
        }
        if (direct == ALLIED || reverse == ALLIED) {
            flags |= DiplomacyRelation.ALLY;
        }
        if (direct == VASSAL) {
            flags |= DiplomacyRelation.VASSAL_OF_TARGET;
        }
        if (reverse == VASSAL) {
            flags |= DiplomacyRelation.OVERLORD_OF_TARGET;
        }
        if (direct == FRIENDLY) {
            flags |= DiplomacyRelation.FRIENDLY;
        }
        return flags;
    }

    public byte relationState(int sourceFactionId, int targetFactionId) {
        int flags = relationFlags(sourceFactionId, targetFactionId);
        if ((flags & DiplomacyRelation.WAR) != 0) {
            return DiplomacyRelation.STATE_WAR;
        }
        if ((flags & DiplomacyRelation.VASSAL_OF_TARGET) != 0) {
            return DiplomacyRelation.STATE_VASSAL;
        }
        if ((flags & DiplomacyRelation.OVERLORD_OF_TARGET) != 0) {
            return DiplomacyRelation.STATE_OVERLORD;
        }
        if ((flags & DiplomacyRelation.ALLY) != 0) {
            return DiplomacyRelation.STATE_ALLY;
        }
        if ((flags & DiplomacyRelation.FRIENDLY) != 0) {
            return DiplomacyRelation.STATE_FRIENDLY;
        }
        return DiplomacyRelation.STATE_NEUTRAL;
    }

    /** Derived diplomatic weight in fixed integer points. */
    public int influence(int factionId) {
        synchronizeExternalChanges();
        return validFaction(factionId) ? influence[factionId] : 0;
    }

    /** Applies one trusted server command immediately. */
    public int apply(byte commandType, int sourceFactionId, int targetFactionId, int value) {
        synchronizeExternalChanges();
        if (!DiplomacyCommand.isValid(commandType)) {
            return INVALID_COMMAND;
        }
        if (!validFaction(sourceFactionId) || !validFaction(targetFactionId)) {
            return INVALID_FACTION;
        }
        if (sourceFactionId == targetFactionId) {
            return INVALID_RELATION;
        }

        boolean changed = switch (commandType) {
            case DiplomacyCommand.SET_REPUTATION -> setDirected(
                    sourceFactionId,
                    targetFactionId,
                    allegianceCode(sourceFactionId, targetFactionId),
                    clampReputation(value));
            case DiplomacyCommand.ADJUST_REPUTATION -> setDirected(
                    sourceFactionId,
                    targetFactionId,
                    allegianceCode(sourceFactionId, targetFactionId),
                    clampReputation((long) reputation(sourceFactionId, targetFactionId) + value));
            case DiplomacyCommand.DECLARE_WAR -> setSymmetric(
                    sourceFactionId, targetFactionId, HOSTILE, atMostReputation(sourceFactionId, targetFactionId, -750));
            case DiplomacyCommand.MAKE_PEACE -> setSymmetric(
                    sourceFactionId, targetFactionId, NEUTRAL, atLeastReputation(sourceFactionId, targetFactionId, -250));
            case DiplomacyCommand.FORM_ALLIANCE -> setSymmetric(
                    sourceFactionId, targetFactionId, ALLIED, atLeastReputation(sourceFactionId, targetFactionId, 500));
            case DiplomacyCommand.BREAK_ALLIANCE -> setSymmetric(
                    sourceFactionId, targetFactionId, NEUTRAL, atMostReputation(sourceFactionId, targetFactionId, 150));
            case DiplomacyCommand.BECOME_VASSAL -> setVassal(sourceFactionId, targetFactionId);
            case DiplomacyCommand.RELEASE_VASSAL -> releaseVassal(sourceFactionId, targetFactionId);
            case DiplomacyCommand.DRIFT_REPUTATION -> driftReputation(sourceFactionId, targetFactionId, value);
            default -> false;
        };
        if (!changed) {
            return NO_CHANGE;
        }
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("Diplomacy revision space exhausted");
        }
        revision++;
        return APPLIED;
    }

    /**
     * Schedules a trusted server command. The heap is intentionally bounded; a full queue rejects
     * work instead of allocating on the server tick.
     */
    public int schedule(long dueTick, byte commandType, int sourceFactionId, int targetFactionId, int value) {
        if (dueTick < 0L) {
            return TIME_REVERSED;
        }
        if (!DiplomacyCommand.isValid(commandType)) {
            return INVALID_COMMAND;
        }
        if (!validPair(sourceFactionId, targetFactionId)) {
            return sourceFactionId == targetFactionId ? INVALID_RELATION : INVALID_FACTION;
        }
        if (scheduledSize == dueTicks.length) {
            return QUEUE_FULL;
        }
        if (nextSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("Diplomacy command sequence space exhausted");
        }
        int row = scheduledSize++;
        dueTicks[row] = dueTick;
        sequences[row] = nextSequence++;
        sources[row] = sourceFactionId;
        targets[row] = targetFactionId;
        values[row] = value;
        commandTypes[row] = commandType;
        siftUp(row);
        return APPLIED;
    }

    /** Processes at most {@code budget} due commands in deterministic heap order. */
    public int processDue(long gameTick, int budget) {
        if (gameTick < lastProcessedTick) {
            return TIME_REVERSED;
        }
        if (budget < 0) {
            throw new IllegalArgumentException("Negative diplomacy tick budget");
        }
        lastProcessedTick = gameTick;
        int processed = 0;
        while (processed < budget && scheduledSize != 0 && dueTicks[0] <= gameTick) {
            byte type = commandTypes[0];
            int source = sources[0];
            int target = targets[0];
            int value = values[0];
            removeRoot();
            apply(type, source, target, value);
            processed++;
        }
        return processed;
    }

    /** Stable hash for replay/self-test diagnostics; relation insertion order is canonical input. */
    public long stateHash() {
        synchronizeExternalChanges();
        long hash = 0xcbf29ce484222325L;
        relationCursor.reset();
        while (relationCursor.advance()) {
            hash = mixHash(hash, relationCursor.sourceFactionId());
            hash = mixHash(hash, relationCursor.targetFactionId());
            hash = mixHash(hash, relationCursor.allegianceCode());
            hash = mixHash(hash, relationCursor.reputation());
            hash = mixHash(hash, relationCursor.revision());
        }
        for (int faction = 0; faction < maxFactions; faction++) {
            hash = mixHash(hash, influence[faction]);
        }
        return hash;
    }

    private void rebuildRelationCache() {
        ensurePairCapacity(Math.max(8, saturatedAdd(relations.size(), saturatedMultiply(dueTicks.length, 2))));
        Arrays.fill(pairKeys, 0L);
        Arrays.fill(cachedAllegiances, (byte) 0);
        Arrays.fill(cachedReputations, (short) 0);
        pairSize = 0;
        Arrays.fill(influence, 0);
        relationCursor.reset();
        while (relationCursor.advance()) {
            int source = relationCursor.sourceFactionId();
            int target = relationCursor.targetFactionId();
            byte allegiance = relationCursor.allegianceCode();
            short reputation = relationCursor.reputation();
            cachePut(source, target, allegiance, reputation);
            addContribution(
                    source,
                    target,
                    allegiance,
                    reputation,
                    1);
        }
        observedNextRelationRevision = relations.nextRevision();
    }

    private boolean setSymmetric(int source, int target, byte allegiance, short reputation) {
        boolean first = setDirected(source, target, allegiance, reputation);
        boolean second = setDirected(target, source, allegiance, reputation);
        return first | second;
    }

    private boolean setVassal(int vassal, int overlord) {
        short forward = (short) Math.max(reputation(vassal, overlord), 350);
        short reverse = (short) Math.max(reputation(overlord, vassal), 350);
        boolean first = setDirected(vassal, overlord, VASSAL, forward);
        boolean second = setDirected(overlord, vassal, FRIENDLY, reverse);
        return first | second;
    }

    private boolean releaseVassal(int possibleVassal, int possibleOverlord) {
        if (allegianceCode(possibleVassal, possibleOverlord) != VASSAL) {
            return false;
        }
        boolean changed = setDirected(
                possibleVassal,
                possibleOverlord,
                NEUTRAL,
                (short) Math.min(reputation(possibleVassal, possibleOverlord), 100));
        if (allegianceCode(possibleOverlord, possibleVassal) == FRIENDLY) {
            changed |= setDirected(
                    possibleOverlord,
                    possibleVassal,
                    NEUTRAL,
                    (short) Math.min(reputation(possibleOverlord, possibleVassal), 100));
        }
        return changed;
    }

    private boolean driftReputation(int source, int target, int magnitude) {
        int step = Math.max(0, magnitude);
        int current = reputation(source, target);
        int next = current > 0 ? Math.max(0, current - step) : Math.min(0, current + step);
        return setDirected(source, target, allegianceCode(source, target), (short) next);
    }

    private boolean setDirected(int source, int target, byte allegiance, short reputation) {
        int oldSlot = findPairSlot(pairKey(source, target));
        boolean hadRelation = oldSlot >= 0;
        byte oldAllegiance = hadRelation ? cachedAllegiances[oldSlot] : NEUTRAL;
        short oldReputation = hadRelation ? cachedReputations[oldSlot] : 0;
        if (oldAllegiance == allegiance && oldReputation == reputation) {
            return false;
        }
        if (hadRelation) {
            addContribution(source, target, oldAllegiance, oldReputation, -1);
        }
        relations.put(source, target, allegiance, reputation);
        cachePut(source, target, allegiance, reputation);
        observedNextRelationRevision = relations.nextRevision();
        addContribution(source, target, allegiance, reputation, 1);
        return true;
    }

    private void synchronizeExternalChanges() {
        if (observedNextRelationRevision != relations.nextRevision()) {
            rebuildRelationCache();
        }
    }

    private byte cachedAllegiance(int source, int target) {
        int slot = findPairSlot(pairKey(source, target));
        return slot < 0 ? NEUTRAL : cachedAllegiances[slot];
    }

    private short cachedReputation(int source, int target) {
        int slot = findPairSlot(pairKey(source, target));
        return slot < 0 ? 0 : cachedReputations[slot];
    }

    private void cachePut(int source, int target, byte allegiance, short reputation) {
        long key = pairKey(source, target);
        int slot = findPairSlot(key);
        if (slot >= 0) {
            cachedAllegiances[slot] = allegiance;
            cachedReputations[slot] = reputation;
            return;
        }
        ensurePairCapacity(pairSize + 1);
        slot = findPairSlot(key);
        if (slot >= 0) {
            cachedAllegiances[slot] = allegiance;
            cachedReputations[slot] = reputation;
            return;
        }
        slot = ~slot;
        pairKeys[slot] = key;
        cachedAllegiances[slot] = allegiance;
        cachedReputations[slot] = reputation;
        pairSize++;
    }

    private int findPairSlot(long key) {
        if (pairKeys == null || pairKeys.length == 0) {
            return -1;
        }
        int mask = pairKeys.length - 1;
        int slot = pairHash(key) & mask;
        while (true) {
            long found = pairKeys[slot];
            if (found == 0L) {
                return ~slot;
            }
            if (found == key) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }
    }

    private void ensurePairCapacity(int expectedPairs) {
        int capacity = pairKeys == null ? 0 : pairKeys.length;
        int required = Math.max(8, capacity);
        while ((long) expectedPairs * 10L > (long) required * 6L) {
            if (required >= 1 << 30) {
                throw new IllegalStateException("Diplomacy relation index is too large");
            }
            required <<= 1;
        }
        if (required <= capacity) {
            return;
        }
        long[] oldKeys = pairKeys;
        byte[] oldAllegiances = cachedAllegiances;
        short[] oldReputations = cachedReputations;
        pairKeys = new long[required];
        cachedAllegiances = new byte[required];
        cachedReputations = new short[required];
        if (oldKeys == null) {
            return;
        }
        int oldSize = pairSize;
        pairSize = 0;
        for (int oldSlot = 0; oldSlot < oldKeys.length; oldSlot++) {
            long key = oldKeys[oldSlot];
            if (key == 0L) {
                continue;
            }
            int slot = ~findPairSlot(key);
            pairKeys[slot] = key;
            cachedAllegiances[slot] = oldAllegiances[oldSlot];
            cachedReputations[slot] = oldReputations[oldSlot];
            pairSize++;
        }
        if (pairSize != oldSize) {
            throw new IllegalStateException("Diplomacy relation index rehash lost entries");
        }
    }

    private static long pairKey(int source, int target) {
        return ((long) source << 32) | Integer.toUnsignedLong(target);
    }

    private static int pairHash(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53l;
        value ^= value >>> 33;
        return (int) value;
    }

    private void addContribution(int source, int target, byte allegiance, short reputation, int sign) {
        if (!validPair(source, target)) {
            return;
        }
        int sourcePoints = reputation / 10;
        int targetPoints = 0;
        if (allegiance == HOSTILE) {
            sourcePoints -= 50;
        } else if (allegiance == ALLIED) {
            sourcePoints += 200;
        } else if (allegiance == VASSAL) {
            sourcePoints -= 100;
            targetPoints += 300;
        } else if (allegiance == FRIENDLY) {
            sourcePoints += 40;
        }
        influence[source] = safeAdd(influence[source], sign * sourcePoints);
        influence[target] = safeAdd(influence[target], sign * targetPoints);
    }

    private short atMostReputation(int source, int target, int ceiling) {
        int reputation = Math.min(reputation(source, target), reputation(target, source));
        return clampReputation(Math.min(reputation, ceiling));
    }

    private short atLeastReputation(int source, int target, int floor) {
        int reputation = Math.max(reputation(source, target), reputation(target, source));
        return clampReputation(Math.max(reputation, floor));
    }

    private static short clampReputation(long value) {
        return (short) Math.max(
                DiplomacyRelation.MIN_REPUTATION,
                Math.min(DiplomacyRelation.MAX_REPUTATION, value));
    }

    private boolean validFaction(int factionId) {
        return factionId >= 0 && factionId < maxFactions;
    }

    private boolean validPair(int sourceFactionId, int targetFactionId) {
        return sourceFactionId != targetFactionId
                && validFaction(sourceFactionId)
                && validFaction(targetFactionId);
    }

    private void siftUp(int row) {
        while (row > 0) {
            int parent = (row - 1) >>> 1;
            if (!less(row, parent)) {
                return;
            }
            swap(row, parent);
            row = parent;
        }
    }

    private void removeRoot() {
        int last = --scheduledSize;
        if (last == 0) {
            clearHeapRow(0);
            return;
        }
        dueTicks[0] = dueTicks[last];
        sequences[0] = sequences[last];
        sources[0] = sources[last];
        targets[0] = targets[last];
        values[0] = values[last];
        commandTypes[0] = commandTypes[last];
        clearHeapRow(last);
        int row = 0;
        while (true) {
            int left = (row << 1) + 1;
            if (left >= scheduledSize) {
                return;
            }
            int right = left + 1;
            int smaller = right < scheduledSize && less(right, left) ? right : left;
            if (!less(smaller, row)) {
                return;
            }
            swap(row, smaller);
            row = smaller;
        }
    }

    private boolean less(int left, int right) {
        long leftDue = dueTicks[left];
        long rightDue = dueTicks[right];
        return leftDue < rightDue || leftDue == rightDue && sequences[left] < sequences[right];
    }

    private void swap(int left, int right) {
        long due = dueTicks[left];
        dueTicks[left] = dueTicks[right];
        dueTicks[right] = due;
        long sequence = sequences[left];
        sequences[left] = sequences[right];
        sequences[right] = sequence;
        int source = sources[left];
        sources[left] = sources[right];
        sources[right] = source;
        int target = targets[left];
        targets[left] = targets[right];
        targets[right] = target;
        int value = values[left];
        values[left] = values[right];
        values[right] = value;
        byte type = commandTypes[left];
        commandTypes[left] = commandTypes[right];
        commandTypes[right] = type;
    }

    private void clearHeapRow(int row) {
        dueTicks[row] = 0L;
        sequences[row] = 0L;
        sources[row] = 0;
        targets[row] = 0;
        values[row] = 0;
        commandTypes[row] = 0;
    }

    private static int safeAdd(int left, int right) {
        long result = (long) left + right;
        return result > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : result < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) result;
    }

    private static int saturatedAdd(int left, int right) {
        long result = (long) left + right;
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    private static int saturatedMultiply(int left, int right) {
        long result = (long) left * right;
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }

    private static long mixHash(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }
}
