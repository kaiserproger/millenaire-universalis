package ru.kaiserroman.millenairearmies.presentation.client;

import java.util.Objects;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenairearmies.presentation.PresentationDefinition;
import ru.kaiserroman.millenairearmies.presentation.PresentationKind;

/**
 * Client-only visual mirror for a unit. It contains presentation ids and a prebuilt marker, but no
 * entity, level, capability, or other world reference.
 */
public final class ClientUnitPresentation {
    public static final byte FLAG_SHOW_OVERHEAD_MARKER = 1;

    private static final Component SPACE = Component.literal(" ");

    private final ResourceLocation roleId;
    private final ResourceLocation rankId;
    private final ResourceLocation bannerId;
    private final ResourceLocation orderStatusId;
    private final byte flags;
    private final Component overheadMarker;
    private final boolean hasOverheadMarker;

    private ClientUnitPresentation(
            ResourceLocation roleId,
            ResourceLocation rankId,
            ResourceLocation bannerId,
            ResourceLocation orderStatusId,
            byte flags,
            Component overheadMarker) {
        this.roleId = Objects.requireNonNull(roleId, "roleId");
        this.rankId = Objects.requireNonNull(rankId, "rankId");
        this.bannerId = Objects.requireNonNull(bannerId, "bannerId");
        this.orderStatusId = Objects.requireNonNull(orderStatusId, "orderStatusId");
        this.flags = flags;
        this.overheadMarker = Objects.requireNonNull(overheadMarker, "overheadMarker");
        this.hasOverheadMarker = !overheadMarker.getSiblings().isEmpty();
    }

    /** Builds all text once, when a server mirror update or resource reload is applied. */
    public static ClientUnitPresentation resolve(
            ClientPresentationCatalog catalog,
            ResourceLocation roleId,
            ResourceLocation rankId,
            ResourceLocation bannerId,
            ResourceLocation orderStatusId,
            byte flags) {
        Objects.requireNonNull(catalog, "catalog");
        PresentationDefinition role = catalog.get(PresentationKind.ROLE, roleId);
        PresentationDefinition rank = catalog.get(PresentationKind.RANK, rankId);
        PresentationDefinition banner = catalog.get(PresentationKind.BANNER, bannerId);
        PresentationDefinition status = catalog.get(PresentationKind.ORDER_STATUS, orderStatusId);

        MutableComponent marker = Component.empty();
        append(marker, banner);
        append(marker, rank);
        append(marker, role);
        append(marker, status);
        return new ClientUnitPresentation(roleId, rankId, bannerId, orderStatusId, flags, marker);
    }

    public ClientUnitPresentation resolveAgain(ClientPresentationCatalog catalog) {
        return resolve(catalog, roleId, rankId, bannerId, orderStatusId, flags);
    }

    public ResourceLocation roleId() {
        return roleId;
    }

    public ResourceLocation rankId() {
        return rankId;
    }

    public ResourceLocation bannerId() {
        return bannerId;
    }

    public ResourceLocation orderStatusId() {
        return orderStatusId;
    }

    public byte flags() {
        return flags;
    }

    public boolean showOverheadMarker() {
        return (flags & FLAG_SHOW_OVERHEAD_MARKER) != 0 && hasOverheadMarker;
    }

    public Component overheadMarker() {
        return overheadMarker;
    }

    private static void append(MutableComponent target, PresentationDefinition definition) {
        if (definition == null || definition.shortLabel().isEmpty()) {
            return;
        }
        if (!target.getSiblings().isEmpty()) {
            target.append(SPACE);
        }
        target.append(definition.marker());
    }
}
