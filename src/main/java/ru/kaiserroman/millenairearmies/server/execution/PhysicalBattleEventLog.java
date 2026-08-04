package ru.kaiserroman.millenairearmies.server.execution;

import java.util.Arrays;

/**
 * Bounded, allocation-free-on-append journal of physical battle facts.
 *
 * <p>The journal deliberately contains no Realm or Simulation state. Consumers receive primitive
 * army/unit handles, faction ids, a stable dimension id, position and a monotonically increasing
 * sequence. A slow reader can detect overwritten rows through {@link Cursor#droppedCount()} and
 * rebuild higher-level war or siege state without reading the Armies ECS directly.</p>
 */
public final class PhysicalBattleEventLog {
    public static final byte CONTACT = 1;
    public static final byte MELEE_HIT = 2;
    public static final byte RANGED_SHOT = 3;
    public static final byte UNIT_DEFEATED = 4;
    public static final byte SIEGE_STARTED = 5;
    public static final byte SIEGE_PROGRESS = 6;
    public static final byte SIEGE_SECURED = 7;

    /** The amount column is fixed-point health units with this scale for hit events. */
    public static final int HEALTH_SCALE = 100;

    private final int capacity;
    private final long[] sequences;
    private final long[] gameTimes;
    private final long[] packedPositions;
    private final int[] sourceArmies;
    private final int[] targetArmies;
    private final int[] sourceUnits;
    private final int[] targetUnits;
    private final int[] sourceFactions;
    private final int[] targetFactions;
    private final int[] dimensionIds;
    private final int[] amounts;
    private final byte[] kinds;

    private int size;
    private int writeIndex;
    private long nextSequence = 1L;

    public PhysicalBattleEventLog(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Battle event capacity must be positive");
        }
        this.capacity = capacity;
        sequences = new long[capacity];
        gameTimes = new long[capacity];
        packedPositions = new long[capacity];
        sourceArmies = new int[capacity];
        targetArmies = new int[capacity];
        sourceUnits = new int[capacity];
        targetUnits = new int[capacity];
        sourceFactions = new int[capacity];
        targetFactions = new int[capacity];
        dimensionIds = new int[capacity];
        amounts = new int[capacity];
        kinds = new byte[capacity];
    }

    public int capacity() {
        return capacity;
    }

    public int size() {
        return size;
    }

    public long oldestSequence() {
        return size == 0 ? nextSequence : nextSequence - size;
    }

    public long latestSequence() {
        return nextSequence - 1L;
    }

    /** Starts at the oldest event currently retained. Cursor creation is a cold consumer action. */
    public Cursor cursor() {
        return new Cursor(this, oldestSequence());
    }

    /** Starts after a sequence previously consumed by another module. */
    public Cursor cursorAfter(long consumedSequence) {
        if (consumedSequence < 0L || consumedSequence == Long.MAX_VALUE) {
            throw new IllegalArgumentException("Consumed battle sequence is outside bounds");
        }
        return new Cursor(this, consumedSequence + 1L);
    }

    public long estimatedPrimitiveBytes() {
        return (long) capacity * (Long.BYTES * 3L + Integer.BYTES * 8L + Byte.BYTES);
    }

    void clear() {
        Arrays.fill(sequences, 0L);
        Arrays.fill(gameTimes, 0L);
        Arrays.fill(packedPositions, 0L);
        Arrays.fill(sourceArmies, 0);
        Arrays.fill(targetArmies, 0);
        Arrays.fill(sourceUnits, 0);
        Arrays.fill(targetUnits, 0);
        Arrays.fill(sourceFactions, 0);
        Arrays.fill(targetFactions, 0);
        Arrays.fill(dimensionIds, 0);
        Arrays.fill(amounts, 0);
        Arrays.fill(kinds, (byte) 0);
        size = 0;
        writeIndex = 0;
        nextSequence = 1L;
    }

    long append(
            byte kind,
            long gameTime,
            int sourceArmy,
            int targetArmy,
            int sourceUnit,
            int targetUnit,
            int sourceFaction,
            int targetFaction,
            int dimensionId,
            long packedPosition,
            int amount) {
        if (!validKind(kind)) {
            throw new IllegalArgumentException("Unknown physical battle event kind: " + kind);
        }
        if (gameTime < 0L || dimensionId < -1 || amount < 0) {
            throw new IllegalArgumentException("Physical battle event values must be bounded");
        }
        if (nextSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("Physical battle event sequence exhausted");
        }

        int row = writeIndex;
        long sequence = nextSequence++;
        sequences[row] = sequence;
        gameTimes[row] = gameTime;
        packedPositions[row] = packedPosition;
        sourceArmies[row] = sourceArmy;
        targetArmies[row] = targetArmy;
        sourceUnits[row] = sourceUnit;
        targetUnits[row] = targetUnit;
        sourceFactions[row] = sourceFaction;
        targetFactions[row] = targetFaction;
        dimensionIds[row] = dimensionId;
        amounts[row] = amount;
        kinds[row] = kind;

        writeIndex++;
        if (writeIndex == capacity) {
            writeIndex = 0;
        }
        if (size < capacity) {
            size++;
        }
        return sequence;
    }

    private int oldestIndex() {
        int index = writeIndex - size;
        return index < 0 ? index + capacity : index;
    }

    private static boolean validKind(byte kind) {
        return kind >= CONTACT && kind <= SIEGE_SECURED;
    }

    public static final class Cursor {
        private final PhysicalBattleEventLog owner;
        private long nextSequence;
        private long currentSequence;
        private long droppedCount;
        private int row = -1;

        private Cursor(PhysicalBattleEventLog owner, long nextSequence) {
            this.owner = owner;
            this.nextSequence = nextSequence;
        }

        public boolean advance() {
            long oldest = owner.oldestSequence();
            if (nextSequence < oldest) {
                droppedCount += oldest - nextSequence;
                nextSequence = oldest;
            }
            long latest = owner.latestSequence();
            if (nextSequence > latest) {
                row = -1;
                currentSequence = 0L;
                return false;
            }
            long offset = nextSequence - oldest;
            if (offset < 0L || offset >= owner.size) {
                row = -1;
                currentSequence = 0L;
                return false;
            }
            int candidate = owner.oldestIndex() + (int) offset;
            if (candidate >= owner.capacity) {
                candidate -= owner.capacity;
            }
            if (owner.sequences[candidate] != nextSequence) {
                // A consumer escaped the server-thread contract or was overwritten between reads.
                // Re-anchor once and expose the loss instead of returning a mismatched row.
                long refreshedOldest = owner.oldestSequence();
                if (nextSequence < refreshedOldest) {
                    droppedCount += refreshedOldest - nextSequence;
                    nextSequence = refreshedOldest;
                    return advance();
                }
                row = -1;
                currentSequence = 0L;
                return false;
            }
            row = candidate;
            currentSequence = nextSequence++;
            return true;
        }

        public long droppedCount() {
            return droppedCount;
        }

        public long sequence() {
            checkActive();
            return currentSequence;
        }

        public byte kind() {
            checkActive();
            return owner.kinds[row];
        }

        public long gameTime() {
            checkActive();
            return owner.gameTimes[row];
        }

        public int sourceArmy() {
            checkActive();
            return owner.sourceArmies[row];
        }

        public int targetArmy() {
            checkActive();
            return owner.targetArmies[row];
        }

        public int sourceUnit() {
            checkActive();
            return owner.sourceUnits[row];
        }

        public int targetUnit() {
            checkActive();
            return owner.targetUnits[row];
        }

        public int sourceFaction() {
            checkActive();
            return owner.sourceFactions[row];
        }

        public int targetFaction() {
            checkActive();
            return owner.targetFactions[row];
        }

        public int dimensionId() {
            checkActive();
            return owner.dimensionIds[row];
        }

        public long packedPosition() {
            checkActive();
            return owner.packedPositions[row];
        }

        public int amount() {
            checkActive();
            return owner.amounts[row];
        }

        private void checkActive() {
            if (row < 0) {
                throw new IllegalStateException("Battle event cursor is not on a row");
            }
        }
    }
}
