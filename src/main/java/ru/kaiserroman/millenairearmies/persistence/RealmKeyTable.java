package ru.kaiserroman.millenairearmies.persistence;

import java.util.Arrays;
import java.util.UUID;

/** Stable one-based ids for player and settlement subjects used by the pure Realm kernel. */
public final class RealmKeyTable {
    public static final byte PLAYER = 1;
    public static final byte SETTLEMENT = 2;

    private final int maximumSubjects;
    private int size;
    private long[] uuidMost = new long[16];
    private long[] uuidLeast = new long[16];
    private byte[] kinds = new byte[16];

    public RealmKeyTable(int maximumSubjects) {
        if (maximumSubjects <= 0) throw new IllegalArgumentException("maximumSubjects must be positive");
        this.maximumSubjects = maximumSubjects;
        int capacity = Math.min(uuidMost.length, maximumSubjects);
        uuidMost = Arrays.copyOf(uuidMost, capacity);
        uuidLeast = Arrays.copyOf(uuidLeast, capacity);
        kinds = Arrays.copyOf(kinds, capacity);
    }

    public long internPlayer(UUID uuid) { return intern(uuid, PLAYER); }
    public long internSettlement(UUID uuid) { return intern(uuid, SETTLEMENT); }
    public long findPlayer(UUID uuid) { return find(uuid, PLAYER); }
    public long findSettlement(UUID uuid) { return find(uuid, SETTLEMENT); }

    public long intern(UUID uuid, byte kind) {
        requireKind(kind);
        if (uuid == null) throw new NullPointerException("uuid");
        long existing = find(uuid, kind);
        if (existing != 0L) return existing;
        if (size == maximumSubjects) throw new IllegalStateException("Realm subject key limit reached");
        ensureCapacity(size + 1);
        int row = size++;
        uuidMost[row] = uuid.getMostSignificantBits();
        uuidLeast[row] = uuid.getLeastSignificantBits();
        kinds[row] = kind;
        return row + 1L;
    }

    public long restore(UUID uuid, byte kind) {
        requireKind(kind);
        if (uuid == null || find(uuid, kind) != 0L || size == maximumSubjects) {
            throw new IllegalArgumentException("Duplicate or excessive restored Realm subject");
        }
        ensureCapacity(size + 1);
        int row = size++;
        uuidMost[row] = uuid.getMostSignificantBits();
        uuidLeast[row] = uuid.getLeastSignificantBits();
        kinds[row] = kind;
        return row + 1L;
    }

    public UUID uuid(long subjectId) {
        int row = row(subjectId);
        return new UUID(uuidMost[row], uuidLeast[row]);
    }

    public byte kind(long subjectId) { return kinds[row(subjectId)]; }
    public boolean valid(long subjectId) { return subjectId > 0L && subjectId <= size; }
    public int size() { return size; }

    public void visit(Visitor visitor) {
        if (visitor == null) throw new NullPointerException("visitor");
        for (int row = 0; row < size; row++) {
            visitor.accept(row + 1L, kinds[row], uuidMost[row], uuidLeast[row]);
        }
    }

    public int estimatedPrimitiveBytes() {
        return uuidMost.length * Long.BYTES + uuidLeast.length * Long.BYTES + kinds.length;
    }

    private long find(UUID uuid, byte kind) {
        if (uuid == null) return 0L;
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        for (int row = 0; row < size; row++) {
            if (kinds[row] == kind && uuidMost[row] == most && uuidLeast[row] == least) {
                return row + 1L;
            }
        }
        return 0L;
    }

    private int row(long subjectId) {
        if (!valid(subjectId)) throw new IndexOutOfBoundsException("Realm subject id=" + subjectId);
        return (int) subjectId - 1;
    }

    private void ensureCapacity(int required) {
        if (required <= uuidMost.length) return;
        int capacity = Math.min(
                maximumSubjects,
                Math.max(required, uuidMost.length + Math.max(1, uuidMost.length >>> 1)));
        uuidMost = Arrays.copyOf(uuidMost, capacity);
        uuidLeast = Arrays.copyOf(uuidLeast, capacity);
        kinds = Arrays.copyOf(kinds, capacity);
    }

    private static void requireKind(byte kind) {
        if (kind != PLAYER && kind != SETTLEMENT) {
            throw new IllegalArgumentException("Unknown Realm subject kind " + kind);
        }
    }

    @FunctionalInterface
    public interface Visitor {
        void accept(long subjectId, byte kind, long uuidMost, long uuidLeast);
    }
}
