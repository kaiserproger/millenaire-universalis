package ru.kaiserroman.millenairearmies.integration.millenaire;

import org.millenaire.village.VillageRelations;
import ru.kaiserroman.millenaire.realm.DiplomaticStatus;
import ru.kaiserroman.millenaire.realm.RealmHistoricalPhase;
import ru.kaiserroman.millenaire.simulation.SettlementStatus;

/** Deterministic proof that Realm/Simulation states drive bounded physical Millenaire outcomes. */
public final class MillenairePhysicalProjectionPolicySelfTest {
    private MillenairePhysicalProjectionPolicySelfTest() {}

    public static void main(String[] args) {
        MillenairePhysicalProjectionPolicy policy = new MillenairePhysicalProjectionPolicy();

        int stableFood = policy.targetPhysicalStock(
                MillenaireWorldSimulationBridge.FOOD,
                1_000L,
                SettlementStatus.ACTIVE,
                RealmHistoricalPhase.STABLE,
                4_096);
        int ascendantFood = policy.targetPhysicalStock(
                MillenaireWorldSimulationBridge.FOOD,
                1_000L,
                SettlementStatus.ACTIVE,
                RealmHistoricalPhase.ASCENDANT,
                4_096);
        int collapsingFood = policy.targetPhysicalStock(
                MillenaireWorldSimulationBridge.FOOD,
                1_000L,
                SettlementStatus.DECLINING,
                RealmHistoricalPhase.COLLAPSING,
                4_096);
        int ruinedFood = policy.targetPhysicalStock(
                MillenaireWorldSimulationBridge.FOOD,
                1_000L,
                SettlementStatus.RUINED,
                RealmHistoricalPhase.COLLAPSING,
                4_096);
        check(stableFood == 1_000, "stable stock maps directly");
        check(ascendantFood > stableFood, "ascendant Realm materialises a surplus");
        check(collapsingFood < stableFood, "collapse drains physical stock");
        check(ruinedFood == 0, "ruined settlement has no projected stock");
        check(policy.boundedDelta(0, 500, 64) == 64, "physical additions are bounded");
        check(policy.boundedDelta(500, 0, 64) == -64, "physical removals are bounded");

        check(policy.relationValue(DiplomaticStatus.WAR, false)
                        == VillageRelations.OPEN_CONFLICT,
                "Realm war becomes native Millenaire hostility");
        check(policy.relationValue(DiplomaticStatus.ALLIANCE, false)
                        == VillageRelations.VERY_GOOD,
                "Realm alliance becomes native Millenaire friendship");
        check(policy.relationValue(DiplomaticStatus.WAR, true)
                        == VillageRelations.EXCELLENT,
                "members of one Realm remain internally aligned");

        check(policy.shouldPlanNativeRaid(
                        DiplomaticStatus.WAR,
                        RealmHistoricalPhase.ASCENDANT,
                        false,
                        false),
                "strong Realm war can create a native raid");
        check(!policy.shouldPlanNativeRaid(
                        DiplomaticStatus.WAR,
                        RealmHistoricalPhase.COLLAPSING,
                        false,
                        false),
                "collapsing Realm cannot launch a new native raid");
        check(policy.constructionPauseYears(
                        SettlementStatus.DECLINING,
                        RealmHistoricalPhase.STRAINED,
                        3,
                        20) == 3,
                "decline pauses real construction");
        check(!policy.upgradesAllowed(
                        SettlementStatus.ACTIVE,
                        RealmHistoricalPhase.DECADENT),
                "decadence disables real building upgrades");
        check(policy.upgradesAllowed(
                        SettlementStatus.ACTIVE,
                        RealmHistoricalPhase.RESTORING),
                "restoration re-enables physical development");

        System.out.println("Millenaire physical projection policy self-test passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
