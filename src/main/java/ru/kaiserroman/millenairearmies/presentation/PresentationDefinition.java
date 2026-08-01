package ru.kaiserroman.millenairearmies.presentation;

import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable, resource-pack supplied visuals for one role, rank, banner, or order status.
 * Components are assembled once on resource reload and are safe to share between unit mirrors.
 */
public final class PresentationDefinition {
    private final PresentationKind kind;
    private final ResourceLocation id;
    private final String translationKey;
    private final String shortLabel;
    private final int colorRgb;
    private final ResourceLocation icon;
    private final Component displayName;
    private final Component marker;

    public PresentationDefinition(
            PresentationKind kind,
            ResourceLocation id,
            String translationKey,
            String shortLabel,
            int colorRgb,
            ResourceLocation icon) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.id = Objects.requireNonNull(id, "id");
        this.translationKey = requireText(translationKey, "translationKey");
        this.shortLabel = Objects.requireNonNull(shortLabel, "shortLabel");
        this.colorRgb = colorRgb & 0x00ff_ffff;
        this.icon = Objects.requireNonNull(icon, "icon");
        this.displayName = Component.translatable(this.translationKey).withColor(this.colorRgb);
        this.marker = this.shortLabel.isEmpty()
                ? Component.empty()
                : Component.literal(this.shortLabel).withColor(this.colorRgb);
    }

    public PresentationKind kind() {
        return kind;
    }

    public ResourceLocation id() {
        return id;
    }

    public String translationKey() {
        return translationKey;
    }

    public String shortLabel() {
        return shortLabel;
    }

    public int colorRgb() {
        return colorRgb;
    }

    public ResourceLocation icon() {
        return icon;
    }

    public Component displayName() {
        return displayName;
    }

    public Component marker() {
        return marker;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
