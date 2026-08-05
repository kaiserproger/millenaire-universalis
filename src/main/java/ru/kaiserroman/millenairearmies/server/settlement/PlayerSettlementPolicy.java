package ru.kaiserroman.millenairearmies.server.settlement;

/** Pure progression, territorial-growth and construction-unlock policy. */
public final class PlayerSettlementPolicy {
    public static final int MINIMUM_RADIUS = 96;
    public static final int MAXIMUM_RADIUS = 512;
    public static final long MINECRAFT_DAY_TICKS = 24_000L;

    private PlayerSettlementPolicy() {}

    public static Assessment assess(
            int baseRadius,
            int buildingCount,
            long population,
            long ageTicks,
            int capturedSettlements) {
        if (baseRadius < 0 || buildingCount < 0 || population < 0L
                || ageTicks < 0L || capturedSettlements < 0) {
            throw new IllegalArgumentException("Negative player settlement progression input");
        }
        int initial = clamp(Math.max(baseRadius, MINIMUM_RADIUS), MINIMUM_RADIUS, MAXIMUM_RADIUS);
        long days = ageTicks / MINECRAFT_DAY_TICKS;
        long rawRadius = (long) initial
                + Math.min(192L, days * 4L)
                + Math.min(160L, (long) buildingCount * 5L)
                + Math.min(64L, (long) capturedSettlements * 16L);
        int radius = clamp(rawRadius, initial, MAXIMUM_RADIUS);

        PlayerSettlementTier tier = PlayerSettlementTier.HAMLET;
        if (buildingCount >= 6 || population >= 40L || radius >= 128) {
            tier = PlayerSettlementTier.VILLAGE;
        }
        if ((buildingCount >= 12 && population >= 90L) || radius >= 208 || capturedSettlements >= 1) {
            tier = PlayerSettlementTier.TOWN;
        }
        if (buildingCount >= 18 && population >= 180L && days >= 5L && radius >= 256) {
            tier = PlayerSettlementTier.CITY_STATE;
        }
        int development = clamp(
                (long) buildingCount * 22L + population / 2L + days * 3L
                        + (long) capturedSettlements * 120L,
                0,
                1000);
        return new Assessment(tier, radius, development, buildingCount, population, days);
    }

    public static boolean allowsExtendedPlan(
            PlayerSettlementTier tier,
            String category,
            boolean townHall,
            boolean subBuilding,
            boolean wallSegment,
            boolean gift,
            int price) {
        if (tier == null || townHall || subBuilding || wallSegment || gift || price < 0) return false;
        String normalized = category == null ? "" : category.toLowerCase(java.util.Locale.ROOT);
        boolean military = normalized.contains("military") || normalized.contains("fort")
                || normalized.contains("barrack") || normalized.contains("armour")
                || normalized.contains("guard") || normalized.contains("wall");
        boolean monumental = normalized.contains("marvel") || normalized.contains("monument")
                || normalized.contains("palace") || normalized.contains("religious");
        return switch (tier) {
            case HAMLET -> !military && !monumental && price <= 2048;
            case VILLAGE -> !military && price <= 8192;
            case TOWN -> !normalized.contains("capital");
            case CITY_STATE -> true;
        };
    }

    public static int conquestDistance(int territoryRadius) {
        return clamp((long) Math.max(territoryRadius, MINIMUM_RADIUS) * 4L, 512, 2048);
    }

    private static int clamp(long value, int minimum, int maximum) {
        return (int) Math.max(minimum, Math.min(maximum, value));
    }

    public record Assessment(
            PlayerSettlementTier tier,
            int territoryRadius,
            int development,
            int buildingCount,
            long population,
            long ageDays) {
        public Assessment {
            if (tier == null || territoryRadius < MINIMUM_RADIUS || territoryRadius > MAXIMUM_RADIUS
                    || development < 0 || development > 1000 || buildingCount < 0
                    || population < 0L || ageDays < 0L) {
                throw new IllegalArgumentException("Invalid player settlement assessment");
            }
        }
    }
}
