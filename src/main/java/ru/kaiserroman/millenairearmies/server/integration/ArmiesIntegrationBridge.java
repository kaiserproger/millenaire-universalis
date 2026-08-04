package ru.kaiserroman.millenairearmies.server.integration;

import java.util.Objects;
import ru.kaiserroman.millenaire.realm.RealmMilitaryPolicy;
import ru.kaiserroman.millenairearmies.lifecycle.ArmyLifecycleService;
import ru.kaiserroman.millenairearmies.server.execution.PhysicalBattleEventLog;

/**
 * Narrow public integration surface for Realm and Simulation.
 *
 * <p>It exposes no ECS, membership, controller or mutation store. Realm may install its read-only
 * hostility/identity policy after server startup; Simulation may create independent cursors over the
 * neutral physical event journal. All installation remains server-thread guarded by the lifecycle.</p>
 */
public final class ArmiesIntegrationBridge {
    private static ArmyLifecycleService lifecycle;

    private ArmiesIntegrationBridge() {}

    /** Internal mod bootstrap hook; rebinding to a different addon instance is rejected. */
    public static synchronized void bind(ArmyLifecycleService service) {
        Objects.requireNonNull(service, "service");
        if (lifecycle != null && lifecycle != service) {
            throw new IllegalStateException("Millenaire Armies integration bridge is already bound");
        }
        lifecycle = service;
    }

    /** Installs Realm's read-only war policy and stable-id mapping on the active server thread. */
    public static synchronized void installRealmMilitaryPolicy(
            RealmMilitaryPolicy policy, ArmyRealmIdentityResolver identities) {
        requireLifecycle().installRealmMilitaryPolicy(
                Objects.requireNonNull(policy, "policy"),
                Objects.requireNonNull(identities, "identities"));
    }

    /** Returns the neutral bounded journal, or null before physical execution starts. */
    public static synchronized PhysicalBattleEventLog battleEvents() {
        return requireLifecycle().battleEvents();
    }

    private static ArmyLifecycleService requireLifecycle() {
        if (lifecycle == null) {
            throw new IllegalStateException("Millenaire Armies integration bridge is not bound");
        }
        return lifecycle;
    }
}
