package ru.kaiserroman.millenairearmies.integration.millenaire;

import java.util.Arrays;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.entity.MillVillager;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;

/**
 * Tracks loaded Millenaire villagers and resolves them to the village index.
 *
 * <p>The join path only reads already-indexed data. It never loads a chunk or saved-data entry,
 * which is important because NeoForge can fire the join event before a chunk reaches FULL. The
 * coordinator resolves misses on the following server post-tick.</p>
 */
public final class MillenaireEntityBridge {
    private static final int MIN_CAPACITY = 32;
    private static final int TOMBSTONE = -1;

    private MillVillager[] villagers = new MillVillager[MIN_CAPACITY];
    private Village[] villages = new Village[MIN_CAPACITY];
    private long[] indexMost = new long[64];
    private long[] indexLeast = new long[64];
    /** Dense entity slot + 1; zero is empty and {@value #TOMBSTONE} is deleted. */
    private int[] indexSlots = new int[64];
    private int indexEntries;
    private int indexTombstones;
    private int size;
    private int unresolved;

    /** Returns true when the villager already has a village binding. */
    public boolean onJoin(MillVillager villager, ServerLevel level, MillenaireVillageIndex index) {
        long most = villager.getUUID().getMostSignificantBits();
        long least = villager.getUUID().getLeastSignificantBits();
        int existing = indexOfUuid(most, least);
        Village village = resolve(villager, level, index);
        if (existing >= 0) {
            // Chunk reload can publish the replacement instance before the old leave callback.
            villagers[existing] = villager;
            villages[existing] = village;
            recountUnresolved();
            return village != null;
        }

        ensureCapacity(size + 1);
        villagers[size] = villager;
        villages[size] = village;
        insertIndex(most, least, size);
        size++;
        if (village == null) {
            unresolved++;
        }
        return village != null;
    }

    /**
     * Discovers already-loaded villagers whose join event happened before this service started.
     * Existing runtime instances are skipped, so the periodic lifecycle call is linear rather than
     * repeatedly rebuilding dense UUID state.
     */
    public int discoverLoaded(MinecraftServer server, MillenaireVillageIndex index) {
        if (server == null || index == null) {
            throw new NullPointerException("server/index");
        }
        int changes = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (!(entity instanceof MillVillager villager) || villager.isRemoved()) {
                    continue;
                }
                int existing = indexOfUuid(
                        villager.getUUID().getMostSignificantBits(),
                        villager.getUUID().getLeastSignificantBits());
                if (existing >= 0 && villagers[existing] == villager) {
                    continue;
                }
                onJoin(villager, level, index);
                changes++;
            }
        }
        return changes;
    }

    public boolean onLeave(MillVillager villager) {
        int slot = indexOfUuid(
                villager.getUUID().getMostSignificantBits(),
                villager.getUUID().getLeastSignificantBits());
        if (slot < 0 || villagers[slot] != villager) {
            return false;
        }
        removeAt(slot);
        return true;
    }

    /** Refreshes all loaded bindings after the village index has been reconciled. */
    public int reconcile(MillenaireVillageIndex index) {
        int changes = 0;
        int nextUnresolved = 0;
        for (int slot = size - 1; slot >= 0; slot--) {
            MillVillager villager = villagers[slot];
            if (villager == null || villager.isRemoved()) {
                removeAt(slot);
                changes++;
                continue;
            }

            Village resolved = resolve(villager, null, index);
            if (villages[slot] != resolved) {
                villages[slot] = resolved;
                changes++;
            }
            if (resolved == null) {
                nextUnresolved++;
            }
        }
        unresolved = nextUnresolved;
        return changes;
    }

    public Village villageFor(MillVillager villager) {
        int slot = indexOf(villager);
        return slot < 0 ? null : villages[slot];
    }

    public MillVillager findLoaded(long uuidMost, long uuidLeast) {
        int slot = indexOfUuid(uuidMost, uuidLeast);
        MillVillager villager = slot < 0 ? null : villagers[slot];
        return villager == null || villager.isRemoved() || villager.getUUID() == null
                ? null
                : villager;
    }

    /**
     * Scans a bounded rotating stripe of loaded Millenaire entities around an attack objective.
     * The caller owns the cursor, so many combatants cannot trigger an all-pairs entity scan.
     */
    public MillVillager findHostileTarget(
            MillVillager source,
            int sourceFaction,
            FactionProjectionService factions,
            double objectiveX,
            double objectiveZ,
            double objectiveRadiusSq,
            double sourceRangeSq,
            int scanBudget,
            CombatSearch search,
            CombatTargetScorer scorer) {
        if (source == null || factions == null || search == null || size == 0 || scanBudget <= 0) {
            return null;
        }
        int work = Math.min(scanBudget, size);
        int start = search.cursor >= size ? 0 : search.cursor;
        MillVillager best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int offset = 0; offset < work; offset++) {
            int slot = start + offset;
            if (slot >= size) slot -= size;
            MillVillager candidate = villagers[slot];
            if (candidate == null
                    || candidate == source
                    || candidate.isRemoved()
                    || !candidate.isAlive()
                    || candidate.level() != source.level()
                    || candidate.isBaby()) {
                continue;
            }
            Village village = villages[slot];
            int targetFaction = factions.factionForVillage(village);
            double objectiveDx = candidate.getX() - objectiveX;
            double objectiveDz = candidate.getZ() - objectiveZ;
            double objectiveDistanceSq = objectiveDx * objectiveDx + objectiveDz * objectiveDz;
            if (objectiveDistanceSq > objectiveRadiusSq) {
                continue;
            }
            double dx = candidate.getX() - source.getX();
            double dy = candidate.getY() - source.getY();
            double dz = candidate.getZ() - source.getZ();
            double distanceSq = dx * dx + dy * dy + dz * dz;
            if (distanceSq > sourceRangeSq) {
                continue;
            }
            double score = scorer == null
                    ? distanceSq
                    : scorer.score(candidate, targetFaction, distanceSq, objectiveDistanceSq);
            if (Double.isFinite(score) && score < bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        search.cursor = start + work;
        if (search.cursor >= size) search.cursor %= size;
        return best;
    }

    public int size() {
        return size;
    }

    public int unresolvedCount() {
        return unresolved;
    }

    /** Visits the current dense loaded projection without allocating wrappers or collections. */
    public void visitLoaded(LoadedVisitor visitor) {
        if (visitor == null) throw new NullPointerException("visitor");
        for (int slot = 0; slot < size; slot++) {
            MillVillager villager = villagers[slot];
            if (villager != null && !villager.isRemoved()) {
                visitor.accept(villager, villages[slot]);
            }
        }
    }

    public void clear() {
        Arrays.fill(villagers, 0, size, null);
        Arrays.fill(villages, 0, size, null);
        Arrays.fill(indexSlots, 0);
        size = 0;
        unresolved = 0;
        indexEntries = 0;
        indexTombstones = 0;
    }

    private static Village resolve(
            MillVillager villager, ServerLevel eventLevel, MillenaireVillageIndex index) {
        VillageId villageId = villager.getVillageId();
        if (villageId == null || villageId.uuid() == null) {
            return null;
        }
        Village village = index.find(villageId);
        if (village == null || eventLevel == null) {
            return village;
        }
        return index.level(villageId) == eventLevel ? village : null;
    }

    private int indexOf(MillVillager villager) {
        int slot = indexOfUuid(
                villager.getUUID().getMostSignificantBits(),
                villager.getUUID().getLeastSignificantBits());
        return slot >= 0 && villagers[slot] == villager ? slot : -1;
    }

    private void removeAt(int slot) {
        MillVillager removed = villagers[slot];
        removeIndex(
                removed.getUUID().getMostSignificantBits(),
                removed.getUUID().getLeastSignificantBits());
        if (villages[slot] == null) {
            unresolved--;
        }
        int last = --size;
        if (slot != last) {
            villagers[slot] = villagers[last];
            villages[slot] = villages[last];
            MillVillager moved = villagers[slot];
            updateIndexSlot(
                    moved.getUUID().getMostSignificantBits(),
                    moved.getUUID().getLeastSignificantBits(),
                    slot);
        }
        villagers[last] = null;
        villages[last] = null;
    }

    private void recountUnresolved() {
        int count = 0;
        for (int slot = 0; slot < size; slot++) {
            if (villages[slot] == null) {
                count++;
            }
        }
        unresolved = count;
    }

    private void ensureCapacity(int requested) {
        if (requested <= villagers.length) {
            return;
        }
        int capacity = Math.max(requested, villagers.length << 1);
        villagers = Arrays.copyOf(villagers, capacity);
        villages = Arrays.copyOf(villages, capacity);
    }

    private int indexOfUuid(long most, long least) {
        int mask = indexSlots.length - 1;
        int bucket = mix(most, least) & mask;
        while (indexSlots[bucket] != 0) {
            if (indexSlots[bucket] > 0
                    && indexMost[bucket] == most
                    && indexLeast[bucket] == least) {
                return indexSlots[bucket] - 1;
            }
            bucket = bucket + 1 & mask;
        }
        return -1;
    }

    private void insertIndex(long most, long least, int denseSlot) {
        ensureIndexCapacity();
        int mask = indexSlots.length - 1;
        int bucket = mix(most, least) & mask;
        int firstTombstone = -1;
        while (indexSlots[bucket] != 0) {
            if (indexSlots[bucket] == TOMBSTONE && firstTombstone < 0) {
                firstTombstone = bucket;
            }
            bucket = bucket + 1 & mask;
        }
        if (firstTombstone >= 0) {
            bucket = firstTombstone;
            indexTombstones--;
        }
        indexMost[bucket] = most;
        indexLeast[bucket] = least;
        indexSlots[bucket] = denseSlot + 1;
        indexEntries++;
    }

    private void removeIndex(long most, long least) {
        int bucket = indexBucket(most, least);
        if (bucket < 0) {
            return;
        }
        indexSlots[bucket] = TOMBSTONE;
        indexEntries--;
        indexTombstones++;
    }

    private void updateIndexSlot(long most, long least, int denseSlot) {
        int bucket = indexBucket(most, least);
        if (bucket < 0) {
            throw new IllegalStateException("Loaded MillVillager UUID index lost a dense row");
        }
        indexSlots[bucket] = denseSlot + 1;
    }

    private int indexBucket(long most, long least) {
        int mask = indexSlots.length - 1;
        int bucket = mix(most, least) & mask;
        while (indexSlots[bucket] != 0) {
            if (indexSlots[bucket] > 0
                    && indexMost[bucket] == most
                    && indexLeast[bucket] == least) {
                return bucket;
            }
            bucket = bucket + 1 & mask;
        }
        return -1;
    }

    private void ensureIndexCapacity() {
        if ((indexEntries + indexTombstones + 1) * 2 < indexSlots.length) {
            return;
        }
        int requested = indexTombstones > indexEntries ? indexSlots.length : indexSlots.length << 1;
        rehash(requested);
    }

    private void rehash(int capacity) {
        long[] oldMost = indexMost;
        long[] oldLeast = indexLeast;
        int[] oldSlots = indexSlots;
        indexMost = new long[capacity];
        indexLeast = new long[capacity];
        indexSlots = new int[capacity];
        indexEntries = 0;
        indexTombstones = 0;
        for (int bucket = 0; bucket < oldSlots.length; bucket++) {
            if (oldSlots[bucket] > 0) {
                insertIndex(oldMost[bucket], oldLeast[bucket], oldSlots[bucket] - 1);
            }
        }
    }

    private static int mix(long most, long least) {
        long value = most ^ Long.rotateLeft(least, 29);
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        return (int) (value ^ value >>> 32);
    }

    @FunctionalInterface
    public interface LoadedVisitor {
        void accept(MillVillager villager, Village village);
    }

    @FunctionalInterface
    public interface CombatTargetScorer {
        /** Lower finite scores are preferred; non-finite scores reject a candidate. */
        double score(
                MillVillager candidate,
                int targetFaction,
                double sourceDistanceSq,
                double objectiveDistanceSq);
    }

    /** Caller-owned rotating cursor used by one retained combat task. */
    public static final class CombatSearch {
        private int cursor;

        public void reset() {
            cursor = 0;
        }
    }
}
