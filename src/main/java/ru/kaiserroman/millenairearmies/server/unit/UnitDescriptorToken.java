package ru.kaiserroman.millenairearmies.server.unit;

import net.minecraft.resources.ResourceLocation;

/** Stable allocation-free token derivation for resource keys. Zero remains the fallback sentinel. */
public final class UnitDescriptorToken {
    private static final int FNV_OFFSET = 0x811c9dc5;
    private static final int FNV_PRIME = 0x01000193;

    private UnitDescriptorToken() {}

    public static int role(ResourceLocation key) {
        return token(key, 0x52); // R
    }

    public static int rank(ResourceLocation key) {
        return token(key, 0x4b); // K
    }

    public static int loadout(ResourceLocation key) {
        return token(key, 0x4c); // L
    }

    private static int token(ResourceLocation key, int kindSalt) {
        int hash = (FNV_OFFSET ^ kindSalt) * FNV_PRIME;
        hash = append(hash, key.getNamespace());
        hash = (hash ^ ':') * FNV_PRIME;
        hash = append(hash, key.getPath());
        return hash == 0 ? 1 : hash;
    }

    private static int append(int hash, String value) {
        for (int index = 0, length = value.length(); index < length; index++) {
            hash = (hash ^ value.charAt(index)) * FNV_PRIME;
        }
        return hash;
    }
}
