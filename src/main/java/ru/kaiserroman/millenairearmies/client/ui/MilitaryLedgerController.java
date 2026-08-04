package ru.kaiserroman.millenairearmies.client.ui;

import net.minecraft.network.chat.Component;
import ru.kaiserroman.millenairearmies.client.ArmyClientMirror;
import ru.kaiserroman.millenairearmies.network.ArmiesProtocol;
import ru.kaiserroman.millenairearmies.persistence.RealmGovernanceSavedData;

/**
 * Client-side hand-off between the war council hub and the military ledgers. It owns the selection
 * that must survive navigation (target warband, controlled settlement, selected residents) and
 * forwards validated intents to the network mirror exactly like the field banner does.
 */
final class MilitaryLedgerController {
    static final Component COMMAND_SENT = Component.translatable("gui.millenaire_armies.command.sent");
    static final Component COMMAND_UNAVAILABLE = Component.translatable("gui.millenaire_armies.command.unavailable");

    static int selectedArmyId = -1;
    static boolean hasSelectedArmy;
    static long settlementMost;
    static long settlementLeast;
    static boolean hasSettlement;
    static final long[] selectedRecruitBits = new long[ArmiesProtocol.MAX_RECRUITS_PER_INTENT * 2];
    static int selectedRecruitCount;
    static int recruitRow = -1;
    static long lastAcknowledgementId;
    static Component feedback;
    static long feedbackUntil;

    private MilitaryLedgerController() {
    }

    static void reset() {
        selectedArmyId = -1;
        hasSelectedArmy = false;
        settlementMost = 0L;
        settlementLeast = 0L;
        hasSettlement = false;
        selectedRecruitCount = 0;
        recruitRow = -1;
        lastAcknowledgementId = 0L;
        feedback = null;
        feedbackUntil = 0L;
    }

    static void seedArmy(int preferredArmyId) {
        if (preferredArmyId < 0) {
            return;
        }
        selectedArmyId = preferredArmyId;
        hasSelectedArmy = true;
    }

    /** Re-anchors selection against the current snapshot; never allocates. */
    static void validate(ArmyClientMirror mirror) {
        if (mirror.isReady() && (!hasSelectedArmy || findArmyIndex(mirror, selectedArmyId) < 0)) {
            hasSelectedArmy = mirror.armyCount() > 0;
            selectedArmyId = hasSelectedArmy ? mirror.armyId(0) : -1;
        }
        if (mirror.isReady() && findSettlementIndex(mirror) < 0) {
            hasSettlement = mirror.settlementCount() > 0;
            if (hasSettlement) {
                settlementMost = mirror.settlementUuidMost(0);
                settlementLeast = mirror.settlementUuidLeast(0);
            }
        }
        if (mirror.isReady()) {
            compactRecruits(mirror);
            if (recruitRow < 0 || recruitRow >= mirror.recruitCount()
                    || !recruitInSettlement(mirror, recruitRow)) {
                recruitRow = filteredRecruitCount(mirror) == 0 ? -1 : filteredRecruitRow(mirror, 0);
            }
        }
    }

    static int findArmyIndex(ArmyClientMirror mirror, int id) {
        for (int i = 0, count = mirror.armyCount(); i < count; i++) {
            if (mirror.armyId(i) == id) {
                return i;
            }
        }
        return -1;
    }

    static int findSettlementIndex(ArmyClientMirror mirror) {
        if (!hasSettlement) {
            return -1;
        }
        for (int row = 0; row < mirror.settlementCount(); row++) {
            if (mirror.settlementUuidMost(row) == settlementMost
                    && mirror.settlementUuidLeast(row) == settlementLeast) {
                return row;
            }
        }
        return -1;
    }

    static String selectedArmyName(ArmyClientMirror mirror) {
        int row = findArmyIndex(mirror, selectedArmyId);
        return row < 0 ? "" : mirror.armyName(row);
    }

    static void selectArmy(int id) {
        selectedArmyId = id;
        hasSelectedArmy = true;
    }

    static void selectSettlement(ArmyClientMirror mirror, int row) {
        settlementMost = mirror.settlementUuidMost(row);
        settlementLeast = mirror.settlementUuidLeast(row);
        hasSettlement = true;
        recruitRow = filteredRecruitCount(mirror) == 0 ? -1 : filteredRecruitRow(mirror, 0);
        selectedRecruitCount = 0;
    }

    /** Advances the recruitment target to the next known warband, if any. */
    static boolean cycleTargetArmy(ArmyClientMirror mirror) {
        if (mirror.armyCount() == 0) {
            return false;
        }
        int current = findArmyIndex(mirror, selectedArmyId);
        int next = current < 0 ? 0 : (current + 1) % mirror.armyCount();
        selectedArmyId = mirror.armyId(next);
        hasSelectedArmy = true;
        return true;
    }

    static int filteredRecruitCount(ArmyClientMirror mirror) {
        int count = 0;
        for (int row = 0; row < mirror.recruitCount(); row++) {
            if (recruitInSettlement(mirror, row)) {
                count++;
            }
        }
        return count;
    }

    static int filteredRecruitRow(ArmyClientMirror mirror, int ordinal) {
        for (int row = 0; row < mirror.recruitCount(); row++) {
            if (recruitInSettlement(mirror, row) && ordinal-- == 0) {
                return row;
            }
        }
        return -1;
    }

    static int filteredRecruitOrdinal(ArmyClientMirror mirror, int requestedRow) {
        int ordinal = 0;
        for (int row = 0; row < mirror.recruitCount(); row++) {
            if (!recruitInSettlement(mirror, row)) {
                continue;
            }
            if (row == requestedRow) {
                return ordinal;
            }
            ordinal++;
        }
        return -1;
    }

    static boolean recruitInSettlement(ArmyClientMirror mirror, int row) {
        return hasSettlement
                && mirror.recruitVillageMost(row) == settlementMost
                && mirror.recruitVillageLeast(row) == settlementLeast;
    }

    static boolean recruitSelected(ArmyClientMirror mirror, int row) {
        long most = mirror.recruitUuidMost(row);
        long least = mirror.recruitUuidLeast(row);
        for (int index = 0; index < selectedRecruitCount; index++) {
            if (selectedRecruitBits[index * 2] == most && selectedRecruitBits[index * 2 + 1] == least) {
                return true;
            }
        }
        return false;
    }

    /** Toggles a resident into the selection; returns a limit-feedback component or null. */
    static Component toggleRecruit(ArmyClientMirror mirror, int row) {
        if (mirror.recruitOptionCode(row) != ArmiesProtocol.RECRUIT_OPTION_ENLIST) {
            return Component.translatable("gui.millenaire_armies.action.recruit_unavailable");
        }
        long most = mirror.recruitUuidMost(row);
        long least = mirror.recruitUuidLeast(row);
        for (int index = 0; index < selectedRecruitCount; index++) {
            if (selectedRecruitBits[index * 2] == most && selectedRecruitBits[index * 2 + 1] == least) {
                int tail = selectedRecruitCount - index - 1;
                if (tail > 0) {
                    System.arraycopy(selectedRecruitBits, (index + 1) * 2,
                            selectedRecruitBits, index * 2, tail * 2);
                }
                selectedRecruitCount--;
                return null;
            }
        }
        if (selectedRecruitCount < ArmiesProtocol.MAX_RECRUITS_PER_INTENT) {
            selectedRecruitBits[selectedRecruitCount * 2] = most;
            selectedRecruitBits[selectedRecruitCount * 2 + 1] = least;
            selectedRecruitCount++;
            return null;
        }
        return Component.translatable(
                "gui.millenaire_armies.recruit.selection_limit",
                ArmiesProtocol.MAX_RECRUITS_PER_INTENT);
    }

    static long[] recruitPayload() {
        long[] result = new long[selectedRecruitCount * 2];
        System.arraycopy(selectedRecruitBits, 0, result, 0, result.length);
        return result;
    }

    private static void compactRecruits(ArmyClientMirror mirror) {
        int write = 0;
        for (int selected = 0; selected < selectedRecruitCount; selected++) {
            long most = selectedRecruitBits[selected * 2];
            long least = selectedRecruitBits[selected * 2 + 1];
            boolean present = false;
            for (int row = 0; row < mirror.recruitCount(); row++) {
                if (recruitInSettlement(mirror, row)
                        && mirror.recruitUuidMost(row) == most
                        && mirror.recruitUuidLeast(row) == least) {
                    present = true;
                    break;
                }
            }
            if (present) {
                selectedRecruitBits[write * 2] = most;
                selectedRecruitBits[write * 2 + 1] = least;
                write++;
            }
        }
        selectedRecruitCount = write;
    }

    static boolean createArmy(ArmyClientMirror mirror) {
        int settlement = findSettlementIndex(mirror);
        boolean requested = settlement >= 0 && mirror.settlementControlled(settlement)
                && mirror.requestCreateArmy(settlementMost, settlementLeast);
        showFeedback(requested ? COMMAND_SENT : COMMAND_UNAVAILABLE);
        return requested;
    }

    static boolean foundRealm(ArmyClientMirror mirror) {
        boolean requested = hasSettlement && mirror.requestFoundRealm(settlementMost, settlementLeast);
        showFeedback(requested ? COMMAND_SENT : COMMAND_UNAVAILABLE);
        return requested;
    }

    static boolean adjustTax(ArmyClientMirror mirror, int delta) {
        int next = Math.max(0, Math.min(25, mirror.realmTaxRate() + delta));
        boolean requested = next != mirror.realmTaxRate() && mirror.requestSetRealmTax(next);
        showFeedback(requested ? COMMAND_SENT : COMMAND_UNAVAILABLE);
        return requested;
    }

    static boolean recruitSelected(ArmyClientMirror mirror) {
        boolean requested = hasSelectedArmy && hasSettlement && selectedRecruitCount > 0
                && mirror.requestRecruitUnits(
                        selectedArmyId,
                        settlementMost,
                        settlementLeast,
                        selectedRecruitCount,
                        recruitPayload());
        showFeedback(requested ? COMMAND_SENT : COMMAND_UNAVAILABLE);
        return requested;
    }

    static boolean hireFocused(ArmyClientMirror mirror) {
        int row = recruitRow;
        if (row < 0 || row >= mirror.recruitCount()) {
            showFeedback(COMMAND_UNAVAILABLE);
            return false;
        }
        int option = mirror.recruitOptionCode(row);
        boolean requested = (option == ArmiesProtocol.RECRUIT_OPTION_HIRE
                        || option == ArmiesProtocol.RECRUIT_OPTION_ASSIGN_HIRED)
                && mirror.requestHireRecruit(
                        mirror.recruitUuidMost(row), mirror.recruitUuidLeast(row));
        showFeedback(requested ? COMMAND_SENT : COMMAND_UNAVAILABLE);
        return requested;
    }

    static boolean issueOrder(ArmyClientMirror mirror, int typeCode) {
        boolean requested = hasSelectedArmy && mirror.requestIssueOrder(selectedArmyId, typeCode);
        showFeedback(requested ? COMMAND_SENT : COMMAND_UNAVAILABLE);
        return requested;
    }

    static boolean cycleFormation(ArmyClientMirror mirror) {
        int row = findArmyIndex(mirror, selectedArmyId);
        int current = row < 0 ? -1 : mirror.armyFormationCode(row);
        int next = current < 0 || current >= 4 ? 0 : current + 1;
        return setFormation(mirror, next);
    }

    static boolean setFormation(ArmyClientMirror mirror, int formationCode) {
        int row = findArmyIndex(mirror, selectedArmyId);
        boolean requested = row >= 0
                && formationCode >= 0
                && formationCode <= 4
                && mirror.requestSetFormation(selectedArmyId, formationCode);
        showFeedback(requested ? COMMAND_SENT : COMMAND_UNAVAILABLE);
        return requested;
    }

    static boolean setGarrison(ArmyClientMirror mirror, int defaultRadius) {
        int row = findArmyIndex(mirror, selectedArmyId);
        int radius = row >= 0 && mirror.armyHasGarrison(row)
                ? mirror.armyGarrisonRadius(row)
                : defaultRadius;
        boolean requested = hasSelectedArmy
                && mirror.requestSetGarrison(selectedArmyId, 0L, 0L, radius);
        showFeedback(requested ? COMMAND_SENT : COMMAND_UNAVAILABLE);
        return requested;
    }

    static boolean clearGarrison(ArmyClientMirror mirror) {
        boolean requested = hasSelectedArmy && mirror.requestClearGarrison(selectedArmyId);
        showFeedback(requested ? COMMAND_SENT : COMMAND_UNAVAILABLE);
        return requested;
    }

    /** Mirrors the server acknowledgement and returns feedback to show, or null when idle. */
    static Component pollAcknowledgement(ArmyClientMirror mirror) {
        int actionId = mirror.acknowledgedActionId();
        if (actionId <= lastAcknowledgementId) {
            return null;
        }
        lastAcknowledgementId = actionId;
        int result = mirror.acknowledgedResult();
        byte action = mirror.acknowledgedAction();
        if (result == ArmiesProtocol.RESULT_ACCEPTED
                && (action == ArmiesProtocol.ACTION_CREATE_ARMY
                        || action == ArmiesProtocol.ACTION_HIRE_RECRUIT)
                && mirror.armyCount() > 0) {
            selectedArmyId = mirror.armyId(mirror.armyCount() - 1);
            hasSelectedArmy = true;
        }
        if (result == ArmiesProtocol.RESULT_ACCEPTED && action == ArmiesProtocol.ACTION_RECRUIT) {
            selectedRecruitCount = 0;
        }
        Component message;
        if (result == ArmiesProtocol.RESULT_ACCEPTED
                && action == ArmiesProtocol.ACTION_HIRE_RECRUIT) {
            message = Component.translatable("gui.millenaire_armies.result.hired");
        } else if (result == ArmiesProtocol.RESULT_ACCEPTED
                && action == ArmiesProtocol.ACTION_CREATE_ARMY) {
            message = Component.translatable("gui.millenaire_armies.result.created");
        } else if (result == ArmiesProtocol.RESULT_ACCEPTED
                && action == ArmiesProtocol.ACTION_RECRUIT) {
            message = Component.translatable(
                    "gui.millenaire_armies.result.recruited", mirror.acknowledgedAffected());
        } else if (result == ArmiesProtocol.RESULT_ACCEPTED
                && action == ArmiesProtocol.ACTION_FOUND_REALM) {
            message = Component.translatable("gui.millenaire_armies.result.realm_founded");
        } else if (result == ArmiesProtocol.RESULT_ACCEPTED
                && action == ArmiesProtocol.ACTION_SET_REALM_TAX) {
            message = Component.translatable("gui.millenaire_armies.result.tax_updated");
        } else if (result == ArmiesProtocol.RESULT_PERMISSION_DENIED
                && action == ArmiesProtocol.ACTION_FOUND_REALM) {
            message = Component.translatable("gui.millenaire_armies.result.realm_permission_denied");
        } else {
            message = switch (result) {
                case ArmiesProtocol.RESULT_ACCEPTED -> Component.translatable(
                        "gui.millenaire_armies.result.accepted", mirror.acknowledgedAffected());
                case ArmiesProtocol.RESULT_STALE -> Component.translatable("gui.millenaire_armies.result.stale");
                case ArmiesProtocol.RESULT_PERMISSION_DENIED -> Component.translatable(
                        "gui.millenaire_armies.result.permission_denied");
                case ArmiesProtocol.RESULT_NOT_FOUND -> Component.translatable("gui.millenaire_armies.result.not_found");
                case ArmiesProtocol.RESULT_LIMIT_REACHED -> Component.translatable(
                        "gui.millenaire_armies.result.limit_reached");
                case ArmiesProtocol.RESULT_PARTIAL -> Component.translatable(
                        "gui.millenaire_armies.result.partial", mirror.acknowledgedAffected());
                default -> Component.translatable("gui.millenaire_armies.result.invalid");
            };
        }
        showFeedback(message);
        return message;
    }

    static void showFeedback(Component message) {
        feedback = message;
        feedbackUntil = System.currentTimeMillis() + 1800L;
    }

    static Component feedback() {
        return feedback;
    }

    static long feedbackUntil() {
        return feedbackUntil;
    }

    static boolean realmHead(ArmyClientMirror mirror) {
        return mirror.realmRoleCode() == RealmGovernanceSavedData.ROLE_HEAD;
    }
}
