package ru.kaiserroman.millenaire.realm;

/**
 * Narrow boundary consumed by Millenaire Armies. Realm owns authority, hostility and war purpose;
 * Armies owns formations, movement, battle and siege execution.
 */
public interface RealmMilitaryPolicy {
    boolean isAtWar(long sourceRealmId, long targetRealmId);

    boolean mayCommandSettlement(long actorId, long settlementId);

    WarGoal warGoal(long sourceRealmId, long targetRealmId);

    void recordBattleOutcome(BattleOutcome outcome);
}
