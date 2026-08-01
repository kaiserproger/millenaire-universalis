package ru.kaiserroman.millenairearmies.presentation.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;
import ru.kaiserroman.millenairearmies.presentation.PresentationDefinition;
import ru.kaiserroman.millenairearmies.presentation.PresentationKind;

/** Loads client resource-pack presentation definitions from assets/&lt;namespace&gt;/army_presentations. */
public final class ClientPresentationCatalog extends SimpleJsonResourceReloadListener {
    public static final String ROOT_DIRECTORY = "army_presentations";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    public static final ClientPresentationCatalog INSTANCE = new ClientPresentationCatalog();

    private volatile Snapshot snapshot = Snapshot.empty();

    private ClientPresentationCatalog() {
        super(GSON, ROOT_DIRECTORY);
    }

    public PresentationDefinition get(PresentationKind kind, ResourceLocation id) {
        return snapshot.definitions.get(kind).get(id);
    }

    public int size(PresentationKind kind) {
        return snapshot.definitions.get(kind).size();
    }

    @Override
    protected void apply(
            Map<ResourceLocation, JsonElement> resources,
            ResourceManager resourceManager,
            ProfilerFiller profiler) {
        EnumMap<PresentationKind, Map<ResourceLocation, PresentationDefinition>> mutable =
                new EnumMap<>(PresentationKind.class);
        for (PresentationKind kind : PresentationKind.values()) {
            mutable.put(kind, new HashMap<>());
        }

        int rejected = 0;
        for (Map.Entry<ResourceLocation, JsonElement> resource : resources.entrySet()) {
            try {
                ParsedId parsed = parseId(resource.getKey());
                PresentationDefinition definition = parseDefinition(
                        parsed.kind, parsed.id, GsonHelper.convertToJsonObject(resource.getValue(), "presentation"));
                PresentationDefinition previous = mutable.get(parsed.kind).put(parsed.id, definition);
                if (previous != null) {
                    throw new JsonParseException("duplicate presentation id " + parsed.id);
                }
            } catch (RuntimeException exception) {
                rejected++;
                LOGGER.error("Could not load army presentation {}: {}", resource.getKey(), exception.getMessage());
            }
        }

        EnumMap<PresentationKind, Map<ResourceLocation, PresentationDefinition>> frozen =
                new EnumMap<>(PresentationKind.class);
        int loaded = 0;
        for (PresentationKind kind : PresentationKind.values()) {
            Map<ResourceLocation, PresentationDefinition> definitions = Map.copyOf(mutable.get(kind));
            frozen.put(kind, definitions);
            loaded += definitions.size();
        }
        snapshot = new Snapshot(frozen);
        ClientPresentationState.units().refreshDefinitions(this);
        LOGGER.info("Loaded {} army presentation definitions ({} rejected)", loaded, rejected);
    }

    private static ParsedId parseId(ResourceLocation resourceId) {
        String path = resourceId.getPath();
        int separator = path.indexOf('/');
        if (separator <= 0 || separator == path.length() - 1) {
            throw new JsonParseException("expected <kind>/<id>.json below " + ROOT_DIRECTORY);
        }
        PresentationKind kind = PresentationKind.fromDirectory(path.substring(0, separator));
        if (kind == null) {
            throw new JsonParseException("unknown presentation kind " + path.substring(0, separator));
        }
        return new ParsedId(kind, resourceId.withPath(path.substring(separator + 1)));
    }

    private static PresentationDefinition parseDefinition(
            PresentationKind kind, ResourceLocation id, JsonObject json) {
        String translationKey = GsonHelper.getAsString(json, "translation_key");
        String shortLabel = GsonHelper.getAsString(json, "short_label", "");
        if (shortLabel.length() > 16) {
            throw new JsonParseException("short_label is longer than 16 characters");
        }
        int color = parseColor(GsonHelper.getAsString(json, "color", "#ffffff"));
        ResourceLocation icon = ResourceLocation.tryParse(GsonHelper.getAsString(json, "icon"));
        if (icon == null) {
            throw new JsonParseException("icon is not a valid resource location");
        }
        return new PresentationDefinition(kind, id, translationKey, shortLabel, color, icon);
    }

    private static int parseColor(String raw) {
        String hex = raw.startsWith("#") ? raw.substring(1) : raw;
        if (hex.length() != 6) {
            throw new JsonParseException("color must be #RRGGBB");
        }
        try {
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException exception) {
            throw new JsonParseException("color must be #RRGGBB", exception);
        }
    }

    private record ParsedId(PresentationKind kind, ResourceLocation id) {}

    private record Snapshot(EnumMap<PresentationKind, Map<ResourceLocation, PresentationDefinition>> definitions) {
        private static Snapshot empty() {
            EnumMap<PresentationKind, Map<ResourceLocation, PresentationDefinition>> maps =
                    new EnumMap<>(PresentationKind.class);
            for (PresentationKind kind : PresentationKind.values()) {
                maps.put(kind, Map.of());
            }
            return new Snapshot(maps);
        }
    }
}
