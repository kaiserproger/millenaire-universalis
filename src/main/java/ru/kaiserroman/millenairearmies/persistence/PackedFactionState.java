package ru.kaiserroman.millenairearmies.persistence;

import java.util.Arrays;
import ru.kaiserroman.millenairearmies.model.FactionAllegiance;

/** Primitive relation graph backing faction UI snapshots and later revision-based delta sync. */
public final class PackedFactionState {
    private static final int MIN_GROWTH = 8;

    private int size;
    private int structuralVersion;
    private long nextRevision = 1L;
    private int[] sourceFactionIds = new int[0];
    private int[] targetFactionIds = new int[0];
    private byte[] allegianceCodes = new byte[0];
    private short[] reputations = new short[0];
    private long[] revisions = new long[0];
    /** Open-addressed pair -> dense row+1 index; zero is empty. */
    private int[] lookupRows = new int[0];

    public PackedFactionState() {
    }

    public PackedFactionState(int expectedRelations) {
        reserve(expectedRelations);
    }

    public int size() {
        return size;
    }

    public long nextRevision() {
        return nextRevision;
    }

    public void reserve(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Negative faction relation capacity: " + capacity);
        }
        ensureCapacity(capacity);
    }

    /** Inserts or updates one directed relation without allocating within reserved capacity. */
    public boolean put(int sourceFactionId, int targetFactionId, byte allegianceCode, short reputation) {
        validate(sourceFactionId, targetFactionId, allegianceCode, 1L);
        int existing = findRow(sourceFactionId, targetFactionId);
        if (existing >= 0) {
            if (allegianceCodes[existing] == allegianceCode && reputations[existing] == reputation) {
                return false;
            }
            allegianceCodes[existing] = allegianceCode;
            reputations[existing] = reputation;
            revisions[existing] = claimRevision();
            return true;
        }
        ensureCapacity(size + 1);
        ensureLookupCapacity(size + 1);
        int row = size++;
        sourceFactionIds[row] = sourceFactionId;
        targetFactionIds[row] = targetFactionId;
        allegianceCodes[row] = allegianceCode;
        reputations[row] = reputation;
        revisions[row] = claimRevision();
        indexRow(row);
        structuralVersion++;
        return true;
    }

    /**
     * Removes relations whose source or target is no longer present in the supplied active-id set.
     * The supplied prefix is read directly and retained nowhere.
     */
    public int removeRelationsOutside(int[] activeFactionIds, int activeCount) {
        if (activeFactionIds == null) {
            throw new NullPointerException("activeFactionIds");
        }
        if (activeCount < 0 || activeCount > activeFactionIds.length) {
            throw new IllegalArgumentException("Invalid active faction count " + activeCount);
        }
        int oldSize = size;
        int write = 0;
        for (int read = 0; read < oldSize; read++) {
            if (!contains(activeFactionIds, activeCount, sourceFactionIds[read])
                    || !contains(activeFactionIds, activeCount, targetFactionIds[read])) {
                claimRevision();
                continue;
            }
            if (write != read) {
                sourceFactionIds[write] = sourceFactionIds[read];
                targetFactionIds[write] = targetFactionIds[read];
                allegianceCodes[write] = allegianceCodes[read];
                reputations[write] = reputations[read];
                revisions[write] = revisions[read];
            }
            write++;
        }
        int removed = oldSize - write;
        if (removed == 0) {
            return 0;
        }
        Arrays.fill(sourceFactionIds, write, oldSize, 0);
        Arrays.fill(targetFactionIds, write, oldSize, 0);
        Arrays.fill(allegianceCodes, write, oldSize, (byte) 0);
        Arrays.fill(reputations, write, oldSize, (short) 0);
        Arrays.fill(revisions, write, oldSize, 0L);
        size = write;
        structuralVersion++;
        rebuildLookup(lookupCapacityFor(size));
        return removed;
    }

    /** Allocation-free directed allegiance lookup for combat and diplomacy gates. */
    public byte allegianceCode(int sourceFactionId, int targetFactionId) {
        int row = findRow(sourceFactionId, targetFactionId);
        return row < 0 ? FactionAllegiance.NEUTRAL.code() : allegianceCodes[row];
    }

    public Cursor newCursor() {
        return new Cursor(this);
    }

    void restore(int sourceFactionId, int targetFactionId, byte allegianceCode, short reputation, long revision) {
        validate(sourceFactionId, targetFactionId, allegianceCode, revision);
        if (findRow(sourceFactionId, targetFactionId) >= 0) {
            throw new IllegalArgumentException("Duplicate persisted faction relation");
        }
        ensureCapacity(size + 1);
        ensureLookupCapacity(size + 1);
        int row = size++;
        sourceFactionIds[row] = sourceFactionId;
        targetFactionIds[row] = targetFactionId;
        allegianceCodes[row] = allegianceCode;
        reputations[row] = reputation;
        revisions[row] = revision;
        indexRow(row);
        structuralVersion++;
    }

    void restoreNextRevision(long restoredNextRevision) {
        if (restoredNextRevision <= 0L) {
            throw new IllegalArgumentException("Next faction revision must be positive");
        }
        long minimum = 1L;
        for (int row = 0; row < size; row++) {
            if (revisions[row] == Long.MAX_VALUE) {
                throw new IllegalArgumentException("Persisted faction revision cannot be incremented");
            }
            minimum = Math.max(minimum, revisions[row] + 1L);
        }
        if (restoredNextRevision < minimum) {
            throw new IllegalArgumentException("Next faction revision precedes persisted relation revisions");
        }
        nextRevision = restoredNextRevision;
    }

    private long claimRevision() {
        if (nextRevision == Long.MAX_VALUE) {
            throw new IllegalStateException("Faction revision space exhausted");
        }
        return nextRevision++;
    }

    private static boolean contains(int[] ids, int count, int requested) {
        for (int index = 0; index < count; index++) {
            if (ids[index] == requested) {
                return true;
            }
        }
        return false;
    }

    private static void validate(int source, int target, byte allegiance, long revision) {
        if (source < 0 || target < 0 || source == target) {
            throw new IllegalArgumentException("Faction relation requires two distinct non-negative ids");
        }
        if (!FactionAllegiance.isValidCode(allegiance)) {
            throw new IllegalArgumentException("Unknown faction allegiance code: " + allegiance);
        }
        if (revision <= 0L) {
            throw new IllegalArgumentException("Faction revision must be positive");
        }
    }

    private void ensureCapacity(int required) {
        if (required <= sourceFactionIds.length) {
            return;
        }
        int current = sourceFactionIds.length;
        int capacity = Math.max(required, current < MIN_GROWTH ? MIN_GROWTH : current + (current >>> 1));
        sourceFactionIds = Arrays.copyOf(sourceFactionIds, capacity);
        targetFactionIds = Arrays.copyOf(targetFactionIds, capacity);
        allegianceCodes = Arrays.copyOf(allegianceCodes, capacity);
        reputations = Arrays.copyOf(reputations, capacity);
        revisions = Arrays.copyOf(revisions, capacity);
    }

    private int findRow(int sourceFactionId, int targetFactionId) {
        if (lookupRows.length == 0) {
            return -1;
        }
        int mask = lookupRows.length - 1;
        int slot = pairHash(sourceFactionId, targetFactionId) & mask;
        while (true) {
            int encoded = lookupRows[slot];
            if (encoded == 0) {
                return -1;
            }
            int row = encoded - 1;
            if (sourceFactionIds[row] == sourceFactionId && targetFactionIds[row] == targetFactionId) {
                return row;
            }
            slot = (slot + 1) & mask;
        }
    }

    private void ensureLookupCapacity(int requiredRows) {
        int requiredCapacity = lookupCapacityFor(requiredRows);
        if (requiredCapacity > lookupRows.length) {
            rebuildLookup(requiredCapacity);
        }
    }

    private void rebuildLookup(int capacity) {
        lookupRows = capacity == 0 ? new int[0] : new int[capacity];
        for (int row = 0; row < size; row++) {
            indexRow(row);
        }
    }

    private void indexRow(int row) {
        int mask = lookupRows.length - 1;
        int slot = pairHash(sourceFactionIds[row], targetFactionIds[row]) & mask;
        while (lookupRows[slot] != 0) {
            slot = (slot + 1) & mask;
        }
        lookupRows[slot] = row + 1;
    }

    private static int lookupCapacityFor(int rows) {
        if (rows == 0) {
            return 0;
        }
        int capacity = 16;
        while ((long) rows * 10L >= (long) capacity * 7L) {
            if (capacity >= 1 << 30) {
                throw new IllegalStateException("Faction relation lookup capacity exhausted");
            }
            capacity <<= 1;
        }
        return capacity;
    }

    private static int pairHash(int source, int target) {
        int value = source * 0x9e3779b9 + Integer.rotateLeft(target, 16);
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        return value;
    }


    public static final class Cursor {
        private final PackedFactionState owner;
        private int expectedStructuralVersion;
        private int nextRow;
        private int row = -1;

        private Cursor(PackedFactionState owner) {
            this.owner = owner;
            reset();
        }

        public Cursor reset() {
            expectedStructuralVersion = owner.structuralVersion;
            nextRow = 0;
            row = -1;
            return this;
        }

        public boolean advance() {
            checkVersion();
            if (nextRow == owner.size) {
                row = -1;
                return false;
            }
            row = nextRow++;
            return true;
        }

        public int sourceFactionId() {
            checkActive();
            return owner.sourceFactionIds[row];
        }

        public int targetFactionId() {
            checkActive();
            return owner.targetFactionIds[row];
        }

        public byte allegianceCode() {
            checkActive();
            return owner.allegianceCodes[row];
        }

        public short reputation() {
            checkActive();
            return owner.reputations[row];
        }

        public long revision() {
            checkActive();
            return owner.revisions[row];
        }

        private void checkVersion() {
            if (expectedStructuralVersion != owner.structuralVersion) {
                throw new IllegalStateException("Faction cursor invalidated by structural change; reset it");
            }
        }

        private void checkActive() {
            checkVersion();
            if (row < 0) {
                throw new IllegalStateException("Faction cursor is not on a row");
            }
        }
    }
}
