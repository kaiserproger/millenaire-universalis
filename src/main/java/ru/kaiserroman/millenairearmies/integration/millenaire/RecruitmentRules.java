package ru.kaiserroman.millenairearmies.integration.millenaire;

import org.millenaire.ReputationConstants;

/** Pure fail-closed rules shared by the Millenaire adapter and deterministic self-tests. */
final class RecruitmentRules {
    private RecruitmentRules() {}

    static long settlementAccess(boolean playerControlled, boolean controlledBy, int combinedReputation) {
        if (!playerControlled || !controlledBy) {
            return MillenaireRecruitmentService.SETTLEMENT_NOT_CONTROLLED;
        }
        return combinedReputation < ReputationConstants.HIRE_REPUTATION_THRESHOLD
                ? MillenaireRecruitmentService.REPUTATION_TOO_LOW
                : 0L;
    }

    static long candidate(
            boolean loaded,
            boolean alive,
            boolean adult,
            boolean military,
            boolean busy,
            boolean alreadyRecruited) {
        if (!loaded) {
            return MillenaireRecruitmentService.VILLAGER_NOT_LOADED;
        }
        if (!alive || !adult) {
            return MillenaireRecruitmentService.VILLAGER_UNAVAILABLE;
        }
        if (!military) {
            return MillenaireRecruitmentService.NOT_MILITARY;
        }
        if (busy) {
            return MillenaireRecruitmentService.VILLAGER_BUSY;
        }
        return alreadyRecruited ? MillenaireRecruitmentService.ALREADY_RECRUITED : 0L;
    }
}
