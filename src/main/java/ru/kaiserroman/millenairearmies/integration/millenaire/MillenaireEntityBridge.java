package ru.kaiserroman.millenairearmies.integration.millenaire;

import java.util.Arrays;
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

    private MillVillager[] villagers = new MillVillager[MIN_CAPACITY];
    private Village[] villages = new Village[MIN_CAPACITY];
    private int size;
    private int unresolved;

    /** Returns true when the villager already has a village binding. */
    public boolean onJoin(MillVillager villager, ServerLevel level, MillenaireVillageIndex index) {
        int existing = indexOf(villager);
        Village village = resolve(villager, level, index);
        if (existing >= 0) {
            villages[existing] = village;
            recountUnresolved();
            return village != null;
        }

        ensureCapacity(size + 1);
        villagers[size] = villager;
        villages[size] = village;
        size++;
        if (village == null) {
            unresolved++;
        }
        return village != null;
    }

    public boolean onLeave(MillVillager villager) {
        int slot = indexOf(villager);
        if (slot < 0) {
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
        for (int slot = 0; slot < size; slot++) {
            MillVillager villager = villagers[slot];
            if (villager.getUUID().getMostSignificantBits() == uuidMost
                    && villager.getUUID().getLeastSignificantBits() == uuidLeast) {
                return villager;
            }
        }
        return null;
    }

    /** Cold explicit-UI traversal over already loaded entities; never loads a chunk or record. */
    public int visitLoaded(LoadedVillagerVisitor visitor) {
        int visited = 0;
        for (int slot = 0; slot < size; slot++) {
            MillVillager villager = villagers[slot];
            Village village = villages[slot];
            if (villager == null || villager.isRemoved() || village == null) {
                continue;
            }
            visitor.accept(villager, village);
            visited++;
        }
        return visited;
    }

    public int size() {
        return size;
    }

    /** Bounded server-thread projection access for retained execution systems. */
    public MillVillager loadedVillagerAt(int row) {
        if (row < 0 || row >= size) {
            throw new IndexOutOfBoundsException("Loaded villager row " + row + " outside 0.." + size);
        }
        return villagers[row];
    }

    /** Village paired with {@link #loadedVillagerAt}; may be null until reconciliation. */
    public Village loadedVillageAt(int row) {
        if (row < 0 || row >= size) {
            throw new IndexOutOfBoundsException("Loaded villager row " + row + " outside 0.." + size);
        }
        return villages[row];
    }

    public int unresolvedCount() {
        return unresolved;
    }

    public void clear() {
        Arrays.fill(villagers, 0, size, null);
        Arrays.fill(villages, 0, size, null);
        size = 0;
        unresolved = 0;
    }

    private static Village resolve(
            MillVillager villager, ServerLevel eventLevel, MillenaireVillageIndex index) {
        VillageId villageId = villager.getVillageId();
        if (villageId == null) {
            return null;
        }
        Village village = index.find(villageId);
        if (village == null || eventLevel == null) {
            return village;
        }
        return index.level(villageId) == eventLevel ? village : null;
    }

    private int indexOf(MillVillager villager) {
        for (int slot = 0; slot < size; slot++) {
            if (villagers[slot] == villager) {
                return slot;
            }
        }
        return -1;
    }

    private void removeAt(int slot) {
        if (villages[slot] == null) {
            unresolved--;
        }
        int last = --size;
        if (slot != last) {
            villagers[slot] = villagers[last];
            villages[slot] = villages[last];
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

    @FunctionalInterface
    public interface LoadedVillagerVisitor {
        void accept(MillVillager villager, Village village);
    }
}
