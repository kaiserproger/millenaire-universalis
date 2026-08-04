package ru.kaiserroman.millenairearmies.integration.millenaire;

import org.millenaire.village.VillageRelations;
import ru.kaiserroman.millenaire.realm.DiplomaticStatus;
import ru.kaiserroman.millenaire.realm.RealmHistoricalPhase;
import ru.kaiserroman.millenaire.simulation.SettlementStatus;

/** Pure bounded decisions shared by the physical Millenaire projection and its self-test. */
public final class MillenairePhysicalProjectionPolicy {
    public int targetPhysicalStock(
            int commodity,
            long virtualStock,
            SettlementStatus settlementStatus,
            RealmHistoricalPhase realmPhase,
            int configuredCap) {
        if (settlementStatus == null) throw new NullPointerException("settlementStatus");
        int base = SimulationCommodityBridge.physicalTarget(commodity, virtualStock, configuredCap);
        long adjusted = (long) base * settlementMultiplier(settlementStatus) / 1000L;
        adjusted = adjusted * phaseMultiplier(realmPhase) / 1000L;
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, adjusted));
    }

    public int boundedDelta(int current, int target, int maximumAbsoluteDelta) {
        if (current < 0 || target < 0 || maximumAbsoluteDelta <= 0) {
            throw new IllegalArgumentException("Invalid physical stock delta");
        }
        long difference = (long) target - current;
        if (difference > maximumAbsoluteDelta) return maximumAbsoluteDelta;
        if (difference < -maximumAbsoluteDelta) return -maximumAbsoluteDelta;
        return (int) difference;
    }

    public int relationValue(DiplomaticStatus status, boolean sameRealm) {
        if (sameRealm) return VillageRelations.EXCELLENT;
        if (status == null) return VillageRelations.NEUTRAL;
        return switch (status) {
            case ALLIANCE -> VillageRelations.VERY_GOOD;
            case PEACE -> VillageRelations.FAIR;
            case TRUCE -> VillageRelations.NEUTRAL;
            case TENSION -> VillageRelations.VERY_BAD;
            case WAR -> VillageRelations.OPEN_CONFLICT;
        };
    }

    public boolean shouldPlanNativeRaid(
            DiplomaticStatus status,
            RealmHistoricalPhase attackerPhase,
            boolean attackerHasRaid,
            boolean attackerUnderAttack) {
        return status == DiplomaticStatus.WAR
                && attackerPhase != RealmHistoricalPhase.DECADENT
                && attackerPhase != RealmHistoricalPhase.COLLAPSING
                && !attackerHasRaid
                && !attackerUnderAttack;
    }

    public int constructionPauseYears(
            SettlementStatus settlementStatus,
            RealmHistoricalPhase realmPhase,
            int declineYears,
            int ruinYears) {
        if (settlementStatus == null || declineYears <= 0 || ruinYears < declineYears) {
            throw new IllegalArgumentException("Invalid construction pause input");
        }
        if (settlementStatus == SettlementStatus.RUINED
                || settlementStatus == SettlementStatus.ABANDONED) {
            return ruinYears;
        }
        if (settlementStatus == SettlementStatus.DECLINING) return declineYears;
        if (realmPhase == RealmHistoricalPhase.COLLAPSING) return Math.max(declineYears, ruinYears / 2);
        if (realmPhase == RealmHistoricalPhase.DECADENT) return declineYears;
        if (realmPhase == RealmHistoricalPhase.STRAINED) return Math.max(1, declineYears / 2);
        return 0;
    }

    public boolean upgradesAllowed(
            SettlementStatus settlementStatus,
            RealmHistoricalPhase realmPhase) {
        if (settlementStatus != SettlementStatus.ACTIVE) return false;
        return realmPhase != RealmHistoricalPhase.DECADENT
                && realmPhase != RealmHistoricalPhase.COLLAPSING;
    }

    private static int settlementMultiplier(SettlementStatus status) {
        return switch (status) {
            case ACTIVE -> 1000;
            case DECLINING -> 650;
            case ABANDONED -> 250;
            case RUINED -> 0;
        };
    }

    private static int phaseMultiplier(RealmHistoricalPhase phase) {
        if (phase == null) return 1000;
        return switch (phase) {
            case ASCENDANT -> 1100;
            case STABLE -> 1000;
            case STRAINED -> 850;
            case DECADENT -> 650;
            case COLLAPSING -> 350;
            case RESTORING -> 800;
        };
    }
}
