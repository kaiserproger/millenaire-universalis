package ru.kaiserroman.millenairearmies.integration.millenaire;

import java.util.Locale;
import org.millenaire.building.BuildingPlanSet;
import org.millenaire.culture.VillageType;
import ru.kaiserroman.millenaire.realm.RealmStatePriority;

/** Pure semantic scoring from a Realm programme to culture-specific Millenaire building plans. */
public final class MillenaireRealmBuildingPolicy {
    public int score(
            RealmStatePriority priority,
            BuildingPlanSet plan,
            VillageType.LayoutSlot slot) {
        if (priority == null || plan == null) return Integer.MIN_VALUE;
        String text = text(plan, slot);
        int score = Math.max(0, slot == null ? 0 : slot.priority());
        score += plan.isTownHall() ? 80 : 0;
        score += switch (priority) {
            case FOOD_SECURITY -> keywordScore(text, 220,
                    "farm", "field", "paddy", "orchard", "fish", "bakery", "kitchen",
                    "cattle", "cow", "sheep", "chicken", "pig", "mill", "food", "granary",
                    "irrig", "well");
            case TRADE -> keywordScore(text, 220,
                    "market", "shop", "merchant", "trade", "inn", "warehouse", "caravan",
                    "port", "dock", "stall", "guild");
            case INDUSTRY -> keywordScore(text, 220,
                    "forge", "smith", "mine", "quarry", "carpenter", "lumber", "weaver",
                    "brick", "workshop", "tool", "kiln", "armoury", "glass", "craft");
            case FORTIFICATION, MILITARY_MOBILIZATION -> keywordScore(text, 240,
                    "fort", "wall", "guard", "watch", "barrack", "armoury", "archery",
                    "border", "tower", "castle", "military", "gate");
            case RECOVERY -> keywordScore(text, 210,
                    "house", "residence", "well", "bath", "hospital", "healer", "farm",
                    "road", "bridge", "worker", "fountain", "water");
            case CONSOLIDATION -> keywordScore(text, 210,
                    "townhall", "council", "archive", "school", "temple", "church", "shrine",
                    "mosque", "palace", "manor", "administr", "courthouse", "guild");
            case EXPANSION -> keywordScore(text, 210,
                    "border", "road", "bridge", "market", "warehouse", "fort", "guard",
                    "outpost", "caravan", "inn");
            case CIVIC_GROWTH -> 140 + keywordScore(text, 80,
                    "house", "market", "school", "well", "temple", "church", "shrine",
                    "mosque", "bath", "fountain", "garden", "hall");
            case AUSTERITY, NONE -> Integer.MIN_VALUE / 2;
        };
        return score;
    }

    public boolean permitsUpgrade(RealmStatePriority priority, BuildingPlanSet plan) {
        if (priority == null || priority == RealmStatePriority.NONE) return true;
        if (!priority.permitsConstruction()) return false;
        if (priority == RealmStatePriority.CIVIC_GROWTH) return true;
        if (plan == null) return priority == RealmStatePriority.RECOVERY;
        return plan.isTownHall() || score(priority, plan, null) > 80;
    }

    public long investmentCost(
            RealmStatePriority priority,
            BuildingPlanSet plan,
            int investmentPermille,
            long baseCost) {
        if (priority == null || plan == null || investmentPermille <= 0 || baseCost <= 0L) return 0L;
        long planCost = Math.max(0L, plan.price() / 16L);
        long nominal = saturatedAdd(baseCost, planCost);
        long scaled = nominal > Long.MAX_VALUE / investmentPermille
                ? Long.MAX_VALUE
                : nominal * investmentPermille / 1000L;
        return Math.max(1L, scaled);
    }

    private static int keywordScore(String text, int each, String... keywords) {
        int score = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                score = score > Integer.MAX_VALUE - each ? Integer.MAX_VALUE : score + each;
            }
        }
        return score == 0 ? -300 : score;
    }

    private static String text(BuildingPlanSet plan, VillageType.LayoutSlot slot) {
        StringBuilder text = new StringBuilder(160)
                .append(plan.id()).append(' ')
                .append(plan.buildingId()).append(' ')
                .append(plan.category()).append(' ')
                .append(plan.nativeName()).append(' ')
                .append(plan.tags()).append(' ')
                .append(plan.travelBookCategory());
        if (slot != null) text.append(' ').append(slot.role());
        return text.toString().toLowerCase(Locale.ROOT);
    }

    private static long saturatedAdd(long left, long right) {
        if (right <= 0L) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
