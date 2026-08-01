package ru.kaiserroman.millenairearmies.server.service;

import java.util.Objects;

/** Pure authorization rule shared by runtime commands and deterministic integration smoke. */
public final class ArmyCommandAuthorization {
    private ArmyCommandAuthorization() {}

    public static boolean canControl(
            ArmyCommandAuthority authority,
            PackedArmyControllers controllers,
            int armyHandle) {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(controllers, "controllers");
        return authority.operator()
                || authority.hasIdentity()
                        && controllers.matches(
                                armyHandle,
                                authority.uuidMost(),
                                authority.uuidLeast());
    }
}
