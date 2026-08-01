package ru.kaiserroman.millenairearmies.server.service;

import java.util.UUID;

/**
 * Server-derived authority presented to the strategic command service.
 *
 * <p>Packet handlers must construct this from the authenticated {@code ServerPlayer}; client
 * payloads are never an authority source. An operator may administer every army. A non-operator
 * may only act on armies whose controller UUID matches this identity.</p>
 */
public record ArmyCommandAuthority(long uuidMost, long uuidLeast, boolean hasIdentity, boolean operator) {
    public static ArmyCommandAuthority operatorWithoutIdentity() {
        return new ArmyCommandAuthority(0L, 0L, false, true);
    }

    public static ArmyCommandAuthority player(UUID uuid, boolean operator) {
        return new ArmyCommandAuthority(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(), true, operator);
    }
}
