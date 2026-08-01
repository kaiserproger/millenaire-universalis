package ru.kaiserroman.millenairearmies.server.unit;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;

/**
 * Resolved visual/equipment descriptor. Items are registry singletons; ItemStacks are created only
 * when an explicit projection actually changes a loaded villager slot.
 */
public final class UnitLoadoutDescriptor {
    public static final int SLOT_COUNT = 6;

    private final ResourceLocation key;
    private final int token;
    private final ResourceLocation[][] candidates;
    private final Item[] resolvedItems;

    UnitLoadoutDescriptor(
            ResourceLocation key,
            ResourceLocation[][] candidates,
            RegistryItemResolver resolver) {
        this.key = Objects.requireNonNull(key, "key");
        this.token = UnitDescriptorToken.loadout(key);
        this.candidates = candidates;
        this.resolvedItems = new Item[SLOT_COUNT];
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            ResourceLocation[] alternatives = candidates[slot];
            for (int candidate = 0; candidate < alternatives.length; candidate++) {
                Item item = resolver.resolve(alternatives[candidate]);
                if (item != null) {
                    resolvedItems[slot] = item;
                    break;
                }
            }
        }
    }

    public ResourceLocation key() { return key; }
    public int token() { return token; }

    /** Returns null when no configured registry candidate exists. */
    public Item resolvedItem(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= SLOT_COUNT) {
            throw new IndexOutOfBoundsException(slotIndex);
        }
        return resolvedItems[slotIndex];
    }

    public Item resolvedItem(EquipmentSlot slot) {
        return resolvedItems[indexOf(slot)];
    }

    /** Cold-path descriptor inspection. The returned array must not be modified. */
    public ResourceLocation[] candidates(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= SLOT_COUNT) {
            throw new IndexOutOfBoundsException(slotIndex);
        }
        return candidates[slotIndex];
    }

    public int resolvedSlotCount() {
        int count = 0;
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            if (resolvedItems[slot] != null) {
                count++;
            }
        }
        return count;
    }

    public static EquipmentSlot slot(int index) {
        return switch (index) {
            case 0 -> EquipmentSlot.MAINHAND;
            case 1 -> EquipmentSlot.OFFHAND;
            case 2 -> EquipmentSlot.HEAD;
            case 3 -> EquipmentSlot.CHEST;
            case 4 -> EquipmentSlot.LEGS;
            case 5 -> EquipmentSlot.FEET;
            default -> throw new IndexOutOfBoundsException(index);
        };
    }

    private static int indexOf(EquipmentSlot slot) {
        return switch (slot) {
            case MAINHAND -> 0;
            case OFFHAND -> 1;
            case HEAD -> 2;
            case CHEST -> 3;
            case LEGS -> 4;
            case FEET -> 5;
            default -> throw new IllegalArgumentException("Unsupported army loadout slot " + slot);
        };
    }
}
