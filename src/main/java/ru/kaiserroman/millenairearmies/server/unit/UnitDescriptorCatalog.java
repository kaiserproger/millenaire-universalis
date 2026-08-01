package ru.kaiserroman.millenairearmies.server.unit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

/** Server datapack catalog for roles, ranks, and registry-key-only equipment loadouts. */
public final class UnitDescriptorCatalog extends SimpleJsonResourceReloadListener {
    public static final String ROOT_DIRECTORY = "army_unit_descriptors";
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    public static final UnitDescriptorCatalog INSTANCE = new UnitDescriptorCatalog(RegistryItemResolver.BUILT_IN);
    private static final int MAX_ITEM_FALLBACKS = 8;
    private static final ResourceLocation[] NO_CANDIDATES = new ResourceLocation[0];

    private final RegistryItemResolver itemResolver;
    private volatile Snapshot snapshot = Snapshot.empty();

    UnitDescriptorCatalog(RegistryItemResolver itemResolver) {
        super(GSON, ROOT_DIRECTORY);
        this.itemResolver = itemResolver;
    }

    public long generation() { return snapshot.generation; }
    public int roleCount() { return snapshot.roles.size(); }
    public int rankCount() { return snapshot.ranks.size(); }
    public int loadoutCount() { return snapshot.loadouts.size(); }

    public UnitRoleDescriptor role(int token) { return snapshot.roles.get(token); }
    public UnitRankDescriptor rank(int token) { return snapshot.ranks.get(token); }
    public UnitLoadoutDescriptor loadout(int token) { return snapshot.loadouts.get(token); }

    public UnitRoleDescriptor role(ResourceLocation key) {
        UnitRoleDescriptor descriptor = role(UnitDescriptorToken.role(key));
        return descriptor != null && descriptor.key().equals(key) ? descriptor : null;
    }

    public UnitRankDescriptor rank(ResourceLocation key) {
        UnitRankDescriptor descriptor = rank(UnitDescriptorToken.rank(key));
        return descriptor != null && descriptor.key().equals(key) ? descriptor : null;
    }

    public UnitLoadoutDescriptor loadout(ResourceLocation key) {
        UnitLoadoutDescriptor descriptor = loadout(UnitDescriptorToken.loadout(key));
        return descriptor != null && descriptor.key().equals(key) ? descriptor : null;
    }

    @Override
    protected void apply(
            Map<ResourceLocation, JsonElement> resources,
            ResourceManager resourceManager,
            ProfilerFiller profiler) {
        TokenTable.Builder<UnitRoleDescriptor> roles = new TokenTable.Builder<>();
        TokenTable.Builder<UnitRankDescriptor> ranks = new TokenTable.Builder<>();
        TokenTable.Builder<UnitLoadoutDescriptor> loadouts = new TokenTable.Builder<>();
        List<Map.Entry<ResourceLocation, JsonElement>> ordered = new ArrayList<>(resources.entrySet());
        ordered.sort(Comparator.comparing(entry -> entry.getKey().toString()));

        int rejected = 0;
        for (Map.Entry<ResourceLocation, JsonElement> resource : ordered) {
            try {
                ParsedId parsed = parseId(resource.getKey());
                JsonObject json = GsonHelper.convertToJsonObject(resource.getValue(), "unit descriptor");
                switch (parsed.kind) {
                    case ROLE -> {
                        UnitRoleDescriptor descriptor = parseRole(parsed.key, json);
                        roles.put(descriptor.token(), descriptor.key(), descriptor);
                    }
                    case RANK -> {
                        UnitRankDescriptor descriptor = parseRank(parsed.key, json);
                        ranks.put(descriptor.token(), descriptor.key(), descriptor);
                    }
                    case LOADOUT -> {
                        UnitLoadoutDescriptor descriptor = parseLoadout(parsed.key, json, itemResolver);
                        loadouts.put(descriptor.token(), descriptor.key(), descriptor);
                    }
                }
            } catch (RuntimeException exception) {
                rejected++;
                LOGGER.error("Could not load army unit descriptor {}: {}", resource.getKey(), exception.getMessage());
            }
        }

        Snapshot previous = snapshot;
        Snapshot loaded = new Snapshot(
                previous.generation + 1L,
                roles.build(),
                ranks.build(),
                loadouts.build());
        snapshot = loaded;
        int unresolvedReferences = validateRoleReferences(loaded);
        LOGGER.info(
                "Loaded army unit descriptors: roles={}, ranks={}, loadouts={} ({} rejected, {} unresolved role references)",
                loaded.roles.size(),
                loaded.ranks.size(),
                loaded.loadouts.size(),
                rejected,
                unresolvedReferences);
    }

    private static ParsedId parseId(ResourceLocation resourceId) {
        String path = resourceId.getPath();
        int separator = path.indexOf('/');
        if (separator <= 0 || separator == path.length() - 1) {
            throw new JsonParseException("expected roles/<id>, ranks/<id>, or loadouts/<id> below " + ROOT_DIRECTORY);
        }
        Kind kind = switch (path.substring(0, separator)) {
            case "roles" -> Kind.ROLE;
            case "ranks" -> Kind.RANK;
            case "loadouts" -> Kind.LOADOUT;
            default -> throw new JsonParseException("unknown unit descriptor kind " + path.substring(0, separator));
        };
        return new ParsedId(kind, resourceId.withPath(path.substring(separator + 1)));
    }

    private static UnitRoleDescriptor parseRole(ResourceLocation key, JsonObject json) {
        ResourceLocation rank = parseKey(json, "default_rank", false);
        ResourceLocation loadout = parseKey(json, "default_loadout", false);
        int formationPriority = boundedInt(json, "formation_priority", 0, -32_768, 32_767);
        return new UnitRoleDescriptor(
                key,
                rank == null ? 0 : UnitDescriptorToken.rank(rank),
                loadout == null ? 0 : UnitDescriptorToken.loadout(loadout),
                formationPriority);
    }

    private static UnitRankDescriptor parseRank(ResourceLocation key, JsonObject json) {
        int commandTier = boundedInt(json, "command_tier", 0, 0, 255);
        int sortPriority = boundedInt(json, "sort_priority", 0, -32_768, 32_767);
        return new UnitRankDescriptor(key, commandTier, sortPriority);
    }

    private static UnitLoadoutDescriptor parseLoadout(
            ResourceLocation key, JsonObject json, RegistryItemResolver resolver) {
        ResourceLocation[][] candidates = new ResourceLocation[UnitLoadoutDescriptor.SLOT_COUNT][];
        candidates[0] = parseCandidates(json, "mainhand");
        candidates[1] = parseCandidates(json, "offhand");
        candidates[2] = parseCandidates(json, "head");
        candidates[3] = parseCandidates(json, "chest");
        candidates[4] = parseCandidates(json, "legs");
        candidates[5] = parseCandidates(json, "feet");
        return new UnitLoadoutDescriptor(key, candidates, resolver);
    }

    private static ResourceLocation[] parseCandidates(JsonObject json, String member) {
        if (!json.has(member)) {
            return NO_CANDIDATES;
        }
        JsonElement value = json.get(member);
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return new ResourceLocation[] {parseKey(value.getAsString(), member)};
        }
        if (!value.isJsonArray()) {
            throw new JsonParseException(member + " must be a registry key or an ordered array of registry keys");
        }
        JsonArray array = value.getAsJsonArray();
        if (array.size() > MAX_ITEM_FALLBACKS) {
            throw new JsonParseException(member + " has more than " + MAX_ITEM_FALLBACKS + " fallback candidates");
        }
        ResourceLocation[] candidates = new ResourceLocation[array.size()];
        for (int index = 0; index < candidates.length; index++) {
            JsonElement candidate = array.get(index);
            if (!candidate.isJsonPrimitive() || !candidate.getAsJsonPrimitive().isString()) {
                throw new JsonParseException(member + '[' + index + "] must be a registry key");
            }
            candidates[index] = parseKey(candidate.getAsString(), member + '[' + index + ']');
        }
        return candidates;
    }

    private static ResourceLocation parseKey(JsonObject json, String member, boolean required) {
        if (!json.has(member)) {
            if (required) {
                throw new JsonParseException("missing " + member);
            }
            return null;
        }
        return parseKey(GsonHelper.getAsString(json, member), member);
    }

    private static ResourceLocation parseKey(String raw, String member) {
        ResourceLocation key = ResourceLocation.tryParse(raw);
        if (key == null) {
            throw new JsonParseException(member + " is not a valid resource location");
        }
        return key;
    }

    private static int boundedInt(JsonObject json, String member, int fallback, int minimum, int maximum) {
        int value = GsonHelper.getAsInt(json, member, fallback);
        if (value < minimum || value > maximum) {
            throw new JsonParseException(member + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static int validateRoleReferences(Snapshot snapshot) {
        int missing = 0;
        TokenTable.Cursor<UnitRoleDescriptor> cursor = snapshot.roles.newCursor();
        while (cursor.advance()) {
            UnitRoleDescriptor role = cursor.value();
            if (role.defaultRankToken() != 0 && snapshot.ranks.get(role.defaultRankToken()) == null) {
                missing++;
                LOGGER.warn("Army role {} refers to missing rank token {}", role.key(), role.defaultRankToken());
            }
            if (role.defaultLoadoutToken() != 0 && snapshot.loadouts.get(role.defaultLoadoutToken()) == null) {
                missing++;
                LOGGER.warn("Army role {} refers to missing loadout token {}", role.key(), role.defaultLoadoutToken());
            }
        }
        return missing;
    }

    private enum Kind { ROLE, RANK, LOADOUT }
    private record ParsedId(Kind kind, ResourceLocation key) {}
    private record Snapshot(
            long generation,
            TokenTable<UnitRoleDescriptor> roles,
            TokenTable<UnitRankDescriptor> ranks,
            TokenTable<UnitLoadoutDescriptor> loadouts) {
        private static Snapshot empty() {
            return new Snapshot(0L, TokenTable.empty(), TokenTable.empty(), TokenTable.empty());
        }
    }

    /** Immutable primitive-key table; resource reload owns all construction allocations. */
    private static final class TokenTable<T> {
        private final int[] keys;
        private final Object[] values;
        private final int mask;
        private final int size;

        private TokenTable(int[] keys, Object[] values, int size) {
            this.keys = keys;
            this.values = values;
            this.mask = keys.length - 1;
            this.size = size;
        }

        static <T> TokenTable<T> empty() {
            return new TokenTable<>(new int[2], new Object[2], 0);
        }

        int size() { return size; }

        @SuppressWarnings("unchecked")
        T get(int token) {
            if (token == 0) {
                return null;
            }
            int position = mix(token) & mask;
            int key;
            while ((key = keys[position]) != 0) {
                if (key == token) {
                    return (T) values[position];
                }
                position = (position + 1) & mask;
            }
            return null;
        }

        Cursor<T> newCursor() { return new Cursor<>(this); }

        private static int mix(int value) {
            value ^= value >>> 16;
            value *= 0x7feb352d;
            value ^= value >>> 15;
            value *= 0x846ca68b;
            return value ^ value >>> 16;
        }

        private static final class Builder<T> {
            private final List<Entry<T>> entries = new ArrayList<>();

            void put(int token, ResourceLocation key, T value) {
                for (Entry<T> entry : entries) {
                    if (entry.token == token) {
                        throw new JsonParseException(
                                "descriptor token collision between " + entry.key + " and " + key);
                    }
                }
                entries.add(new Entry<>(token, key, value));
            }

            TokenTable<T> build() {
                int capacity = 2;
                while (capacity < entries.size() * 2) {
                    capacity <<= 1;
                }
                int[] keys = new int[capacity];
                Object[] values = new Object[capacity];
                int mask = capacity - 1;
                for (Entry<T> entry : entries) {
                    int position = mix(entry.token) & mask;
                    while (keys[position] != 0) {
                        position = (position + 1) & mask;
                    }
                    keys[position] = entry.token;
                    values[position] = entry.value;
                }
                return new TokenTable<>(keys, values, entries.size());
            }
        }

        private record Entry<T>(int token, ResourceLocation key, T value) {}

        private static final class Cursor<T> {
            private final TokenTable<T> owner;
            private int index = -1;

            private Cursor(TokenTable<T> owner) { this.owner = owner; }

            boolean advance() {
                while (++index < owner.keys.length) {
                    if (owner.keys[index] != 0) {
                        return true;
                    }
                }
                return false;
            }

            @SuppressWarnings("unchecked")
            T value() { return (T) owner.values[index]; }
        }
    }
}
