package ru.kaiserroman.millenairearmies.persistence;

import java.util.Arrays;
import net.minecraft.resources.ResourceLocation;

/** Cold stable item-name dictionary; hot logistics rows store only its primitive ids. */
public final class StableItemTable {
    private String[] names = new String[0];
    private int size;

    public int size() {
        return size;
    }

    public int intern(ResourceLocation name) {
        return intern(name.toString());
    }

    public ResourceLocation name(int id) {
        return ResourceLocation.parse(nameString(id));
    }

    String nameString(int id) {
        if (id < 0 || id >= size) {
            throw new IllegalArgumentException("Unknown item dictionary id " + id);
        }
        return names[id];
    }

    int intern(String name) {
        ResourceLocation parsed = ResourceLocation.tryParse(name);
        if (parsed == null) {
            throw new IllegalArgumentException("Invalid item resource name " + name);
        }
        String canonical = parsed.toString();
        for (int id = 0; id < size; id++) {
            if (names[id].equals(canonical)) {
                return id;
            }
        }
        if (size == names.length) {
            names = Arrays.copyOf(names, Math.max(8, size + (size >>> 1) + 1));
        }
        names[size] = canonical;
        return size++;
    }
}
