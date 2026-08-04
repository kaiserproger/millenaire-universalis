package ru.kaiserroman.millenairearmies.server.integration;

/** Stable-id mapping supplied by Realm; Armies never reads Realm registries directly. */
public interface ArmyRealmIdentityResolver {
    long realmForArmy(int armyHandle);

    long realmAtObjective(int dimensionId, long packedPosition);

    long settlementAtObjective(int dimensionId, long packedPosition);

    boolean isCapital(long realmId, long settlementId);
}
