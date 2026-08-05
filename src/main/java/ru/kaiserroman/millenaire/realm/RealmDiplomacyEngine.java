package ru.kaiserroman.millenaire.realm;

/** Deterministic high-level diplomacy. It never moves units or resolves combat. */
public final class RealmDiplomacyEngine {
    public static final int REASON_GRIEVANCE = 1;
    public static final int REASON_BORDER = 1 << 1;
    public static final int REASON_CLAIM = 1 << 2;
    public static final int REASON_TRADE = 1 << 3;
    public static final int REASON_COMMON_THREAT = 1 << 4;
    public static final int REASON_IDEOLOGY = 1 << 5;
    public static final int REASON_POWER = 1 << 6;
    public static final int REASON_EXHAUSTION = 1 << 7;

    public DiplomaticDecision evaluate(DiplomaticStatus current, DiplomacyInputs inputs) {
        if (current == null || inputs == null) {
            throw new NullPointerException("diplomacy input");
        }
        int aggression = aggression(inputs);
        int cooperation = cooperation(inputs);
        int reasons = reasons(inputs);

        if (current == DiplomaticStatus.WAR) {
            if (inputs.warExhaustion() >= 800 || inputs.trust() >= 780) {
                return new DiplomaticDecision(
                        DiplomaticStatus.TRUCE, WarGoal.NONE, aggression, cooperation, reasons);
            }
            return new DiplomaticDecision(
                    DiplomaticStatus.WAR, selectWarGoal(inputs), aggression, cooperation, reasons);
        }
        if (inputs.truceCycles() > 0) {
            return new DiplomaticDecision(
                    DiplomaticStatus.TRUCE, WarGoal.NONE, aggression, cooperation, reasons);
        }
        if (aggression >= 650 && inputs.powerAdvantage() >= 420) {
            return new DiplomaticDecision(
                    DiplomaticStatus.WAR, selectWarGoal(inputs), aggression, cooperation, reasons);
        }
        if (cooperation >= 700 && aggression < 360) {
            return new DiplomaticDecision(
                    DiplomaticStatus.ALLIANCE, WarGoal.NONE, aggression, cooperation, reasons);
        }
        if (aggression >= 430 || current == DiplomaticStatus.TENSION && aggression >= 350) {
            return new DiplomaticDecision(
                    DiplomaticStatus.TENSION, WarGoal.NONE, aggression, cooperation, reasons);
        }
        return new DiplomaticDecision(
                DiplomaticStatus.PEACE, WarGoal.NONE, aggression, cooperation, reasons);
    }

    public WarImpact battleImpact(BattleOutcome outcome) {
        if (outcome == null) throw new NullPointerException("outcome");
        int attackerExhaustion = Math.min(250, 20 + outcome.attackerLosses() * 4);
        int defenderExhaustion = Math.min(300, 25 + outcome.defenderLosses() * 4);
        int objective = (outcome.settlementOccupied() ? 90 : 0) + (outcome.capitalCaptured() ? 260 : 0);
        int battle = 45 + Math.min(180, Math.abs(outcome.defenderLosses() - outcome.attackerLosses()) * 3);
        int winnerScore = battle + objective;
        int loserScore = -Math.max(25, battle / 2 + objective / 2);
        int grievance = Math.min(300, 20 + objective / 2 + outcome.defenderLosses() * 2);
        return outcome.attackerVictory()
                ? new WarImpact(winnerScore, loserScore, attackerExhaustion, defenderExhaustion, grievance)
                : new WarImpact(loserScore, winnerScore, attackerExhaustion, defenderExhaustion, grievance);
    }

    private static int aggression(DiplomacyInputs inputs) {
        long value = 0L;
        value += inputs.grievances() * 28L;
        value += inputs.borderFriction() * 22L;
        value += inputs.claimStrength() * 20L;
        value += inputs.ideologicalDistance() * 10L;
        value += Math.max(0, inputs.powerAdvantage() - 500) * 30L;
        value += (1000 - inputs.trust()) * 10L;
        value -= inputs.tradeInterdependence() * 15L;
        value -= inputs.commonThreat() * 10L;
        value -= inputs.fear() * 5L;
        return clamp((int) (value / 100L));
    }

    private static int cooperation(DiplomacyInputs inputs) {
        long value = inputs.trust() * 35L
                + inputs.tradeInterdependence() * 30L
                + inputs.commonThreat() * 25L
                + (1000 - inputs.ideologicalDistance()) * 10L;
        return clamp((int) (value / 100L));
    }

    private static WarGoal selectWarGoal(DiplomacyInputs inputs) {
        if (inputs.claimStrength() >= 700) return WarGoal.BORDER_CLAIM;
        if (inputs.ideologicalDistance() >= 780 && inputs.trust() < 300) return WarGoal.LIBERATE;
        if (inputs.tradeInterdependence() >= 700 && inputs.borderFriction() >= 500) {
            return WarGoal.TRADE_ACCESS;
        }
        if (inputs.grievances() >= 760) return WarGoal.PUNITIVE;
        if (inputs.powerAdvantage() >= 720) return WarGoal.SUBJUGATE;
        return WarGoal.BORDER_CLAIM;
    }

    private static int reasons(DiplomacyInputs inputs) {
        int reasons = 0;
        if (inputs.grievances() >= 550) reasons |= REASON_GRIEVANCE;
        if (inputs.borderFriction() >= 550) reasons |= REASON_BORDER;
        if (inputs.claimStrength() >= 550) reasons |= REASON_CLAIM;
        if (inputs.tradeInterdependence() >= 600) reasons |= REASON_TRADE;
        if (inputs.commonThreat() >= 600) reasons |= REASON_COMMON_THREAT;
        if (inputs.ideologicalDistance() >= 600) reasons |= REASON_IDEOLOGY;
        if (inputs.powerAdvantage() >= 650 || inputs.powerAdvantage() <= 350) reasons |= REASON_POWER;
        if (inputs.warExhaustion() >= 650) reasons |= REASON_EXHAUSTION;
        return reasons;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(1000, value));
    }
}
