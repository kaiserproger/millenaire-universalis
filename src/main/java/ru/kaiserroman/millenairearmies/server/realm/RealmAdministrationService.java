package ru.kaiserroman.millenairearmies.server.realm;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenaire.realm.Constitution;
import ru.kaiserroman.millenaire.realm.GovernmentForm;
import ru.kaiserroman.millenaire.realm.RealmMemberKind;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.persistence.PlayerRealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.RealmGovernanceSavedData;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;

/**
 * Canonical transaction boundary for player Realm foundation and fiscal administration.
 * Compatibility stores are dual-written only after canonical preflight; they are no longer the
 * authoritative source for the war council or military policy.
 */
public final class RealmAdministrationService {
    public static final int INITIAL_TAX_RATE = 10;
    public static final int INITIAL_LEGITIMACY = 700;

    private final RealmSavedData realms;
    private final SimulationSavedData simulation;
    private final PlayerRealmSavedData legacyRealms;
    private final RealmGovernanceSavedData legacyGovernance;

    private long foundationCount;
    private long taxChangeCount;
    private long compatibilityMismatchCount;

    public RealmAdministrationService(
            RealmSavedData realms,
            SimulationSavedData simulation,
            PlayerRealmSavedData legacyRealms,
            RealmGovernanceSavedData legacyGovernance) {
        if (realms == null || legacyRealms == null || legacyGovernance == null) {
            throw new NullPointerException("Realm administration dependency");
        }
        this.realms = realms;
        this.simulation = simulation;
        this.legacyRealms = legacyRealms;
        this.legacyGovernance = legacyGovernance;
    }

    /** Returns the canonical Realm id, or {@link RealmRegistry#NO_REALM} on rejected preflight. */
    public long foundPlayerRealm(
            UUID owner,
            UUID capital,
            String name,
            ResourceLocation dimension,
            long gameTime,
            long foundedCycle) {
        if (owner == null || capital == null || name == null || dimension == null
                || gameTime < 0L || foundedCycle < 0L) {
            throw new IllegalArgumentException("Invalid player Realm foundation input");
        }
        if (realms.realmForPlayer(owner) != RealmRegistry.NO_REALM
                || realms.realmForSettlement(capital) != RealmRegistry.NO_REALM
                || legacyRealms.exists(owner)
                || !legacyGovernance.canFoundCapital(owner, capital)) {
            return RealmRegistry.NO_REALM;
        }

        long ownerSubject = realms.keys().internPlayer(owner);
        long capitalSubject = realms.keys().internSettlement(capital);
        long realmId = realms.registry().createRealm(
                capitalSubject,
                RealmMemberKind.PLAYER_SETTLEMENT,
                ownerSubject,
                GovernmentForm.FEUDAL_MONARCHY,
                INITIAL_LEGITIMACY,
                foundedCycle);
        if (realmId == RealmRegistry.NO_REALM) return RealmRegistry.NO_REALM;
        boolean playerAdded = realms.registry().addMember(
                realmId,
                ownerSubject,
                RealmMemberKind.PLAYER,
                ownerSubject,
                1000);
        int institutionRow = realms.institutions().ensureRealm(
                realmId,
                Constitution.archetype(GovernmentForm.FEUDAL_MONARCHY, INITIAL_LEGITIMACY),
                historicalMilliYear(gameTime));
        if (!playerAdded || institutionRow < 0) {
            realms.institutions().removeRealm(realmId);
            realms.history().removeRealm(realmId);
            realms.registry().dissolveRealm(realmId);
            return RealmRegistry.NO_REALM;
        }
        realms.upsertMetadata(
                realmId,
                name,
                INITIAL_TAX_RATE,
                0L,
                0,
                false);

        boolean legacyFounded = legacyRealms.found(owner, name, capital, dimension, gameTime);
        boolean governanceFounded = legacyGovernance.foundCapital(
                owner,
                capital,
                RealmGovernanceSavedData.GOVERNMENT_FEUDAL);
        if (!legacyFounded || !governanceFounded) {
            compatibilityMismatchCount++;
            realms.institutions().removeRealm(realmId);
            realms.history().removeRealm(realmId);
            realms.registry().dissolveRealm(realmId);
            realms.removeMetadata(realmId);
            throw new IllegalStateException("Canonical/compatibility Realm foundation mismatch");
        }

        assignSimulationRealm(capital, realmId);
        realms.markChanged();
        foundationCount++;
        return realmId;
    }

    /** Canonical head-of-state tax update with best-effort compatibility dual-write. */
    public boolean setTaxRate(UUID actor, int taxRate) {
        if (actor == null || taxRate < 0 || taxRate > 25) {
            throw new IllegalArgumentException("Invalid Realm tax update");
        }
        long actorSubject = realms.keys().findPlayer(actor);
        if (actorSubject == 0L) return false;
        long realmId = realms.registry().realmOfMember(actorSubject);
        if (realmId == RealmRegistry.NO_REALM || !realms.registry().exists(realmId)) return false;
        long capital = realms.registry().capitalMemberId(realmId);
        if (realms.registry().memberControllerId(capital) != actorSubject) return false;
        String name = realms.name(realmId);
        if (name == null) return false;
        boolean changed = realms.upsertMetadata(
                realmId,
                name,
                taxRate,
                realms.treasury(realmId),
                realms.capturedSettlementCount(realmId),
                realms.isLegacy(realmId));
        boolean compatibilityUpdated = legacyRealms.setTaxRate(actor, taxRate);
        if (!compatibilityUpdated) compatibilityMismatchCount++;
        if (changed) realms.markChanged();
        taxChangeCount++;
        return true;
    }

    public long foundationCount() { return foundationCount; }
    public long taxChangeCount() { return taxChangeCount; }
    public long compatibilityMismatchCount() { return compatibilityMismatchCount; }

    private static long historicalMilliYear(long gameTime) {
        long yearTicks = ArmiesConfig.HISTORICAL_YEAR_TICKS;
        long years = gameTime / yearTicks;
        long remainder = gameTime % yearTicks;
        if (years > Long.MAX_VALUE / 1000L) return Long.MAX_VALUE;
        return years * 1000L + remainder * 1000L / yearTicks;
    }

    private void assignSimulationRealm(UUID settlement, long realmId) {
        if (simulation == null) return;
        long simulationSettlement = simulation.keys().findSettlement(settlement);
        if (simulationSettlement == 0L) return;
        if (simulation.state().assignRealm(simulationSettlement, realmId)) {
            simulation.markChanged();
        }
    }
}
