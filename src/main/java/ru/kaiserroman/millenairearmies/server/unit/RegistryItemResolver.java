package ru.kaiserroman.millenairearmies.server.unit;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Optional item lookup seam. Integrations pass registry keys and never link third-party classes.
 * A null result means "candidate unavailable" and advances to the next configured fallback.
 */
@FunctionalInterface
public interface RegistryItemResolver {
    RegistryItemResolver BUILT_IN = key -> {
        if (!BuiltInRegistries.ITEM.containsKey(key)) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(key);
        return item == Items.AIR ? null : item;
    };

    Item resolve(ResourceLocation key);
}
