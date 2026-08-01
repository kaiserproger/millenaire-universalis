package ru.kaiserroman.millenairearmies.server.unit;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Strategic rank metadata only; it does not alter combat attributes or AI. */
public final class UnitRankDescriptor {
    private final ResourceLocation key;
    private final int token;
    private final int commandTier;
    private final int sortPriority;

    UnitRankDescriptor(ResourceLocation key, int commandTier, int sortPriority) {
        this.key = Objects.requireNonNull(key, "key");
        this.token = UnitDescriptorToken.rank(key);
        this.commandTier = commandTier;
        this.sortPriority = sortPriority;
    }

    public ResourceLocation key() { return key; }
    public int token() { return token; }
    public int commandTier() { return commandTier; }
    public int sortPriority() { return sortPriority; }
}
