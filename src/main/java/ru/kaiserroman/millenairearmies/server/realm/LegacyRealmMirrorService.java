package ru.kaiserroman.millenairearmies.server.realm;

import java.util.Arrays;
import java.util.UUID;
import ru.kaiserroman.millenaire.realm.Constitution;
import ru.kaiserroman.millenaire.realm.GovernmentForm;
import ru.kaiserroman.millenaire.realm.RealmMemberKind;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenairearmies.persistence.PlayerRealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.RealmGovernanceSavedData;
import ru.kaiserroman.millenairearmies.persistence.RealmKeyTable;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;

/**
 * Transitional, server-thread mirror from the old Armies-owned player realm stores into the new
 * canonical Realm registry. Only metadata rows marked legacy are reconciled or removed; future
 * NPC-native Realms are never touched.
 */
public final class LegacyRealmMirrorService {
    private final RealmSavedData target;
    private final RealmKeyTable keys;
    private final RealmRegistry registry;
    private final PlayerRealmSavedData.View legacyView = new PlayerRealmSavedData.View();
    private final LongEpochSet seenRealms = new LongEpochSet(RealmSavedData.MAX_REALMS);
    private final LongEpochSet seenMembers = new LongEpochSet(RealmSavedData.MAX_MEMBERS);
    private final long[] legacyRealmScratch = new long[RealmSavedData.MAX_REALMS];
    private final long[] staleMemberScratch = new long[RealmSavedData.MAX_MEMBERS];

    private PlayerRealmSavedData legacyRealms;
    private RealmGovernanceSavedData legacyGovernance;
    private int legacyRealmCount;
    private int staleMemberCount;
    private boolean metadataChanged;
    private long reconcileCount;

    public LegacyRealmMirrorService(RealmSavedData target) {
        if (target == null) throw new NullPointerException("target");
        this.target = target;
        keys = target.keys();
        registry = target.registry();
    }

    /** Returns one when the canonical mirror changed, otherwise zero. */
    public int reconcile(
            PlayerRealmSavedData legacyRealms,
            RealmGovernanceSavedData legacyGovernance) {
        if (legacyRealms == null || legacyGovernance == null) {
            throw new NullPointerException("legacy Realm stores");
        }
        this.legacyRealms = legacyRealms;
        this.legacyGovernance = legacyGovernance;
        long oldRevision = registry.revision();
        long oldInstitutionRevision = target.institutions().revision();
        int oldSubjects = keys.size();
        int oldMetadata = target.metadataSize();
        seenRealms.begin();
        seenMembers.begin();
        legacyRealmCount = 0;
        metadataChanged = false;
        target.visitMetadata((realmId, name, taxRate, treasury, legacy) -> {
            if (legacy && legacyRealmCount < legacyRealmScratch.length) {
                legacyRealmScratch[legacyRealmCount++] = realmId;
            }
        });

        legacyRealms.visit((ownerMost, ownerLeast, taxRate, lastTaxTick) ->
                mirrorRealm(new UUID(ownerMost, ownerLeast)));
        removeMissingLegacyRealms();
        removeMissingLegacyMembers();
        reconcileCount++;

        boolean changed = oldRevision != registry.revision()
                || oldInstitutionRevision != target.institutions().revision()
                || oldSubjects != keys.size()
                || oldMetadata != target.metadataSize()
                || metadataChanged;
        if (changed) target.markChanged();
        this.legacyRealms = null;
        this.legacyGovernance = null;
        return changed ? 1 : 0;
    }

    private void mirrorRealm(UUID head) {
        if (!legacyRealms.read(head, legacyView)) return;
        UUID capital = new UUID(legacyView.capitalMost(), legacyView.capitalLeast());
        long headSubject = keys.internPlayer(head);
        long capitalSubject = keys.internSettlement(capital);
        long realmId = registry.realmOfMember(headSubject);
        if (realmId == RealmRegistry.NO_REALM) {
            long capitalRealm = registry.realmOfMember(capitalSubject);
            if (capitalRealm != RealmRegistry.NO_REALM && target.isLegacy(capitalRealm)) {
                realmId = capitalRealm;
            } else {
                realmId = registry.createRealm(
                        capitalSubject,
                        RealmMemberKind.PLAYER_SETTLEMENT,
                        headSubject,
                        government(head),
                        legitimacy(head, legacyView.capturedSettlements()),
                        0L);
                if (realmId == RealmRegistry.NO_REALM) {
                    throw new IllegalStateException("Unable to create canonical legacy Realm");
                }
            }
        } else if (!target.isLegacy(realmId) && target.name(realmId) != null) {
            // Never let a transitional old save seize a future native Realm that already owns player.
            return;
        }

        ensureMember(realmId, capitalSubject, RealmMemberKind.PLAYER_SETTLEMENT, headSubject, 1000);
        registry.setCapital(realmId, capitalSubject);
        ensureMember(realmId, headSubject, RealmMemberKind.PLAYER, headSubject, 1000);
        seenMembers.add(capitalSubject);
        seenMembers.add(headSubject);
        seenRealms.add(realmId);
        if (target.institutions().constitution(realmId) == null) {
            GovernmentForm initialGovernment = government(head);
            int initialLegitimacy = legitimacy(head, legacyView.capturedSettlements());
            registry.setGovernment(realmId, initialGovernment);
            registry.setLegitimacy(realmId, initialLegitimacy);
            if (target.institutions().ensureRealm(
                            realmId,
                            Constitution.archetype(initialGovernment, initialLegitimacy),
                            0L)
                    < 0) {
                throw new IllegalStateException("Unable to initialise legacy Realm institutions");
            }
        }
        metadataChanged |= target.upsertMetadata(
                realmId,
                legacyView.name(),
                legacyView.taxRate(),
                legacyView.treasury(),
                legacyView.capturedSettlements(),
                true);

        long canonicalRealmId = realmId;
        legacyGovernance.visitRealm(head, (controllerMost, controllerLeast, villageMost, villageLeast, role) -> {
            UUID controller = new UUID(controllerMost, controllerLeast);
            UUID village = new UUID(villageMost, villageLeast);
            long controllerSubject = keys.internPlayer(controller);
            long villageSubject = keys.internSettlement(village);
            ensureMember(
                    canonicalRealmId,
                    controllerSubject,
                    RealmMemberKind.PLAYER,
                    controllerSubject,
                    playerInfluence(role));
            ensureMember(
                    canonicalRealmId,
                    villageSubject,
                    RealmMemberKind.PLAYER_SETTLEMENT,
                    controllerSubject,
                    settlementInfluence(role));
            seenMembers.add(controllerSubject);
            seenMembers.add(villageSubject);
        });
    }

    private void ensureMember(
            long realmId,
            long memberId,
            RealmMemberKind kind,
            long controllerId,
            int influence) {
        long currentRealm = registry.realmOfMember(memberId);
        if (currentRealm == RealmRegistry.NO_REALM) {
            if (!registry.addMember(realmId, memberId, kind, controllerId, influence)) {
                throw new IllegalStateException("Unable to add mirrored Realm member " + memberId);
            }
            return;
        }
        if (!registry.updateMember(memberId, realmId, kind, controllerId, influence)) {
            if (registry.capitalMemberId(currentRealm) == memberId && currentRealm != realmId) {
                throw new IllegalStateException("Legacy mirror attempted to transfer another Realm capital");
            }
            throw new IllegalStateException("Unable to update mirrored Realm member " + memberId);
        }
    }

    private void removeMissingLegacyRealms() {
        for (int index = 0; index < legacyRealmCount; index++) {
            long realmId = legacyRealmScratch[index];
            if (seenRealms.contains(realmId)) continue;
            target.institutions().removeRealm(realmId);
            target.diplomacy().removeRealm(realmId);
            target.dependencies().removeRealm(realmId);
            target.history().removeRealm(realmId);
            registry.dissolveRealm(realmId);
            metadataChanged |= target.removeMetadata(realmId);
        }
    }

    private void removeMissingLegacyMembers() {
        staleMemberCount = 0;
        registry.visitAllMembers((realmId, memberId, kind, controllerId, influence) -> {
            if (target.isLegacy(realmId)
                    && seenRealms.contains(realmId)
                    && !seenMembers.contains(memberId)
                    && staleMemberCount < staleMemberScratch.length) {
                staleMemberScratch[staleMemberCount++] = memberId;
            }
        });
        for (int index = 0; index < staleMemberCount; index++) {
            long memberId = staleMemberScratch[index];
            long realmId = registry.realmOfMember(memberId);
            if (registry.capitalMemberId(realmId) != memberId) {
                registry.removeMember(memberId);
            }
        }
    }

    private GovernmentForm government(UUID head) {
        return legacyGovernance.government(head) == RealmGovernanceSavedData.GOVERNMENT_ADMINISTRATIVE
                ? GovernmentForm.BUREAUCRATIC_MONARCHY
                : GovernmentForm.FEUDAL_MONARCHY;
    }

    private int legitimacy(UUID head, int captures) {
        long value = 550L
                + Math.min(250L, Math.max(0L, (long) captures) * 15L)
                + Math.min(150L, (long) legacyGovernance.regionCount(head) * 10L);
        return (int) Math.min(1000L, value);
    }

    private static int playerInfluence(byte role) {
        return role == RealmGovernanceSavedData.ROLE_HEAD
                ? 1000
                : role == RealmGovernanceSavedData.ROLE_GOVERNOR ? 650 : 500;
    }

    private static int settlementInfluence(byte role) {
        return role == RealmGovernanceSavedData.ROLE_HEAD
                ? 1000
                : role == RealmGovernanceSavedData.ROLE_GOVERNOR ? 600 : 450;
    }

    public long reconcileCount() { return reconcileCount; }

    private static final class LongEpochSet {
        private final long[] keys;
        private final int[] epochs;
        private final int mask;
        private int epoch;

        LongEpochSet(int expectedSize) {
            int capacity = 16;
            while (capacity < expectedSize * 2L) capacity <<= 1;
            keys = new long[capacity];
            epochs = new int[capacity];
            mask = capacity - 1;
        }

        void begin() {
            epoch++;
            if (epoch == 0) {
                Arrays.fill(epochs, 0);
                epoch = 1;
            }
        }

        void add(long value) {
            if (value <= 0L) throw new IllegalArgumentException("Epoch set values must be positive");
            int slot = hash(value) & mask;
            while (epochs[slot] == epoch) {
                if (keys[slot] == value) return;
                slot = (slot + 1) & mask;
            }
            epochs[slot] = epoch;
            keys[slot] = value;
        }

        boolean contains(long value) {
            if (value <= 0L) return false;
            int slot = hash(value) & mask;
            while (epochs[slot] == epoch) {
                if (keys[slot] == value) return true;
                slot = (slot + 1) & mask;
            }
            return false;
        }

        private static int hash(long value) {
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdL;
            value ^= value >>> 33;
            value *= 0xc4ceb9fe1a85ec53L;
            value ^= value >>> 33;
            return (int) value;
        }
    }
}
