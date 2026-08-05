package ru.kaiserroman.millenaire.realm;

public record DiplomaticDecision(
        DiplomaticStatus status,
        WarGoal warGoal,
        int aggressionPressure,
        int cooperationPressure,
        int reasonMask) {

    public DiplomaticDecision {
        if (status == null || warGoal == null) {
            throw new NullPointerException("diplomatic decision");
        }
        if (status != DiplomaticStatus.WAR && warGoal != WarGoal.NONE) {
            throw new IllegalArgumentException("Only war may carry an offensive war goal");
        }
    }
}
