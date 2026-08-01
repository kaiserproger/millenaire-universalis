package ru.kaiserroman.millenairearmies.persistence;

import java.util.Arrays;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Persistent culture-name to faction-id dictionary.
 *
 * <p>Faction ids are initially derived from the full culture resource name. Persisting the chosen
 * value makes collision resolution stable even if another culture is installed later. Removed
 * cultures deliberately keep their reservation so that restoring a datapack does not renumber
 * armies or diplomacy.</p>
 */
public final class FactionIdentitySavedData extends SavedData {
    public static final String FILE_ID = "millenaire_armies_faction_ids";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_MAPPINGS = 65_536;
    private static final SavedData.Factory<FactionIdentitySavedData> FACTORY =
            new SavedData.Factory<>(FactionIdentitySavedData::new, FactionIdentitySavedData::load);

    private ResourceLocation[] cultures = new ResourceLocation[8];
    private int[] factionIds = new int[8];
    private int size;

    public static FactionIdentitySavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    public int size() {
        return size;
    }

    public int factionId(ResourceLocation culture) {
        int row = findCulture(culture);
        return row < 0 ? -1 : factionIds[row];
    }

    /** Returns the existing stable id or reserves and dirties a new mapping. */
    public int resolve(ResourceLocation culture) {
        if (culture == null) {
            throw new NullPointerException("culture");
        }
        int row = findCulture(culture);
        if (row >= 0) {
            return factionIds[row];
        }
        if (size == MAX_MAPPINGS) {
            throw new IllegalStateException("Faction identity mapping limit reached");
        }

        int salt = 0;
        int candidate;
        do {
            candidate = stableId(culture, salt++);
        } while (findFactionId(candidate) >= 0);

        ensureCapacity(size + 1);
        cultures[size] = culture;
        factionIds[size] = candidate;
        size++;
        setDirty();
        return candidate;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("SchemaVersion", SCHEMA_VERSION);
        ListTag names = new ListTag();
        int[] ids = new int[size];
        for (int row = 0; row < size; row++) {
            names.add(StringTag.valueOf(cultures[row].toString()));
            ids[row] = factionIds[row];
        }
        tag.put("Cultures", names);
        tag.putIntArray("FactionIds", ids);
        return tag;
    }

    static FactionIdentitySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        int schema = tag.getInt("SchemaVersion");
        if (schema != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported faction identity schema " + schema);
        }
        ListTag names = tag.getList("Cultures", Tag.TAG_STRING);
        int[] ids = tag.getIntArray("FactionIds");
        if (names.size() != ids.length || ids.length > MAX_MAPPINGS) {
            throw new IllegalArgumentException("Invalid faction identity mapping lengths");
        }

        FactionIdentitySavedData data = new FactionIdentitySavedData();
        data.ensureCapacity(ids.length);
        for (int row = 0; row < ids.length; row++) {
            ResourceLocation culture = ResourceLocation.tryParse(names.getString(row));
            int id = ids[row];
            if (culture == null || id < 0 || data.findCulture(culture) >= 0 || data.findFactionId(id) >= 0) {
                throw new IllegalArgumentException("Invalid or duplicate faction identity at row " + row);
            }
            data.cultures[row] = culture;
            data.factionIds[row] = id;
            data.size++;
        }
        return data;
    }

    private int findCulture(ResourceLocation culture) {
        for (int row = 0; row < size; row++) {
            if (cultures[row].equals(culture)) {
                return row;
            }
        }
        return -1;
    }

    private int findFactionId(int factionId) {
        for (int row = 0; row < size; row++) {
            if (factionIds[row] == factionId) {
                return row;
            }
        }
        return -1;
    }

    private void ensureCapacity(int required) {
        if (required <= cultures.length) {
            return;
        }
        int capacity = Math.max(required, cultures.length + (cultures.length >>> 1));
        cultures = Arrays.copyOf(cultures, capacity);
        factionIds = Arrays.copyOf(factionIds, capacity);
    }

    /** Stable 31-bit FNV-1a variant over the canonical namespace/path, with a collision salt. */
    static int stableId(ResourceLocation culture, int salt) {
        int hash = 0x811c9dc5;
        hash = hashChars(hash, culture.getNamespace());
        hash = (hash ^ ':') * 0x01000193;
        hash = hashChars(hash, culture.getPath());
        hash ^= salt * 0x9e3779b9;
        hash ^= hash >>> 16;
        hash *= 0x7feb352d;
        hash ^= hash >>> 15;
        return hash & 0x7fff_ffff;
    }

    private static int hashChars(int hash, String text) {
        for (int index = 0; index < text.length(); index++) {
            hash = (hash ^ text.charAt(index)) * 0x01000193;
        }
        return hash;
    }
}
