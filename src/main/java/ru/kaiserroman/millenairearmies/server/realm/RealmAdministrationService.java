package ru.kaiserroman.millenairearmies.server.realm;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenaire.realm.Constitution;
import ru.kaiserroman.millenaire.realm.GovernmentForm;
import ru.kaiserroman.millenaire.realm.RealmMemberKind;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.persistence.PlayerRealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.PlayerSettlementSavedData;
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

    /** Cheap preflight used before an irreversible physical Village spawn. */
    public boolean canFoundPlayerRealm(UUID owner) {
        return canFoundPlayerRealm(owner, 1);
    }

    /** Capacity-aware preflight for one capital plus generated child settlements. */
    public boolean canFoundPlayerRealm(UUID owner, int settlementCount) {
        if (owner == null || settlementCount <= 0 || settlementCount > PlayerSettlementSavedData.MAX_SETTLEMENTS) {
            return false;
        }
        int requiredMembers = settlementCount + 1; // settlements plus the player subject
        int requiredSubjects = settlementCount + 1;
        return realms.realmForPlayer(owner) == RealmRegistry.NO_REALM
                && !legacyRealms.exists(owner)
                && legacyRealms.size() < PlayerRealmSavedData.MAX_REALMS
                && legacyGovernance.size()
                        <= RealmGovernanceSavedData.MAX_ASSIGNMENTS - settlementCount
                && realms.registry().realmCount() < RealmSavedData.MAX_REALMS
                && realms.registry().memberCount()
                        <= RealmSavedData.MAX_MEMBERS - requiredMembers
                && realms.keys().size()
                        <= RealmSavedData.MAX_SUBJECTS - requiredSubjects;
    }

    /** Renames canonical and compatibility metadata for the player's own Realm. */
    public boolean renamePlayerRealm(UUID actor, String name) {
        if (actor == null || name == null) throw new IllegalArgumentException("Invalid Realm rename");
        long actorSubject = realms.keys().findPlayer(actor);
        if (actorSubject == 0L) return false;
        long realmId = realms.registry().realmOfMember(actorSubject);
        if (realmId == RealmRegistry.NO_REALM || !realms.registry().exists(realmId)) return false;
        long capital = realms.registry().capitalMemberId(realmId);
        if (realms.registry().memberControllerId(capital) != actorSubject) return false;
        boolean changed = realms.upsertMetadata(
                realmId,
                name,
                realms.taxRate(realmId),
                realms.treasury(realmId),
                realms.capturedSettlementCount(realmId),
                realms.isLegacy(realmId));
        boolean compatibilityUpdated = legacyRealms.rename(actor, name);
        if (!compatibilityUpdated) compatibilityMismatchCount++;
        if (changed) realms.markChanged();
        return true;
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

    /** Preflight for a capture that must also remain commandable through the compatibility store. */
    public boolean canRecordCapture(UUID actor, UUID settlement) {
        if (actor == null || settlement == null) return false;
        long realmId = realms.realmForPlayer(actor);
        if (realmId == RealmRegistry.NO_REALM
                || realms.name(realmId) == null
                || !legacyRealms.exists(actor)) {
            return false;
        }
        return legacyGovernance.canCommandSettlement(actor, settlement)
                || legacyGovernance.canAttachRegion(
                        actor,
                        governorId(actor, settlement),
                        settlement);
    }

    public boolean canAttachFoundedRegion(UUID actor, UUID settlement) {
        return actor != null && settlement != null
                && legacyGovernance.canReserveRegion(governorId(actor, settlement), settlement);
    }

    /** Mirrors a generated child settlement without incrementing conquest history. */
    public boolean attachFoundedRegion(UUID actor, UUID settlement) {
        if (actor == null || settlement == null) return false;
        long actorRealm = realms.realmForPlayer(actor);
        return actorRealm != RealmRegistry.NO_REALM
                && realms.realmForSettlement(settlement) == actorRealm
                && mirrorGovernorRegion(actor, settlement);
    }

    /** Records a successfully committed settlement capture in canonical and compatibility metadata. */
    public boolean recordCapture(UUID actor) {
        return recordCapture(actor, null);
    }

    /** Mirrors a captured settlement as a governor-led legacy region when possible. */
    public boolean recordCapture(UUID actor, UUID settlement) {
        if (actor == null) return false;
        long realmId = realms.realmForPlayer(actor);
        if (realmId == RealmRegistry.NO_REALM || !legacyRealms.exists(actor)) return false;
        if (settlement != null && !canRecordCapture(actor, settlement)) return false;
        if (!realms.recordCapture(realmId)) return false;
        legacyRealms.recordCapture(actor);
        if (settlement != null && !mirrorGovernorRegion(actor, settlement)) {
            compatibilityMismatchCount++;
            throw new IllegalStateException(
                    "Preflighted captured settlement could not join compatibility governance");
        }
        realms.markChanged();
        return true;
    }

    private boolean mirrorGovernorRegion(UUID actor, UUID settlement) {
        if (legacyGovernance.canCommandSettlement(actor, settlement)) return true;
        return legacyGovernance.attachRegion(
                actor,
                governorId(actor, settlement),
                settlement,
                RealmGovernanceSavedData.ROLE_GOVERNOR);
    }

    private static UUID governorId(UUID actor, UUID settlement) {
        return UUID.nameUUIDFromBytes(
                ("millenaire-universalis:governor:" + actor + ':' + settlement)
                        .getBytes(StandardCharsets.UTF_8));
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
