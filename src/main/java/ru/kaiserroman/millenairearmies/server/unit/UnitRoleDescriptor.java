package ru.kaiserroman.millenairearmies.server.unit;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** Reload-time role metadata. Cross references are deterministic primitive tokens. */
public final class UnitRoleDescriptor {
    private final ResourceLocation key;
    private final int token;
    private final int defaultRankToken;
    private final int defaultLoadoutToken;
    private final int formationPriority;

    UnitRoleDescriptor(
            ResourceLocation key,
            int defaultRankToken,
            int defaultLoadoutToken,
            int formationPriority) {
        this.key = Objects.requireNonNull(key, "key");
        this.token = UnitDescriptorToken.role(key);
        this.defaultRankToken = defaultRankToken;
        this.defaultLoadoutToken = defaultLoadoutToken;
        this.formationPriority = formationPriority;
    }

    public ResourceLocation key() { return key; }
    public int token() { return token; }
    public int defaultRankToken() { return defaultRankToken; }
    public int defaultLoadoutToken() { return defaultLoadoutToken; }
    public int formationPriority() { return formationPriority; }
}
