package ru.kaiserroman.millenairearmies.server.settlement;

import java.util.Arrays;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.village.Village;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;

/**
 * Server-thread-only runtime projection of persisted per-village development radii.
 *
 * <p>The registry is intentionally primitive and bounded. It exists only because Millenaire's
 * construction launcher reads the immutable radius from a shared VillageType. The mixin bridge
 * consults this projection for a concrete physical Village and otherwise keeps the upstream
 * radius.</p>
 */
public final class PlayerSettlementTerritoryRegistry {
    private static final int MAXIMUM = RealmSavedData.MAX_MEMBERS;
    private static long[] most = new long[16];
    private static long[] least = new long[16];
    private static int[] radii = new int[16];
    private static int size;

    private PlayerSettlementTerritoryRegistry() {}

    public static void clear() {
        Arrays.fill(most, 0, size, 0L);
        Arrays.fill(least, 0, size, 0L);
        Arrays.fill(radii, 0, size, 0);
        size = 0;
    }

    public static boolean put(UUID villageId, int radius) {
        if (villageId == null
                || radius < PlayerSettlementPolicy.MINIMUM_RADIUS
                || radius > PlayerSettlementPolicy.MAXIMUM_RADIUS) {
            throw new IllegalArgumentException("Invalid player settlement territory row");
        }
        long uuidMost = villageId.getMostSignificantBits();
        long uuidLeast = villageId.getLeastSignificantBits();
        for (int row = 0; row < size; row++) {
            if (most[row] == uuidMost && least[row] == uuidLeast) {
                radii[row] = radius;
                return true;
            }
        }
        if (size == MAXIMUM) return false;
        ensureCapacity(size + 1);
        most[size] = uuidMost;
        least[size] = uuidLeast;
        radii[size] = radius;
        size++;
        return true;
    }

    /** Returns a per-village radius capped to a fully loaded square, never below upstream behavior. */
    public static int radius(ServerLevel level, Village village, int upstreamRadius) {
        if (village == null || village.getId() == null || village.getId().uuid() == null
                || village.getCenter() == null) {
            return upstreamRadius;
        }
        int requested = requestedRadius(village.getId().uuid(), upstreamRadius);
        if (requested <= upstreamRadius) return upstreamRadius;
        int loaded = loadedRadius(level, village.getCenter(), upstreamRadius, requested);
        return loaded == 0 ? upstreamRadius : loaded;
    }

    public static boolean mayLaunchPlanned(
            ServerLevel level,
            Village village,
            BlockPos plannedPosition,
            int upstreamRadius,
            int footprintRadius) {
        if (level == null || village == null || plannedPosition == null
                || village.getId() == null || village.getId().uuid() == null
                || village.getCenter() == null) {
            return true;
        }
        int requested = requestedRadius(village.getId().uuid(), upstreamRadius);
        if (requested <= upstreamRadius) return true;
        BlockPos center = village.getCenter();
        long required = Math.max(
                Math.abs((long) plannedPosition.getX() - center.getX()),
                Math.abs((long) plannedPosition.getZ() - center.getZ()));
        required += Math.max(0, footprintRadius);
        if (required <= upstreamRadius) return true;
        if (required > requested) return false;
        int loaded = loadedRadius(level, center, upstreamRadius, requested);
        return loaded >= required;
    }

    /**
     * Finds the largest concentric square whose chunks are all already loaded.
     * Returns zero when even the required minimum is unavailable.
     */
    public static int loadedRadius(
            ServerLevel level,
            BlockPos center,
            int minimumRadius,
            int requestedRadius) {
        if (level == null) return 0;
        return loadedRadius(center, minimumRadius, requestedRadius, level::hasChunk);
    }

    static int loadedRadius(
            BlockPos center,
            int minimumRadius,
            int requestedRadius,
            ChunkAvailability availability) {
        if (center == null || availability == null
                || minimumRadius <= 0 || requestedRadius < minimumRadius) {
            return 0;
        }
        int requested = Math.min(PlayerSettlementPolicy.MAXIMUM_RADIUS, requestedRadius);
        int centerChunkX = center.getX() >> 4;
        int centerChunkZ = center.getZ() >> 4;
        int supported = -1;
        int maximumRing = (requested + 15) / 16 + 1;
        for (int ring = 0; ring <= maximumRing; ring++) {
            if (!ringLoaded(availability, centerChunkX, centerChunkZ, ring)) break;
            int minBlockX = (centerChunkX - ring) << 4;
            int minBlockZ = (centerChunkZ - ring) << 4;
            int maxBlockX = ((centerChunkX + ring + 1) << 4) - 1;
            int maxBlockZ = ((centerChunkZ + ring + 1) << 4) - 1;
            supported = Math.min(
                    Math.min(center.getX() - minBlockX, maxBlockX - center.getX()),
                    Math.min(center.getZ() - minBlockZ, maxBlockZ - center.getZ()));
            if (supported >= requested) return requested;
        }
        return supported >= minimumRadius ? Math.min(supported, requested) : 0;
    }

    public static int size() { return size; }

    private static int requestedRadius(UUID villageId, int upstreamRadius) {
        long uuidMost = villageId.getMostSignificantBits();
        long uuidLeast = villageId.getLeastSignificantBits();
        for (int row = 0; row < size; row++) {
            if (most[row] == uuidMost && least[row] == uuidLeast) {
                return Math.max(upstreamRadius, radii[row]);
            }
        }
        return upstreamRadius;
    }

    private static boolean ringLoaded(
            ChunkAvailability availability,
            int centerChunkX,
            int centerChunkZ,
            int ring) {
        if (ring == 0) return availability.loaded(centerChunkX, centerChunkZ);
        int minimumX = centerChunkX - ring;
        int maximumX = centerChunkX + ring;
        int minimumZ = centerChunkZ - ring;
        int maximumZ = centerChunkZ + ring;
        for (int chunkX = minimumX; chunkX <= maximumX; chunkX++) {
            if (!availability.loaded(chunkX, minimumZ)
                    || !availability.loaded(chunkX, maximumZ)) {
                return false;
            }
        }
        for (int chunkZ = minimumZ + 1; chunkZ < maximumZ; chunkZ++) {
            if (!availability.loaded(minimumX, chunkZ)
                    || !availability.loaded(maximumX, chunkZ)) {
                return false;
            }
        }
        return true;
    }

    @FunctionalInterface
    interface ChunkAvailability {
        boolean loaded(int chunkX, int chunkZ);
    }

    private static void ensureCapacity(int required) {
        if (required <= most.length) return;
        int capacity = Math.min(MAXIMUM, Math.max(required, most.length << 1));
        most = Arrays.copyOf(most, capacity);
        least = Arrays.copyOf(least, capacity);
        radii = Arrays.copyOf(radii, capacity);
    }
}
