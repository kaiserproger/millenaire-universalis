package ru.kaiserroman.millenairearmies.integration.millenaire;

import java.util.Arrays;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageSavedData;

/**
 * Server-thread-only index of Millenaire villages.
 *
 * <p>The index deliberately uses the public beta.2 saved-data API and primitive UUID columns. A
 * normal reconciliation reuses its tables, so the periodic 200-tick scan does not create a map
 * entry or wrapper object per village.</p>
 */
public final class MillenaireVillageIndex {
    private static final byte EMPTY = 0;
    private static final byte OCCUPIED = 1;
    private static final byte TOMBSTONE = 2;
    private static final int MIN_CAPACITY = 16;

    private byte[] states = new byte[MIN_CAPACITY];
    private long[] uuidMost = new long[MIN_CAPACITY];
    private long[] uuidLeast = new long[MIN_CAPACITY];
    private Village[] villages = new Village[MIN_CAPACITY];
    private ServerLevel[] levels = new ServerLevel[MIN_CAPACITY];
    private int[] seenEpoch = new int[MIN_CAPACITY];

    private int size;
    private int tombstones;
    private int epoch;
    private long reconciliationCount;

    /** Reconciles all dimensions and returns the number of added, replaced, or removed entries. */
    public int reconcile(MinecraftServer server) {
        int activeEpoch = nextEpoch();
        int changes = 0;

        for (ServerLevel level : server.getAllLevels()) {
            for (Village village : VillageSavedData.get(level).getVillageManager().getAllVillages()) {
                if (village != null && village.getId() != null && village.getId().uuid() != null) {
                    changes += upsert(level, village, activeEpoch);
                }
            }
        }

        for (int slot = 0; slot < states.length; slot++) {
            if (states[slot] == OCCUPIED && seenEpoch[slot] != activeEpoch) {
                states[slot] = TOMBSTONE;
                villages[slot] = null;
                levels[slot] = null;
                size--;
                tombstones++;
                changes++;
            }
        }

        // Village deletion is rare, but reclaim tombstones before they can make lookups degrade.
        if (tombstones > size && tombstones > 8) {
            rehash(states.length);
        }
        reconciliationCount++;
        return changes;
    }

    public Village find(VillageId villageId) {
        if (villageId == null || villageId.uuid() == null) {
            return null;
        }
        return find(villageId.uuid().getMostSignificantBits(), villageId.uuid().getLeastSignificantBits());
    }

    public Village find(long most, long least) {
        int slot = findSlot(most, least);
        return slot < 0 ? null : villages[slot];
    }

    public ServerLevel level(VillageId villageId) {
        if (villageId == null || villageId.uuid() == null) {
            return null;
        }
        int slot = findSlot(
                villageId.uuid().getMostSignificantBits(), villageId.uuid().getLeastSignificantBits());
        return slot < 0 ? null : levels[slot];
    }

    public int size() {
        return size;
    }

    public long reconciliationCount() {
        return reconciliationCount;
    }

    /**
     * Returns a reusable, allocation-free cursor over the currently indexed villages.
     *
     * <p>The cursor is invalidated by reconciliation or {@link #clear()}; callers should keep it
     * only as reusable scratch owned by another server-thread-only service.</p>
     */
    public Cursor newCursor() {
        return new Cursor(this);
    }

    public void clear() {
        Arrays.fill(states, EMPTY);
        Arrays.fill(villages, null);
        Arrays.fill(levels, null);
        Arrays.fill(seenEpoch, 0);
        size = 0;
        tombstones = 0;
        epoch = 0;
        reconciliationCount = 0;
    }

    private int upsert(ServerLevel level, Village village, int activeEpoch) {
        if (level == null || village == null || village.getId() == null || village.getId().uuid() == null) {
            return 0;
        }
        long most = village.getId().uuid().getMostSignificantBits();
        long least = village.getId().uuid().getLeastSignificantBits();
        ensureInsertCapacity();

        int mask = states.length - 1;
        int slot = hash(most, least) & mask;
        int firstTombstone = -1;
        while (true) {
            byte state = states[slot];
            if (state == EMPTY) {
                int target = firstTombstone >= 0 ? firstTombstone : slot;
                if (firstTombstone >= 0) {
                    tombstones--;
                }
                states[target] = OCCUPIED;
                uuidMost[target] = most;
                uuidLeast[target] = least;
                villages[target] = village;
                levels[target] = level;
                seenEpoch[target] = activeEpoch;
                size++;
                return 1;
            }
            if (state == TOMBSTONE) {
                if (firstTombstone < 0) {
                    firstTombstone = slot;
                }
            } else if (uuidMost[slot] == most && uuidLeast[slot] == least) {
                boolean changed = villages[slot] != village || levels[slot] != level;
                villages[slot] = village;
                levels[slot] = level;
                seenEpoch[slot] = activeEpoch;
                return changed ? 1 : 0;
            }
            slot = (slot + 1) & mask;
        }
    }

    private int findSlot(long most, long least) {
        int mask = states.length - 1;
        int slot = hash(most, least) & mask;
        while (true) {
            byte state = states[slot];
            if (state == EMPTY) {
                return -1;
            }
            if (state == OCCUPIED && uuidMost[slot] == most && uuidLeast[slot] == least) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }
    }

    private void ensureInsertCapacity() {
        if ((size + tombstones + 1) * 10 < states.length * 7) {
            return;
        }
        int requested = size * 10 < states.length * 5 ? states.length : states.length << 1;
        rehash(requested);
    }

    private void rehash(int requestedCapacity) {
        int capacity = MIN_CAPACITY;
        while (capacity < requestedCapacity) {
            capacity <<= 1;
        }

        byte[] oldStates = states;
        long[] oldMost = uuidMost;
        long[] oldLeast = uuidLeast;
        Village[] oldVillages = villages;
        ServerLevel[] oldLevels = levels;
        int[] oldSeenEpoch = seenEpoch;

        states = new byte[capacity];
        uuidMost = new long[capacity];
        uuidLeast = new long[capacity];
        villages = new Village[capacity];
        levels = new ServerLevel[capacity];
        seenEpoch = new int[capacity];
        size = 0;
        tombstones = 0;

        int mask = capacity - 1;
        for (int oldSlot = 0; oldSlot < oldStates.length; oldSlot++) {
            if (oldStates[oldSlot] != OCCUPIED) {
                continue;
            }
            int slot = hash(oldMost[oldSlot], oldLeast[oldSlot]) & mask;
            while (states[slot] == OCCUPIED) {
                slot = (slot + 1) & mask;
            }
            states[slot] = OCCUPIED;
            uuidMost[slot] = oldMost[oldSlot];
            uuidLeast[slot] = oldLeast[oldSlot];
            villages[slot] = oldVillages[oldSlot];
            levels[slot] = oldLevels[oldSlot];
            seenEpoch[slot] = oldSeenEpoch[oldSlot];
            size++;
        }
    }

    private int nextEpoch() {
        epoch++;
        if (epoch == 0) {
            Arrays.fill(seenEpoch, 0);
            epoch = 1;
        }
        return epoch;
    }

    private static int hash(long most, long least) {
        long value = most ^ Long.rotateLeft(least, 29);
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53l;
        value ^= value >>> 33;
        return (int) value;
    }

    public static final class Cursor {
        private final MillenaireVillageIndex owner;
        private long expectedReconciliationCount;
        private int nextSlot;
        private int slot = -1;

        private Cursor(MillenaireVillageIndex owner) {
            this.owner = owner;
            reset();
        }

        public Cursor reset() {
            expectedReconciliationCount = owner.reconciliationCount;
            nextSlot = 0;
            slot = -1;
            return this;
        }

        public boolean advance() {
            checkVersion();
            while (nextSlot < owner.states.length) {
                int candidate = nextSlot++;
                if (owner.states[candidate] == OCCUPIED) {
                    slot = candidate;
                    return true;
                }
            }
            slot = -1;
            return false;
        }

        public long uuidMost() {
            checkActive();
            return owner.uuidMost[slot];
        }

        public long uuidLeast() {
            checkActive();
            return owner.uuidLeast[slot];
        }

        public Village village() {
            checkActive();
            return owner.villages[slot];
        }

        public ServerLevel level() {
            checkActive();
            return owner.levels[slot];
        }

        private void checkVersion() {
            if (expectedReconciliationCount != owner.reconciliationCount) {
                throw new IllegalStateException("Village cursor invalidated by reconciliation; reset it");
            }
        }

        private void checkActive() {
            checkVersion();
            if (slot < 0) {
                throw new IllegalStateException("Village cursor is not on an entry");
            }
        }
    }
}
