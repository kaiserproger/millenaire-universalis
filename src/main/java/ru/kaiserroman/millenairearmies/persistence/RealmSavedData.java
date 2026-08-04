package ru.kaiserroman.millenairearmies.persistence;

import java.util.Arrays;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import ru.kaiserroman.millenaire.realm.Constitution;
import ru.kaiserroman.millenaire.realm.DiplomaticStatus;
import ru.kaiserroman.millenaire.realm.GovernmentForm;
import ru.kaiserroman.millenaire.realm.RealmDependencyLedger;
import ru.kaiserroman.millenaire.realm.RealmHistoricalPhase;
import ru.kaiserroman.millenaire.realm.RealmHistoryLedger;
import ru.kaiserroman.millenaire.realm.RealmScale;
import ru.kaiserroman.millenaire.realm.RealmStatePriority;
import ru.kaiserroman.millenaire.realm.RealmDiplomacyLedger;
import ru.kaiserroman.millenaire.realm.RealmInstitutionLedger;
import ru.kaiserroman.millenaire.realm.RealmLifecycleLedger;
import ru.kaiserroman.millenaire.realm.RealmMemberKind;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenaire.realm.WarGoal;

/** Canonical persistence owner for the new Realm kernel; independent from ArmySavedData. */
public final class RealmSavedData extends SavedData {
    public static final String FILE_ID = "millenaire_realms";
    public static final int MAX_REALMS = 4_096;
    public static final int MAX_MEMBERS = 32_768;
    public static final int MAX_SUBJECTS = 32_768;
    public static final int MAX_RELATIONS = 32_768;
    public static final int MAX_DEPENDENCIES = MAX_REALMS;
    public static final int MAX_NAME_LENGTH = 64;
    private static final int SCHEMA_VERSION = 4;
    private static final SavedData.Factory<RealmSavedData> FACTORY =
            new SavedData.Factory<>(RealmSavedData::new, RealmSavedData::load);

    private final RealmKeyTable keys;
    private final RealmRegistry registry;
    private final RealmInstitutionLedger institutions;
    private final RealmLifecycleLedger lifecycle;
    private final RealmDiplomacyLedger diplomacy;
    private final RealmDependencyLedger dependencies;
    private final RealmHistoryLedger history;

    private int metadataSize;
    private long metadataRevision;
    private long[] metadataRealmIds = new long[8];
    private String[] names = new String[8];
    private int[] taxRates = new int[8];
    private long[] treasuries = new long[8];
    private int[] capturedSettlements = new int[8];
    private byte[] legacyFlags = new byte[8];
    private byte[] statePriorities = new byte[8];
    private int[] stateDecisionPressures = new int[8];
    private int[] stateInvestmentPermille = new int[8];
    private long[] lastStateDecisionMilliYears = new long[8];

    public RealmSavedData() {
        this(
                new RealmKeyTable(MAX_SUBJECTS),
                new RealmRegistry(MAX_REALMS, MAX_MEMBERS),
                new RealmInstitutionLedger(MAX_REALMS),
                new RealmLifecycleLedger(MAX_MEMBERS, MAX_REALMS),
                new RealmDiplomacyLedger(MAX_RELATIONS),
                new RealmDependencyLedger(MAX_DEPENDENCIES),
                new RealmHistoryLedger(MAX_REALMS));
    }

    RealmSavedData(
            RealmKeyTable keys,
            RealmRegistry registry,
            RealmInstitutionLedger institutions,
            RealmLifecycleLedger lifecycle,
            RealmDiplomacyLedger diplomacy,
            RealmDependencyLedger dependencies,
            RealmHistoryLedger history) {
        if (keys == null || registry == null || institutions == null
                || lifecycle == null || diplomacy == null || dependencies == null || history == null) {
            throw new NullPointerException("Realm SavedData stores");
        }
        this.keys = keys;
        this.registry = registry;
        this.institutions = institutions;
        this.lifecycle = lifecycle;
        this.diplomacy = diplomacy;
        this.dependencies = dependencies;
        this.history = history;
        Arrays.fill(lastStateDecisionMilliYears, -1L);
    }

    public static RealmSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    public RealmKeyTable keys() { return keys; }
    public RealmRegistry registry() { return registry; }
    public RealmInstitutionLedger institutions() { return institutions; }
    public RealmLifecycleLedger lifecycle() { return lifecycle; }
    public RealmDiplomacyLedger diplomacy() { return diplomacy; }
    public RealmDependencyLedger dependencies() { return dependencies; }
    public RealmHistoryLedger history() { return history; }
    public int metadataSize() { return metadataSize; }
    public long metadataRevision() { return metadataRevision; }
    public void markChanged() { setDirty(); }

    public long realmForSettlement(UUID settlement) {
        long subject = keys.findSettlement(settlement);
        return subject == 0L ? RealmRegistry.NO_REALM : registry.realmOfMember(subject);
    }

    public long realmForPlayer(UUID player) {
        long subject = keys.findPlayer(player);
        return subject == 0L ? RealmRegistry.NO_REALM : registry.realmOfMember(subject);
    }

    public boolean upsertMetadata(
            long realmId,
            String name,
            int taxRate,
            long treasury,
            boolean legacy) {
        int row = findMetadata(realmId);
        int captures = row < 0 ? 0 : capturedSettlements[row];
        return upsertMetadata(realmId, name, taxRate, treasury, captures, legacy);
    }

    public boolean upsertMetadata(
            long realmId,
            String name,
            int taxRate,
            long treasury,
            int captures,
            boolean legacy) {
        if (!registry.exists(realmId) || taxRate < 0 || taxRate > 100
                || treasury < 0L || captures < 0) {
            throw new IllegalArgumentException("Invalid Realm metadata");
        }
        String validatedName = validateName(name);
        int row = findMetadata(realmId);
        if (row < 0) {
            if (metadataSize == MAX_REALMS) {
                throw new IllegalStateException("Realm metadata limit reached");
            }
            ensureMetadataCapacity(metadataSize + 1);
            row = metadataSize++;
            metadataRealmIds[row] = realmId;
        }
        byte legacyFlag = legacy ? (byte) 1 : (byte) 0;
        boolean changed = !validatedName.equals(names[row])
                || taxRates[row] != taxRate
                || treasuries[row] != treasury
                || capturedSettlements[row] != captures
                || legacyFlags[row] != legacyFlag;
        names[row] = validatedName;
        taxRates[row] = taxRate;
        treasuries[row] = treasury;
        capturedSettlements[row] = captures;
        legacyFlags[row] = legacyFlag;
        if (changed) changedMetadata();
        return changed;
    }

    public boolean removeMetadata(long realmId) {
        int row = findMetadata(realmId);
        if (row < 0) return false;
        int last = --metadataSize;
        if (row != last) {
            metadataRealmIds[row] = metadataRealmIds[last];
            names[row] = names[last];
            taxRates[row] = taxRates[last];
            treasuries[row] = treasuries[last];
            capturedSettlements[row] = capturedSettlements[last];
            legacyFlags[row] = legacyFlags[last];
            statePriorities[row] = statePriorities[last];
            stateDecisionPressures[row] = stateDecisionPressures[last];
            stateInvestmentPermille[row] = stateInvestmentPermille[last];
            lastStateDecisionMilliYears[row] = lastStateDecisionMilliYears[last];
        }
        metadataRealmIds[last] = 0L;
        names[last] = null;
        taxRates[last] = 0;
        treasuries[last] = 0L;
        capturedSettlements[last] = 0;
        legacyFlags[last] = 0;
        statePriorities[last] = 0;
        stateDecisionPressures[last] = 0;
        stateInvestmentPermille[last] = 0;
        lastStateDecisionMilliYears[last] = -1L;
        changedMetadata();
        return true;
    }

    public String name(long realmId) {
        int row = findMetadata(realmId);
        return row < 0 ? null : names[row];
    }

    public int taxRate(long realmId) {
        int row = findMetadata(realmId);
        return row < 0 ? 0 : taxRates[row];
    }

    public long treasury(long realmId) {
        int row = findMetadata(realmId);
        return row < 0 ? 0L : treasuries[row];
    }

    /** Adds or removes canonical treasury funds, saturating upward and never allowing debt. */
    public boolean adjustTreasury(long realmId, long delta) {
        int row = findMetadata(realmId);
        if (row < 0) return false;
        long current = treasuries[row];
        long next;
        if (delta >= 0L) {
            next = current > Long.MAX_VALUE - delta ? Long.MAX_VALUE : current + delta;
        } else if (delta == Long.MIN_VALUE || current < -delta) {
            next = 0L;
        } else {
            next = current + delta;
        }
        if (next != current) {
            treasuries[row] = next;
            changedMetadata();
        }
        return true;
    }

    public int capturedSettlementCount(long realmId) {
        int row = findMetadata(realmId);
        return row < 0 ? 0 : capturedSettlements[row];
    }

    public boolean recordCapture(long realmId) {
        int row = findMetadata(realmId);
        if (row < 0) return false;
        if (capturedSettlements[row] != Integer.MAX_VALUE) {
            capturedSettlements[row]++;
            changedMetadata();
        }
        return true;
    }

    public boolean isLegacy(long realmId) {
        int row = findMetadata(realmId);
        return row >= 0 && legacyFlags[row] != 0;
    }

    public RealmStatePriority statePriority(long realmId) {
        int row = findMetadata(realmId);
        return row < 0
                ? RealmStatePriority.NONE
                : RealmStatePriority.values()[Byte.toUnsignedInt(statePriorities[row])];
    }

    public int stateDecisionPressure(long realmId) {
        int row = findMetadata(realmId);
        return row < 0 ? 0 : stateDecisionPressures[row];
    }

    public int stateInvestmentPermille(long realmId) {
        int row = findMetadata(realmId);
        return row < 0 ? 0 : stateInvestmentPermille[row];
    }

    public long lastStateDecisionMilliYear(long realmId) {
        int row = findMetadata(realmId);
        return row < 0 ? -1L : lastStateDecisionMilliYears[row];
    }

    public boolean recordStateDecision(
            long realmId,
            RealmStatePriority priority,
            int pressure,
            int investmentPermille,
            long milliYear) {
        if (priority == null || pressure < 0 || pressure > 1000
                || investmentPermille < 0 || investmentPermille > 1000 || milliYear < 0L) {
            throw new IllegalArgumentException("Invalid Realm state decision metadata");
        }
        int row = findMetadata(realmId);
        if (row < 0) return false;
        byte encoded = (byte) priority.ordinal();
        boolean changed = statePriorities[row] != encoded
                || stateDecisionPressures[row] != pressure
                || stateInvestmentPermille[row] != investmentPermille
                || lastStateDecisionMilliYears[row] != milliYear;
        statePriorities[row] = encoded;
        stateDecisionPressures[row] = pressure;
        stateInvestmentPermille[row] = investmentPermille;
        lastStateDecisionMilliYears[row] = milliYear;
        if (changed) changedMetadata();
        return true;
    }

    public void visitMetadata(MetadataVisitor visitor) {
        if (visitor == null) throw new NullPointerException("visitor");
        for (int row = 0; row < metadataSize; row++) {
            visitor.accept(
                    metadataRealmIds[row],
                    names[row],
                    taxRates[row],
                    treasuries[row],
                    legacyFlags[row] != 0);
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("SchemaVersion", SCHEMA_VERSION);
        tag.putLong("NextRealmId", registry.nextRealmId());
        tag.putLong("RegistryRevision", registry.revision());
        tag.putLong("InstitutionRevision", institutions.revision());
        tag.putLong("LifecycleRevision", lifecycle.revision());
        tag.putLong("DiplomacyRevision", diplomacy.revision());
        tag.putLong("DependencyRevision", dependencies.revision());
        tag.putLong("HistoryRevision", history.revision());
        tag.putLong("MetadataRevision", metadataRevision);

        ListTag subjects = new ListTag();
        keys.visit((subjectId, kind, most, least) -> {
            CompoundTag row = new CompoundTag();
            row.putByte("Kind", kind);
            row.putLong("Most", most);
            row.putLong("Least", least);
            subjects.add(row);
        });
        tag.put("Subjects", subjects);

        ListTag realms = new ListTag();
        registry.visitRealms((realmId, capitalMemberId, foundedCycle, government, legitimacy) -> {
            CompoundTag row = new CompoundTag();
            row.putLong("RealmId", realmId);
            row.putLong("CapitalMemberId", capitalMemberId);
            row.putLong("FoundedCycle", foundedCycle);
            row.putByte("Government", (byte) government.ordinal());
            row.putInt("Legitimacy", legitimacy);
            realms.add(row);
        });
        tag.put("Realms", realms);

        ListTag members = new ListTag();
        registry.visitAllMembers((realmId, memberId, kind, controllerId, influence) -> {
            CompoundTag row = new CompoundTag();
            row.putLong("RealmId", realmId);
            row.putLong("MemberId", memberId);
            row.putByte("Kind", (byte) kind.ordinal());
            row.putLong("ControllerId", controllerId);
            row.putInt("Influence", influence);
            members.add(row);
        });
        tag.put("Members", members);

        ListTag institutionRows = new ListTag();
        institutions.visit((realmId, constitution, stableMilliYears, lastEvaluationMilliYear) -> {
            CompoundTag row = new CompoundTag();
            row.putLong("RealmId", realmId);
            row.putByte("Government", (byte) constitution.government().ordinal());
            row.putInt("Centralization", constitution.centralization());
            row.putInt("Bureaucracy", constitution.bureaucracy());
            row.putInt("NoblePower", constitution.noblePower());
            row.putInt("MerchantPower", constitution.merchantPower());
            row.putInt("CitizenPower", constitution.citizenPower());
            row.putInt("MarketFreedom", constitution.marketFreedom());
            row.putInt("LandConcentration", constitution.landConcentration());
            row.putInt("Militarization", constitution.militarization());
            row.putInt("Legitimacy", constitution.legitimacy());
            row.putInt("StableMilliYears", stableMilliYears);
            row.putLong("LastEvaluationMilliYear", lastEvaluationMilliYear);
            institutionRows.add(row);
        });
        tag.put("Institutions", institutionRows);

        ListTag formationRows = new ListTag();
        lifecycle.visitFormations((regionKey, cultureKey, qualifyingMilliYears, pressure, lastSeenMilliYear) -> {
            CompoundTag row = new CompoundTag();
            row.putLong("RegionKey", regionKey);
            row.putInt("CultureKey", cultureKey);
            row.putInt("QualifyingMilliYears", qualifyingMilliYears);
            row.putInt("Pressure", pressure);
            row.putLong("LastSeenMilliYear", lastSeenMilliYear);
            formationRows.add(row);
        });
        tag.put("FormationCandidates", formationRows);

        ListTag crisisRows = new ListTag();
        lifecycle.visitCrises((realmId, qualifyingMilliYears, pressure, lastSeenMilliYear) -> {
            CompoundTag row = new CompoundTag();
            row.putLong("RealmId", realmId);
            row.putInt("QualifyingMilliYears", qualifyingMilliYears);
            row.putInt("Pressure", pressure);
            row.putLong("LastSeenMilliYear", lastSeenMilliYear);
            crisisRows.add(row);
        });
        tag.put("RealmCrises", crisisRows);

        ListTag diplomacyRows = new ListTag();
        diplomacy.visit((firstRealm, secondRealm, status, firstGoal, secondGoal,
                firstTrust, secondTrust, firstGrievances, secondGrievances,
                firstFear, secondFear, firstClaims, secondClaims,
                firstExhaustion, secondExhaustion, firstWarScore, secondWarScore,
                tradeInterdependence, borderFriction, ideologicalDistance,
                commonThreat, truceUntilCycle, lastEvaluationCycle) -> {
            CompoundTag row = new CompoundTag();
            row.putLong("FirstRealm", firstRealm);
            row.putLong("SecondRealm", secondRealm);
            row.putByte("Status", (byte) status.ordinal());
            row.putByte("FirstGoal", (byte) firstGoal.ordinal());
            row.putByte("SecondGoal", (byte) secondGoal.ordinal());
            row.putInt("FirstTrust", firstTrust);
            row.putInt("SecondTrust", secondTrust);
            row.putInt("FirstGrievances", firstGrievances);
            row.putInt("SecondGrievances", secondGrievances);
            row.putInt("FirstFear", firstFear);
            row.putInt("SecondFear", secondFear);
            row.putInt("FirstClaims", firstClaims);
            row.putInt("SecondClaims", secondClaims);
            row.putInt("FirstExhaustion", firstExhaustion);
            row.putInt("SecondExhaustion", secondExhaustion);
            row.putInt("FirstWarScore", firstWarScore);
            row.putInt("SecondWarScore", secondWarScore);
            row.putInt("Trade", tradeInterdependence);
            row.putInt("Border", borderFriction);
            row.putInt("Ideology", ideologicalDistance);
            row.putInt("CommonThreat", commonThreat);
            row.putLong("TruceUntil", truceUntilCycle);
            row.putLong("LastEvaluation", lastEvaluationCycle);
            diplomacyRows.add(row);
        });
        tag.put("Diplomacy", diplomacyRows);

        ListTag dependencyRows = new ListTag();
        dependencies.visit((subjectRealm, overlordRealm, autonomy, tributeRate, militaryLevy, sinceCycle) -> {
            CompoundTag row = new CompoundTag();
            row.putLong("SubjectRealm", subjectRealm);
            row.putLong("OverlordRealm", overlordRealm);
            row.putInt("Autonomy", autonomy);
            row.putInt("TributeRate", tributeRate);
            row.putInt("MilitaryLevy", militaryLevy);
            row.putLong("SinceCycle", sinceCycle);
            dependencyRows.add(row);
        });
        tag.put("Dependencies", dependencyRows);

        ListTag historyRows = new ListTag();
        history.visit((realmId, phase, scale, capacity, burden, viability, expansion,
                crisisMomentum, recoveryMomentum, crisisRate, recoveryRate, reasonMask,
                foundedMilliYear, phaseSinceMilliYear, lastEvaluationMilliYear) -> {
            CompoundTag row = new CompoundTag();
            row.putLong("RealmId", realmId);
            row.putByte("Phase", (byte) phase.ordinal());
            row.putByte("Scale", (byte) scale.ordinal());
            row.putInt("Capacity", capacity);
            row.putInt("Burden", burden);
            row.putInt("Viability", viability);
            row.putInt("Expansion", expansion);
            row.putInt("CrisisMomentum", crisisMomentum);
            row.putInt("RecoveryMomentum", recoveryMomentum);
            row.putInt("CrisisRate", crisisRate);
            row.putInt("RecoveryRate", recoveryRate);
            row.putInt("ReasonMask", reasonMask);
            row.putLong("FoundedMilliYear", foundedMilliYear);
            row.putLong("PhaseSinceMilliYear", phaseSinceMilliYear);
            row.putLong("LastEvaluationMilliYear", lastEvaluationMilliYear);
            row.putLong("LastSecessionMilliYear", history.lastSecessionMilliYear(realmId));
            historyRows.add(row);
        });
        tag.put("History", historyRows);

        ListTag metadata = new ListTag();
        visitMetadata((realmId, name, taxRate, treasury, legacy) -> {
            CompoundTag row = new CompoundTag();
            row.putLong("RealmId", realmId);
            row.putString("Name", name);
            row.putInt("TaxRate", taxRate);
            row.putLong("Treasury", treasury);
            row.putInt("CapturedSettlements", capturedSettlementCount(realmId));
            row.putBoolean("Legacy", legacy);
            row.putByte("StatePriority", (byte) statePriority(realmId).ordinal());
            row.putInt("StateDecisionPressure", stateDecisionPressure(realmId));
            row.putInt("StateInvestmentPermille", stateInvestmentPermille(realmId));
            row.putLong("LastStateDecisionMilliYear", lastStateDecisionMilliYear(realmId));
            metadata.add(row);
        });
        tag.put("Metadata", metadata);
        return tag;
    }

    static RealmSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        int schemaVersion = tag.getInt("SchemaVersion");
        if (schemaVersion < 1 || schemaVersion > SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported Realm schema " + schemaVersion);
        }
        RealmKeyTable keys = new RealmKeyTable(MAX_SUBJECTS);
        ListTag subjects = tag.getList("Subjects", Tag.TAG_COMPOUND);
        if (subjects.size() > MAX_SUBJECTS) {
            throw new IllegalArgumentException("Too many Realm subjects");
        }
        for (int row = 0; row < subjects.size(); row++) {
            CompoundTag subject = subjects.getCompound(row);
            keys.restore(
                    new UUID(subject.getLong("Most"), subject.getLong("Least")),
                    subject.getByte("Kind"));
        }

        RealmRegistry registry = new RealmRegistry(MAX_REALMS, MAX_MEMBERS);
        ListTag realms = tag.getList("Realms", Tag.TAG_COMPOUND);
        if (realms.size() > MAX_REALMS) throw new IllegalArgumentException("Too many Realms");
        for (int row = 0; row < realms.size(); row++) {
            CompoundTag realm = realms.getCompound(row);
            int government = Byte.toUnsignedInt(realm.getByte("Government"));
            long capital = realm.getLong("CapitalMemberId");
            if (government >= GovernmentForm.values().length
                    || !keys.valid(capital)
                    || keys.kind(capital) != RealmKeyTable.SETTLEMENT) {
                throw new IllegalArgumentException("Invalid restored Realm row " + row);
            }
            registry.restoreRealm(
                    realm.getLong("RealmId"),
                    capital,
                    realm.getLong("FoundedCycle"),
                    GovernmentForm.values()[government],
                    realm.getInt("Legitimacy"));
        }

        ListTag members = tag.getList("Members", Tag.TAG_COMPOUND);
        if (members.size() > MAX_MEMBERS) throw new IllegalArgumentException("Too many Realm members");
        for (int row = 0; row < members.size(); row++) {
            CompoundTag member = members.getCompound(row);
            int kind = Byte.toUnsignedInt(member.getByte("Kind"));
            long memberId = member.getLong("MemberId");
            long controllerId = member.getLong("ControllerId");
            if (kind >= RealmMemberKind.values().length
                    || !keys.valid(memberId)
                    || controllerId != 0L
                            && (!keys.valid(controllerId) || keys.kind(controllerId) != RealmKeyTable.PLAYER)) {
                throw new IllegalArgumentException("Invalid restored Realm member row " + row);
            }
            RealmMemberKind memberKind = RealmMemberKind.values()[kind];
            byte expectedSubjectKind = memberKind == RealmMemberKind.PLAYER
                    ? RealmKeyTable.PLAYER
                    : RealmKeyTable.SETTLEMENT;
            if (keys.kind(memberId) != expectedSubjectKind) {
                throw new IllegalArgumentException("Realm member/subject kind mismatch at row " + row);
            }
            registry.restoreMember(
                    member.getLong("RealmId"),
                    memberId,
                    memberKind,
                    controllerId,
                    member.getInt("Influence"));
        }
        registry.finishRestore(tag.getLong("NextRealmId"), tag.getLong("RegistryRevision"));

        RealmInstitutionLedger institutions = new RealmInstitutionLedger(MAX_REALMS);
        ListTag institutionRows = tag.getList("Institutions", Tag.TAG_COMPOUND);
        if (institutionRows.size() > MAX_REALMS) {
            throw new IllegalArgumentException("Too many Realm institution rows");
        }
        for (int row = 0; row < institutionRows.size(); row++) {
            CompoundTag entry = institutionRows.getCompound(row);
            long realmId = entry.getLong("RealmId");
            int government = Byte.toUnsignedInt(entry.getByte("Government"));
            if (!registry.exists(realmId) || government >= GovernmentForm.values().length) {
                throw new IllegalArgumentException("Invalid Realm institution row " + row);
            }
            institutions.restore(
                    realmId,
                    new Constitution(
                            GovernmentForm.values()[government],
                            entry.getInt("Centralization"),
                            entry.getInt("Bureaucracy"),
                            entry.getInt("NoblePower"),
                            entry.getInt("MerchantPower"),
                            entry.getInt("CitizenPower"),
                            entry.getInt("MarketFreedom"),
                            entry.getInt("LandConcentration"),
                            entry.getInt("Militarization"),
                            entry.getInt("Legitimacy")),
                    entry.contains("StableMilliYears")
                            ? entry.getInt("StableMilliYears")
                            : saturatedHistoricalMigration(entry.getInt("StableCycles")),
                    entry.contains("LastEvaluationMilliYear")
                            ? entry.getLong("LastEvaluationMilliYear")
                            : saturatedHistoricalMigration(entry.getLong("LastEvaluationCycle")));
        }
        institutions.restoreRevision(Math.max(0L, tag.getLong("InstitutionRevision")));

        RealmLifecycleLedger lifecycle = new RealmLifecycleLedger(MAX_MEMBERS, MAX_REALMS);
        ListTag formationRows = tag.getList("FormationCandidates", Tag.TAG_COMPOUND);
        if (formationRows.size() > MAX_MEMBERS) {
            throw new IllegalArgumentException("Too many Realm formation candidates");
        }
        for (int row = 0; row < formationRows.size(); row++) {
            CompoundTag entry = formationRows.getCompound(row);
            int qualifyingMilliYears = entry.contains("QualifyingMilliYears")
                    ? entry.getInt("QualifyingMilliYears")
                    : saturatedHistoricalMigration(entry.getInt("QualifyingCycles"));
            long lastSeenMilliYear = entry.contains("LastSeenMilliYear")
                    ? entry.getLong("LastSeenMilliYear")
                    : saturatedHistoricalMigration(entry.getLong("LastSeenCycle"));
            lifecycle.restoreFormation(
                    entry.getLong("RegionKey"),
                    entry.getInt("CultureKey"),
                    qualifyingMilliYears,
                    entry.getInt("Pressure"),
                    lastSeenMilliYear);
        }
        ListTag crisisRows = tag.getList("RealmCrises", Tag.TAG_COMPOUND);
        if (crisisRows.size() > MAX_REALMS) {
            throw new IllegalArgumentException("Too many Realm crisis rows");
        }
        for (int row = 0; row < crisisRows.size(); row++) {
            CompoundTag entry = crisisRows.getCompound(row);
            long realmId = entry.getLong("RealmId");
            if (!registry.exists(realmId)) {
                throw new IllegalArgumentException("Realm crisis references unknown Realm");
            }
            int qualifyingMilliYears = entry.contains("QualifyingMilliYears")
                    ? entry.getInt("QualifyingMilliYears")
                    : saturatedHistoricalMigration(entry.getInt("QualifyingCycles"));
            long lastSeenMilliYear = entry.contains("LastSeenMilliYear")
                    ? entry.getLong("LastSeenMilliYear")
                    : saturatedHistoricalMigration(entry.getLong("LastSeenCycle"));
            lifecycle.restoreCrisis(
                    realmId,
                    qualifyingMilliYears,
                    entry.getInt("Pressure"),
                    lastSeenMilliYear);
        }
        lifecycle.restoreRevision(Math.max(0L, tag.getLong("LifecycleRevision")));

        RealmDiplomacyLedger diplomacy = new RealmDiplomacyLedger(MAX_RELATIONS);
        ListTag diplomacyRows = tag.getList("Diplomacy", Tag.TAG_COMPOUND);
        if (diplomacyRows.size() > MAX_RELATIONS) {
            throw new IllegalArgumentException("Too many Realm diplomatic relations");
        }
        for (int row = 0; row < diplomacyRows.size(); row++) {
            CompoundTag entry = diplomacyRows.getCompound(row);
            long firstRealm = entry.getLong("FirstRealm");
            long secondRealm = entry.getLong("SecondRealm");
            int status = Byte.toUnsignedInt(entry.getByte("Status"));
            int firstGoal = Byte.toUnsignedInt(entry.getByte("FirstGoal"));
            int secondGoal = Byte.toUnsignedInt(entry.getByte("SecondGoal"));
            if (!registry.exists(firstRealm)
                    || !registry.exists(secondRealm)
                    || firstRealm >= secondRealm
                    || status >= DiplomaticStatus.values().length
                    || firstGoal >= WarGoal.values().length
                    || secondGoal >= WarGoal.values().length) {
                throw new IllegalArgumentException("Invalid Realm diplomatic relation at row " + row);
            }
            diplomacy.restore(
                    firstRealm,
                    secondRealm,
                    DiplomaticStatus.values()[status],
                    WarGoal.values()[firstGoal],
                    WarGoal.values()[secondGoal],
                    entry.getInt("FirstTrust"),
                    entry.getInt("SecondTrust"),
                    entry.getInt("FirstGrievances"),
                    entry.getInt("SecondGrievances"),
                    entry.getInt("FirstFear"),
                    entry.getInt("SecondFear"),
                    entry.getInt("FirstClaims"),
                    entry.getInt("SecondClaims"),
                    entry.getInt("FirstExhaustion"),
                    entry.getInt("SecondExhaustion"),
                    entry.getInt("FirstWarScore"),
                    entry.getInt("SecondWarScore"),
                    entry.getInt("Trade"),
                    entry.getInt("Border"),
                    entry.getInt("Ideology"),
                    entry.getInt("CommonThreat"),
                    entry.getLong("TruceUntil"),
                    entry.getLong("LastEvaluation"));
        }
        diplomacy.restoreRevision(Math.max(0L, tag.getLong("DiplomacyRevision")));

        RealmDependencyLedger dependencies = new RealmDependencyLedger(MAX_DEPENDENCIES);
        if (schemaVersion >= 2) {
            ListTag dependencyRows = tag.getList("Dependencies", Tag.TAG_COMPOUND);
            if (dependencyRows.size() > MAX_DEPENDENCIES) {
                throw new IllegalArgumentException("Too many Realm dependencies");
            }
            for (int row = 0; row < dependencyRows.size(); row++) {
                CompoundTag entry = dependencyRows.getCompound(row);
                long subjectRealm = entry.getLong("SubjectRealm");
                long overlordRealm = entry.getLong("OverlordRealm");
                if (!registry.exists(subjectRealm) || !registry.exists(overlordRealm)) {
                    throw new IllegalArgumentException("Realm dependency references unknown Realm");
                }
                dependencies.restore(
                        subjectRealm,
                        overlordRealm,
                        entry.getInt("Autonomy"),
                        entry.getInt("TributeRate"),
                        entry.getInt("MilitaryLevy"),
                        entry.getLong("SinceCycle"));
            }
            dependencies.restoreRevision(Math.max(0L, tag.getLong("DependencyRevision")));
        }

        RealmHistoryLedger history = new RealmHistoryLedger(MAX_REALMS);
        if (schemaVersion >= 3) {
            ListTag historyRows = tag.getList("History", Tag.TAG_COMPOUND);
            if (historyRows.size() > MAX_REALMS) {
                throw new IllegalArgumentException("Too many Realm history rows");
            }
            for (int row = 0; row < historyRows.size(); row++) {
                CompoundTag entry = historyRows.getCompound(row);
                long realmId = entry.getLong("RealmId");
                int phase = Byte.toUnsignedInt(entry.getByte("Phase"));
                int scale = Byte.toUnsignedInt(entry.getByte("Scale"));
                if (!registry.exists(realmId)
                        || phase >= RealmHistoricalPhase.values().length
                        || scale >= RealmScale.values().length) {
                    throw new IllegalArgumentException("Invalid Realm history row " + row);
                }
                history.restore(
                        realmId,
                        RealmHistoricalPhase.values()[phase],
                        RealmScale.values()[scale],
                        entry.getInt("Capacity"),
                        entry.getInt("Burden"),
                        entry.getInt("Viability"),
                        entry.getInt("Expansion"),
                        entry.getInt("CrisisMomentum"),
                        entry.getInt("RecoveryMomentum"),
                        entry.getInt("CrisisRate"),
                        entry.getInt("RecoveryRate"),
                        entry.getInt("ReasonMask"),
                        entry.getLong("FoundedMilliYear"),
                        entry.getLong("PhaseSinceMilliYear"),
                        entry.getLong("LastEvaluationMilliYear"),
                        entry.contains("LastSecessionMilliYear")
                                ? entry.getLong("LastSecessionMilliYear")
                                : -1L);
            }
            history.restoreRevision(Math.max(0L, tag.getLong("HistoryRevision")));
        }

        RealmSavedData data = new RealmSavedData(
                keys, registry, institutions, lifecycle, diplomacy, dependencies, history);
        ListTag metadata = tag.getList("Metadata", Tag.TAG_COMPOUND);
        if (metadata.size() > MAX_REALMS) throw new IllegalArgumentException("Too much Realm metadata");
        for (int row = 0; row < metadata.size(); row++) {
            CompoundTag entry = metadata.getCompound(row);
            long realmId = entry.getLong("RealmId");
            data.upsertMetadata(
                    realmId,
                    entry.getString("Name"),
                    entry.getInt("TaxRate"),
                    entry.getLong("Treasury"),
                    entry.getInt("CapturedSettlements"),
                    entry.getBoolean("Legacy"));
            if (schemaVersion >= 4 && entry.contains("StatePriority")) {
                int priority = Byte.toUnsignedInt(entry.getByte("StatePriority"));
                if (priority >= RealmStatePriority.values().length) {
                    throw new IllegalArgumentException("Invalid Realm state priority row " + row);
                }
                data.restoreStateDecision(
                        realmId,
                        RealmStatePriority.values()[priority],
                        entry.getInt("StateDecisionPressure"),
                        entry.getInt("StateInvestmentPermille"),
                        entry.getLong("LastStateDecisionMilliYear"));
            }
        }
        long restoredMetadataRevision = Math.max(0L, tag.getLong("MetadataRevision"));
        data.metadataRevision = Math.max(restoredMetadataRevision, data.metadataRevision);
        return data;
    }

    private void restoreStateDecision(
            long realmId,
            RealmStatePriority priority,
            int pressure,
            int investmentPermille,
            long milliYear) {
        if (priority == null || pressure < 0 || pressure > 1000
                || investmentPermille < 0 || investmentPermille > 1000 || milliYear < -1L) {
            throw new IllegalArgumentException("Invalid restored Realm state decision");
        }
        int row = findMetadata(realmId);
        if (row < 0) throw new IllegalArgumentException("Missing Realm metadata for state decision");
        statePriorities[row] = (byte) priority.ordinal();
        stateDecisionPressures[row] = pressure;
        stateInvestmentPermille[row] = investmentPermille;
        lastStateDecisionMilliYears[row] = milliYear;
    }

    private int findMetadata(long realmId) {
        for (int row = 0; row < metadataSize; row++) {
            if (metadataRealmIds[row] == realmId) return row;
        }
        return -1;
    }

    private void ensureMetadataCapacity(int required) {
        if (required <= metadataRealmIds.length) return;
        int capacity = Math.min(
                MAX_REALMS,
                Math.max(required, metadataRealmIds.length + Math.max(1, metadataRealmIds.length >>> 1)));
        int oldCapacity = metadataRealmIds.length;
        metadataRealmIds = Arrays.copyOf(metadataRealmIds, capacity);
        names = Arrays.copyOf(names, capacity);
        taxRates = Arrays.copyOf(taxRates, capacity);
        treasuries = Arrays.copyOf(treasuries, capacity);
        capturedSettlements = Arrays.copyOf(capturedSettlements, capacity);
        legacyFlags = Arrays.copyOf(legacyFlags, capacity);
        statePriorities = Arrays.copyOf(statePriorities, capacity);
        stateDecisionPressures = Arrays.copyOf(stateDecisionPressures, capacity);
        stateInvestmentPermille = Arrays.copyOf(stateInvestmentPermille, capacity);
        lastStateDecisionMilliYears = Arrays.copyOf(lastStateDecisionMilliYears, capacity);
        Arrays.fill(lastStateDecisionMilliYears, oldCapacity, capacity, -1L);
    }

    private void changedMetadata() {
        if (metadataRevision == Long.MAX_VALUE) {
            throw new IllegalStateException("Realm metadata revision exhausted");
        }
        metadataRevision++;
    }

    private static int saturatedHistoricalMigration(int oldCycles) {
        if (oldCycles <= 0) return 0;
        return oldCycles > Integer.MAX_VALUE / 1000
                ? Integer.MAX_VALUE
                : oldCycles * 1000;
    }

    private static long saturatedHistoricalMigration(long oldCycle) {
        if (oldCycle <= 0L) return 0L;
        return oldCycle > Long.MAX_VALUE / 1000L
                ? Long.MAX_VALUE
                : oldCycle * 1000L;
    }

    private static String validateName(String value) {
        if (value == null) throw new NullPointerException("name");
        String name = value.strip();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("Realm name length outside 1.." + MAX_NAME_LENGTH);
        }
        for (int index = 0; index < name.length(); index++) {
            if (Character.isISOControl(name.charAt(index))) {
                throw new IllegalArgumentException("Realm name contains control characters");
            }
        }
        return name;
    }

    @FunctionalInterface
    public interface MetadataVisitor {
        void accept(long realmId, String name, int taxRate, long treasury, boolean legacy);
    }
}
