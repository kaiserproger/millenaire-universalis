package ru.kaiserroman.millenairearmies.integration.millenaire;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.millenaire.ReputationConstants;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillagerType;
import org.millenaire.entity.MillVillager;
import org.millenaire.hire.HireConstants;
import org.millenaire.hire.HiringHelper;
import org.millenaire.item.MoneyHelper;
import org.millenaire.village.Village;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillagerRecord;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.network.ArmiesProtocol;
import ru.kaiserroman.millenairearmies.persistence.PackedUnitMembership;
import ru.kaiserroman.millenairearmies.persistence.PackedCommandState;
import ru.kaiserroman.millenairearmies.persistence.RealmGovernanceSavedData;
import ru.kaiserroman.millenairearmies.persistence.PackedGarrisonState;
import ru.kaiserroman.millenairearmies.persistence.PackedLogisticsState;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandAuthority;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandService;
import ru.kaiserroman.millenairearmies.server.service.StrategicArmyOrder;
import ru.kaiserroman.millenairearmies.server.unit.PackedUnitRoleState;

/**
 * Server-authoritative player flow for raising armies from controlled Millenaire settlements.
 *
 * <p>All world reads, inventory charges and packed mutations happen on the Minecraft server
 * thread. A client supplies intent only: the server resolves the selected village or resident,
 * derives its faction, checks Millenaire ownership/reputation, and commits membership atomically.
 * Controlled settlements pay through their town hall; independent retinues use Millenaire's own
 * one-day hire, reputation and denier APIs. No entity is spawned, cloned or teleported here.</p>
 */
public final class MillenaireRecruitmentService {
    public static final long NOT_RUNNING = -20L;
    public static final long PERMISSION_DENIED = -21L;
    public static final long ARMY_NOT_FOUND = -22L;
    public static final long VILLAGER_NOT_LOADED = -23L;
    public static final long VILLAGE_NOT_FOUND = -24L;
    public static final long VILLAGER_NOT_IN_VILLAGE = -25L;
    public static final long WRONG_FACTION = -26L;
    public static final long VILLAGER_UNAVAILABLE = -27L;
    public static final long UNIT_LIMIT_REACHED = -28L;
    public static final long SETTLEMENT_NOT_CONTROLLED = -29L;
    public static final long REPUTATION_TOO_LOW = -30L;
    public static final long ALREADY_RECRUITED = -31L;
    public static final long ARMY_FULL = -32L;
    public static final long VILLAGER_BUSY = -33L;
    public static final long NOT_MILITARY = -34L;
    public static final long LEDGER_UNAVAILABLE = -35L;
    public static final long INSUFFICIENT_RESOURCES = -36L;
    public static final long NOT_RECRUITED = -37L;
    public static final long ARMY_LIMIT_REACHED = -38L;
    public static final long INVALID_COUNT = -39L;
    public static final long WRONG_DIMENSION = -40L;
    public static final long SUPPLY_SHORTAGE = -41L;
    public static final long NOT_HIREABLE = -42L;
    public static final long INSUFFICIENT_FUNDS = -43L;
    public static final long HIRED_BY_OTHER = -44L;

    private static final int MAX_UNIT_ROWS = 1 << 20;
    private static final int HIRE_CLEANUP_ROWS_PER_TICK = 32;

    private final MillenaireVillageIndex villageIndex;
    private final MillenaireVillageIndex.Cursor villageCursor;
    private final MillenaireEntityBridge entityBridge;
    private final ArmyCommandService commandService;
    private final SettlementRecruitmentLedger ledger;
    private final long[] candidateMost = new long[ArmiesConfig.MAX_UNITS_PER_ARMY];
    private final long[] candidateLeast = new long[ArmiesConfig.MAX_UNITS_PER_ARMY];
    private final long[] candidatePositions = new long[ArmiesConfig.MAX_UNITS_PER_ARMY];
    private final long[] candidateDistances = new long[ArmiesConfig.MAX_UNITS_PER_ARMY];
    private final MillVillager[] candidateEntities = new MillVillager[ArmiesConfig.MAX_UNITS_PER_ARMY];

    private MinecraftServer server;
    private PackedArmyEcs ecs;
    private PackedUnitMembership memberships;
    private RealmGovernanceSavedData governance;
    private final RealmGovernanceSavedData.AssignmentView governanceAssignment =
            new RealmGovernanceSavedData.AssignmentView();
    private RecruitmentRoster roster;
    private PackedUnitRoleState unitRoles;
    private ArmyCommandService.DirtyMarker dirtyMarker;
    private RecruitmentFactionPolicy factionPolicy = RecruitmentFactionPolicy.DENY_ALL;
    private RecruitmentSupplyPolicy supplyPolicy = RecruitmentSupplyPolicy.ALLOW_ALL;
    private int nextHireCleanupRow;

    public MillenaireRecruitmentService(
            MillenaireVillageIndex villageIndex,
            MillenaireEntityBridge entityBridge,
            ArmyCommandService commandService) {
        this(villageIndex, entityBridge, commandService, new MillenaireSettlementRecruitmentLedger());
    }

    public MillenaireRecruitmentService(
            MillenaireVillageIndex villageIndex,
            MillenaireEntityBridge entityBridge,
            ArmyCommandService commandService,
            SettlementRecruitmentLedger ledger) {
        this.villageIndex = Objects.requireNonNull(villageIndex, "villageIndex");
        this.villageCursor = villageIndex.newCursor();
        this.entityBridge = Objects.requireNonNull(entityBridge, "entityBridge");
        this.commandService = Objects.requireNonNull(commandService, "commandService");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
    }

    public boolean start(
            MinecraftServer startingServer,
            PackedArmyEcs persistedEcs,
            PackedUnitMembership persistedMemberships,
            PackedCommandState persistedCommands,
            PackedLogisticsState persistedLogistics,
            PackedGarrisonState persistedGarrisons,
            ArmyCommandService.DirtyMarker persistedDirtyMarker) {
        Objects.requireNonNull(startingServer, "startingServer");
        if (server == startingServer) {
            return false;
        }
        if (server != null) {
            throw new IllegalStateException("Recruitment service is already attached to another server");
        }
        Objects.requireNonNull(persistedEcs, "persistedEcs");
        Objects.requireNonNull(persistedMemberships, "persistedMemberships");
        Objects.requireNonNull(persistedCommands, "persistedCommands");
        Objects.requireNonNull(persistedLogistics, "persistedLogistics");
        Objects.requireNonNull(persistedGarrisons, "persistedGarrisons");
        Objects.requireNonNull(persistedDirtyMarker, "persistedDirtyMarker");
        if (!commandService.isRunning() || commandService.ecs() != persistedEcs
                || commandService.controllers() == null) {
            throw new IllegalStateException(
                    "Army command service must own the same persisted stores before recruitment starts");
        }
        server = startingServer;
        ecs = persistedEcs;
        memberships = persistedMemberships;
        governance = RealmGovernanceSavedData.get(startingServer);
        dirtyMarker = persistedDirtyMarker;
        roster = new RecruitmentRoster(
                persistedEcs,
                persistedMemberships,
                commandService.controllers(),
                persistedCommands,
                persistedLogistics,
                persistedGarrisons,
                ArmiesConfig.MAX_UNITS_PER_ARMY,
                persistedDirtyMarker,
                RecruitmentUnitReleaseListener.NOOP);
        nextHireCleanupRow = 0;
        return true;
    }

    public void stop(MinecraftServer stoppingServer) {
        if (server != stoppingServer) {
            return;
        }
        server = null;
        ecs = null;
        memberships = null;
        governance = null;
        roster = null;
        unitRoles = null;
        dirtyMarker = null;
        factionPolicy = RecruitmentFactionPolicy.DENY_ALL;
        clearCandidates();
        supplyPolicy = RecruitmentSupplyPolicy.ALLOW_ALL;
        nextHireCleanupRow = 0;
    }

    public void installFactionPolicy(RecruitmentFactionPolicy installedPolicy) {
        if (server != null) {
            requireServerThread();
        }
        factionPolicy = Objects.requireNonNull(installedPolicy, "installedPolicy");
    }

    public void installReleaseListener(RecruitmentUnitReleaseListener installedListener) {
        if (server == null || roster == null) {
            throw new IllegalStateException("Recruitment service is not running");
        }
        requireServerThread();
        roster.releaseListener(Objects.requireNonNull(installedListener, "installedListener"));
    }

    public void installUnitRoleState(PackedUnitRoleState installedState) {
        if (server == null || roster == null || dirtyMarker == null) {
            throw new IllegalStateException("Recruitment service is not running");
        }
        requireServerThread();
        unitRoles = Objects.requireNonNull(installedState, "installedState");
    }

    public void installSupplyPolicy(RecruitmentSupplyPolicy installedPolicy) {
        if (server != null) {
            requireServerThread();
        }
        supplyPolicy = Objects.requireNonNull(installedPolicy, "installedPolicy");
    }

    /** Raises a new controlled army from the nearest eligible settlement (admin command path). */
    public long formArmy(
            ArmyCommandAuthority authority,
            ServerLevel level,
            BlockPos actorPosition,
            int desiredUnits) {
        Village village = nearestVillage(level, actorPosition);
        if (village == null || village.getId() == null || village.getId().uuid() == null
                || village.getCenter() == null) {
            return VILLAGE_NOT_FOUND;
        }
        UUID villageId = village.getId().uuid();
        int faction = factionPolicy.factionForVillage(
                villageId.getMostSignificantBits(), villageId.getLeastSignificantBits());
        return formArmyAtVillage(
                authority,
                level,
                actorPosition,
                villageId.getMostSignificantBits(),
                villageId.getLeastSignificantBits(),
                faction,
                village.getCenter().asLong(),
                desiredUnits);
    }

    /** Raises a new army from the exact settlement selected in the player ledger. */
    public long formArmyAtVillage(
            ArmyCommandAuthority authority,
            ServerLevel level,
            BlockPos actorPosition,
            long villageUuidMost,
            long villageUuidLeast,
            int expectedFaction,
            long expectedPosition,
            int desiredUnits) {
        long ready = prepareIdentity(authority);
        if (ready != 0L) {
            return ready;
        }
        if (level == null || level.getServer() != server) {
            return WRONG_DIMENSION;
        }
        if (actorPosition == null || desiredUnits <= 0 || desiredUnits > ArmiesConfig.MAX_UNITS_PER_ARMY) {
            return INVALID_COUNT;
        }
        Village village = villageIndex.find(villageUuidMost, villageUuidLeast);
        if (village == null || village.getId() == null || villageIndex.level(village.getId()) != level
                || village.getCenter() == null || village.getCenter().asLong() != expectedPosition
                || !withinVillageRadius(actorPosition, village)) {
            return VILLAGE_NOT_FOUND;
        }
        long settlementFailure = validateControlledSettlement(authority, level, village);
        if (settlementFailure != 0L) {
            return settlementFailure;
        }
        VillageId villageId = village.getId();
        int faction = factionPolicy.factionForVillage(
                villageId.uuid().getMostSignificantBits(), villageId.uuid().getLeastSignificantBits());
        if (faction < 0 || faction != expectedFaction) {
            return WRONG_FACTION;
        }
        int candidates = collectCandidates(village, actorPosition, desiredUnits);
        if (candidates < desiredUnits) {
            clearCandidates();
            return VILLAGER_UNAVAILABLE;
        }
        if ((long) ecs.unitSize() + desiredUnits > MAX_UNIT_ROWS) {
            clearCandidates();
            return UNIT_LIMIT_REACHED;
        }

        int charged = ledger.debit(level, village, 1, desiredUnits);
        if (charged < 0) {
            clearCandidates();
            return ledgerFailure(charged);
        }
        if (!consumeRecruitmentKits(village, desiredUnits)) {
            ledger.refund(level, village, charged);
            clearCandidates();
            return SUPPLY_SHORTAGE;
        }
        long created = commandService.createArmyForVerifiedSettlementOwner(
                authority,
                faction,
                StrategicArmyOrder.HOLD,
                village.getCenter().asLong(),
                level.dimension().location());
        if (created < 0L) {
            refundRecruitmentKits(village, desiredUnits);
            ledger.refund(level, village, charged);
            clearCandidates();
            return creationFailure(created);
        }
        int armyHandle = (int) created;
        for (int index = 0; index < desiredUnits; index++) {
            long recruited = roster.recruit(
                    authority,
                    armyHandle,
                    candidateMost[index],
                    candidateLeast[index],
                    candidatePositions[index]);
            if (recruited < 0L) {
                roster.disband(authority, armyHandle);
                refundRecruitmentKits(village, desiredUnits);
                ledger.refund(level, village, charged);
                clearCandidates();
                return recruited;
            }
            classifyRecruit(recruited, PackedUnitRoleState.TROOP_CLASS_LEVY);
        }
        clearCandidates();
        return created;
    }

    /** Recruits exactly {@code requested} nearest available fighters or mutates nothing. */
    public long recruitNearest(
            ArmyCommandAuthority authority,
            int armyHandle,
            ServerLevel level,
            BlockPos actorPosition,
            int requested) {
        long ready = prepareArmy(authority, armyHandle);
        if (ready != 0L) {
            return ready;
        }
        if (level == null || level.getServer() != server) {
            return WRONG_DIMENSION;
        }
        if (actorPosition == null || requested <= 0 || requested > ArmiesConfig.MAX_UNITS_PER_ARMY) {
            return INVALID_COUNT;
        }
        if ((long) ecs.armyUnitCount(armyHandle) + requested > ArmiesConfig.MAX_UNITS_PER_ARMY) {
            return ARMY_FULL;
        }
        if ((long) ecs.unitSize() + requested > MAX_UNIT_ROWS) {
            return UNIT_LIMIT_REACHED;
        }
        Village village = nearestVillage(level, actorPosition);
        long settlementFailure = validateControlledSettlement(authority, level, village);
        if (settlementFailure != 0L) {
            return settlementFailure;
        }
        if (!armyMatchesVillage(armyHandle, village)) {
            return WRONG_FACTION;
        }
        int candidates = collectCandidates(village, actorPosition, requested);
        if (candidates < requested) {
            clearCandidates();
            return VILLAGER_UNAVAILABLE;
        }

        int charged = ledger.debit(level, village, 0, requested);
        if (charged < 0) {
            clearCandidates();
            return ledgerFailure(charged);
        }
        if (!consumeRecruitmentKits(village, requested)) {
            ledger.refund(level, village, charged);
            clearCandidates();
            return SUPPLY_SHORTAGE;
        }
        int committed = 0;
        for (; committed < requested; committed++) {
            long result = roster.recruit(
                    authority,
                    armyHandle,
                    candidateMost[committed],
                    candidateLeast[committed],
                    candidatePositions[committed]);
            if (result < 0L) {
                for (int rollback = committed - 1; rollback >= 0; rollback--) {
                    roster.release(authority, armyHandle, candidateMost[rollback], candidateLeast[rollback]);
                }
                refundRecruitmentKits(village, requested);
                ledger.refund(level, village, charged);
                clearCandidates();
                return result;
            }
            classifyRecruit(result, PackedUnitRoleState.TROOP_CLASS_LEVY);
        }
        clearCandidates();
        return committed;
    }

    /** Recruits one concrete, already loaded entity selected by the command source. */
    public long recruitTarget(
            ArmyCommandAuthority authority,
            int armyHandle,
            ServerLevel actorLevel,
            BlockPos actorPosition,
            MillVillager villager) {
        long ready = prepareArmy(authority, armyHandle);
        if (ready != 0L) {
            return ready;
        }
        if (actorLevel == null || actorLevel.getServer() != server || actorPosition == null) {
            return WRONG_DIMENSION;
        }
        if (villager == null || villager.isRemoved() || !villager.isAlive()
                || villager.level() != actorLevel) {
            return VILLAGER_NOT_LOADED;
        }
        Village village = entityBridge.villageFor(villager);
        long settlementFailure = validateControlledSettlement(authority, actorLevel, village);
        if (settlementFailure != 0L) {
            return settlementFailure;
        }
        if (!withinVillageRadius(actorPosition, village)) {
            return VILLAGE_NOT_FOUND;
        }
        if (!armyMatchesVillage(armyHandle, village)) {
            return WRONG_FACTION;
        }
        long recordFailure = validateLoadedRecord(village, villager);
        if (recordFailure != 0L) {
            return recordFailure;
        }
        if (ecs.armyUnitCount(armyHandle) >= ArmiesConfig.MAX_UNITS_PER_ARMY) {
            return ARMY_FULL;
        }
        UUID uuid = villager.getUUID();
        if (memberships.unitHandleForUuid(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()) != 0) {
            return ALREADY_RECRUITED;
        }
        int charged = ledger.debit(actorLevel, village, 0, 1);
        if (charged < 0) {
            return ledgerFailure(charged);
        }
        if (!consumeRecruitmentKits(village, 1)) {
            ledger.refund(actorLevel, village, charged);
            return SUPPLY_SHORTAGE;
        }
        long recruited = roster.recruit(
                authority,
                armyHandle,
                uuid.getMostSignificantBits(),
                uuid.getLeastSignificantBits(),
                villager.blockPosition().asLong());
        if (recruited < 0L) {
            refundRecruitmentKits(village, 1);
            ledger.refund(actorLevel, village, charged);
        } else {
            classifyRecruit(recruited, PackedUnitRoleState.TROOP_CLASS_LEVY);
        }
        return recruited;
    }

    /** Compatibility entry point; still performs the same loaded/owned settlement checks. */
    public long recruitLoaded(
            ArmyCommandAuthority authority,
            int armyHandle,
            long villagerUuidMost,
            long villagerUuidLeast) {
        long ready = prepareArmy(authority, armyHandle);
        if (ready != 0L) {
            return ready;
        }
        MillVillager villager = entityBridge.findLoaded(villagerUuidMost, villagerUuidLeast);
        if (villager == null || !(villager.level() instanceof ServerLevel level)) {
            return VILLAGER_NOT_LOADED;
        }
        return recruitTarget(authority, armyHandle, level, villager.blockPosition(), villager);
    }

    /**
     * UI adapter for one explicitly selected loaded fighter. The player's actual level and
     * position remain authoritative, so a forged UUID cannot bypass the normal proximity,
     * settlement-control, faction, availability, ledger or supply checks in {@link
     * #recruitTarget}.
     */
    public long recruitSelected(
            ArmyCommandAuthority authority,
            int armyHandle,
            ServerLevel actorLevel,
            BlockPos actorPosition,
            long villageUuidMost,
            long villageUuidLeast,
            long villagerUuidMost,
            long villagerUuidLeast) {
        MillVillager villager = entityBridge.findLoaded(villagerUuidMost, villagerUuidLeast);
        Village village = villager == null ? null : entityBridge.villageFor(villager);
        if (village == null || village.getId() == null || village.getId().uuid() == null
                || village.getId().uuid().getMostSignificantBits() != villageUuidMost
                || village.getId().uuid().getLeastSignificantBits() != villageUuidLeast) {
            return VILLAGER_NOT_IN_VILLAGE;
        }
        return recruitTarget(authority, armyHandle, actorLevel, actorPosition, villager);
    }

    /** Unloaded records are never recruited; this method remains as a fail-closed API boundary. */
    public long recruitRecord(
            ArmyCommandAuthority authority,
            int armyHandle,
            long villageUuidMost,
            long villageUuidLeast,
            UUID villagerUuid) {
        long ready = prepareArmy(authority, armyHandle);
        if (ready != 0L) {
            return ready;
        }
        if (villagerUuid == null || villageIndex.find(villageUuidMost, villageUuidLeast) == null) {
            return VILLAGE_NOT_FOUND;
        }
        return VILLAGER_NOT_LOADED;
    }

    /** Reconciles a real Millenaire death into the packed army roster. */
    public boolean casualty(MillVillager villager) {
        if (server == null || roster == null || villager == null || villager.getUUID() == null) {
            return false;
        }
        requireServerThread();
        UUID uuid = villager.getUUID();
        return roster.releaseCasualty(
                uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
    }

    public long release(
            ArmyCommandAuthority authority,
            int armyHandle,
            long villagerUuidMost,
            long villagerUuidLeast) {
        long ready = prepareArmy(authority, armyHandle);
        return ready != 0L
                ? ready
                : roster.release(authority, armyHandle, villagerUuidMost, villagerUuidLeast);
    }

    public long disband(ArmyCommandAuthority authority, int armyHandle) {
        long ready = prepareIdentity(authority);
        if (ready != 0L) {
            return ready;
        }
        return roster.disband(authority, armyHandle);
    }

    /** Lists available loaded fighters in the controlled settlement nearest the source. */
    public long visitEligible(
            ArmyCommandAuthority authority,
            ServerLevel level,
            BlockPos actorPosition,
            EligibleVillagerSink sink) {
        long ready = prepareIdentity(authority);
        if (ready != 0L) {
            return ready;
        }
        if (level == null || level.getServer() != server || actorPosition == null || sink == null) {
            return WRONG_DIMENSION;
        }
        Village village = nearestVillage(level, actorPosition);
        long settlementFailure = validateControlledSettlement(authority, level, village);
        if (settlementFailure != 0L) {
            return settlementFailure;
        }
        int count = collectCandidates(village, actorPosition, ArmiesConfig.MAX_UNITS_PER_ARMY);
        for (int index = 0; index < count; index++) {
            MillVillager villager = candidateEntities[index];
            VillageId id = village.getId();
            sink.accept(
                    villager,
                    village.getVillageName(),
                    id.uuid().getMostSignificantBits(),
                    id.uuid().getLeastSignificantBits(),
                    candidateDistances[index]);
        }
        clearCandidates();
        return count;
    }

    /** Projects nearby controlled enlistment and independent one-day hire options for the UI. */
    public long visitRecruitmentOptions(ServerPlayer player, RecruitmentOptionSink sink) {
        if (player == null || sink == null) {
            return PERMISSION_DENIED;
        }
        ArmyCommandAuthority authority = ArmyCommandAuthority.player(
                player.getUUID(), player.hasPermissions(2));
        long ready = prepareIdentity(authority);
        if (ready != 0L) {
            return ready;
        }
        ServerLevel level = player.serverLevel();
        BlockPos actorPosition = player.blockPosition();
        int funds = MoneyHelper.getTotalDeniers(player.getInventory());
        int projected = 0;
        for (villageCursor.reset(); villageCursor.advance(); ) {
            Village village = villageCursor.village();
            if (villageCursor.level() != level || !visibleRecruitmentVillage(village, actorPosition)) {
                continue;
            }
            VillageId id = village.getId();
            UUID villageId = id.uuid();
            int faction = factionPolicy.factionForVillage(
                    villageId.getMostSignificantBits(), villageId.getLeastSignificantBits());
            if (faction < 0) {
                continue;
            }
            boolean controlled = validateControlledSettlement(authority, level, village) == 0L;
            sink.settlement(village, faction, controlled);
            int reputation = village.getCombinedReputation(level, player.getUUID());
            for (VillagerRecord record : village.getVillagerRecords().values()) {
                if (projected == Integer.MAX_VALUE || record == null || record.getUuid() == null) {
                    continue;
                }
                UUID uuid = record.getUuid();
                MillVillager villager = entityBridge.findLoaded(
                        uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
                int option = recruitmentOption(
                        player.getUUID(), village, record, villager, controlled, reputation, funds);
                if (option < 0) {
                    continue;
                }
                VillagerType type = ModCultures.getVillagerType(record.getVillagerTypeId());
                int cost = type == null ? 0 : HiringHelper.hireCost(
                        type.hiringCost(), village.isControlledBy(player.getUUID()));
                sink.recruit(
                        villager,
                        village,
                        option,
                        Math.max(0, cost),
                        reputation,
                        ReputationConstants.HIRE_REPUTATION_THRESHOLD);
                projected++;
            }
        }
        return projected;
    }

    /** Pays or reuses Millenaire's one-day hire and atomically creates/joins the player's retinue. */
    public long hireRecruit(ServerPlayer player, long villagerUuidMost, long villagerUuidLeast) {
        if (player == null) {
            return PERMISSION_DENIED;
        }
        ArmyCommandAuthority authority = ArmyCommandAuthority.player(
                player.getUUID(), player.hasPermissions(2));
        long ready = prepareIdentity(authority);
        if (ready != 0L) {
            return ready;
        }
        MillVillager villager = entityBridge.findLoaded(villagerUuidMost, villagerUuidLeast);
        if (villager == null || villager.level() != player.serverLevel()
                || villager.distanceToSqr(player) > (double) ArmiesConfig.RECRUITMENT_VILLAGE_RADIUS
                        * ArmiesConfig.RECRUITMENT_VILLAGE_RADIUS) {
            return VILLAGER_NOT_LOADED;
        }
        Village village = entityBridge.villageFor(villager);
        VillagerRecord record = village == null ? null : village.getVillagerRecord(villager.getUUID());
        if (village == null || record == null) {
            return VILLAGER_NOT_IN_VILLAGE;
        }
        int reputation = village.getCombinedReputation(player.serverLevel(), player.getUUID());
        int funds = MoneyHelper.getTotalDeniers(player.getInventory());
        int option = recruitmentOption(
                player.getUUID(), village, record, villager, false, reputation, funds);
        if (option == ArmiesProtocol.RECRUIT_OPTION_REPUTATION_LOCKED) {
            return REPUTATION_TOO_LOW;
        }
        if (option == ArmiesProtocol.RECRUIT_OPTION_FUNDS_LOCKED) {
            return INSUFFICIENT_FUNDS;
        }
        if (option != ArmiesProtocol.RECRUIT_OPTION_HIRE
                && option != ArmiesProtocol.RECRUIT_OPTION_ASSIGN_HIRED) {
            return option < 0 ? option : NOT_HIREABLE;
        }

        VillagerType type = ModCultures.getVillagerType(record.getVillagerTypeId());
        int cost = HiringHelper.hireCost(type.hiringCost(), village.isControlledBy(player.getUUID()));
        boolean paid = option == ArmiesProtocol.RECRUIT_OPTION_HIRE;
        if (paid && !MoneyHelper.removeDeniers(player.getInventory(), cost)) {
            return INSUFFICIENT_FUNDS;
        }
        if (paid) {
            village.setVillagerHired(
                    player.serverLevel(),
                    villager.getUUID(),
                    player.getUUID(),
                    player.serverLevel().getGameTime() + HireConstants.HIRE_DURATION_TICKS);
        }

        UUID villageId = village.getId().uuid();
        int faction = factionPolicy.factionForVillage(
                villageId.getMostSignificantBits(), villageId.getLeastSignificantBits());
        if (faction < 0) {
            rollbackHire(player, village, villager, paid, cost);
            return WRONG_FACTION;
        }
        int armyHandle = findRetinueArmy(authority, faction);
        boolean created = false;
        if (armyHandle == 0) {
            long createdHandle = commandService.createArmyForVerifiedSettlementOwner(
                    authority,
                    faction,
                    StrategicArmyOrder.HOLD,
                    villager.blockPosition().asLong(),
                    player.serverLevel().dimension().location());
            if (createdHandle < 0L) {
                rollbackHire(player, village, villager, paid, cost);
                return creationFailure(createdHandle);
            }
            armyHandle = (int) createdHandle;
            created = true;
        }
        long recruited = roster.recruit(
                authority,
                armyHandle,
                villagerUuidMost,
                villagerUuidLeast,
                villager.blockPosition().asLong());
        if (recruited < 0L) {
            if (created) {
                roster.disband(authority, armyHandle);
            }
            rollbackHire(player, village, villager, paid, cost);
            return recruited;
        }
        classifyRecruit(recruited, PackedUnitRoleState.TROOP_CLASS_REGULAR);
        return Integer.toUnsignedLong(armyHandle);
    }

    /** Bounded expiry cleanup for hired fighters; permanent settlement levies are never released. */
    public void tick(MinecraftServer tickingServer) {
        if (server != tickingServer || memberships == null || memberships.size() == 0) {
            nextHireCleanupRow = 0;
            return;
        }
        requireServerThread();
        int work = Math.min(HIRE_CLEANUP_ROWS_PER_TICK, memberships.size());
        for (int processed = 0; processed < work && memberships.size() > 0; processed++) {
            if (nextHireCleanupRow >= memberships.size()) {
                nextHireCleanupRow = 0;
            }
            int row = nextHireCleanupRow;
            int unit = memberships.unitHandleAt(row);
            int army = ecs.isUnitAlive(unit) ? ecs.unitArmy(unit) : PackedArmyEcs.NO_ARMY;
            if (army == PackedArmyEcs.NO_ARMY || !ecs.isArmyAlive(army)
                    || !commandService.controllers().hasController(army)) {
                nextHireCleanupRow++;
                continue;
            }
            MillVillager villager = entityBridge.findLoaded(
                    memberships.uuidMostAt(row), memberships.uuidLeastAt(row));
            if (villager == null) {
                nextHireCleanupRow++;
                continue;
            }
            UUID controller = new UUID(
                    commandService.controllers().uuidMost(army),
                    commandService.controllers().uuidLeast(army));
            Village village = entityBridge.villageFor(villager);
            boolean permanent = village != null && village.getId() != null
                    && (village.isControlledBy(controller)
                            || governance != null && governance.canCommandSettlement(controller, village.getId().uuid()));
            boolean activeHire = villager.isHired()
                    && controller.equals(villager.getHiredBy())
                    && !HiringHelper.isExpired(villager.level().getGameTime(), villager.getHiredUntil());
            if (permanent || activeHire) {
                nextHireCleanupRow++;
                continue;
            }
            long released = roster.release(
                    ArmyCommandAuthority.player(controller, false),
                    army,
                    memberships.uuidMostAt(row),
                    memberships.uuidLeastAt(row));
            if (released < 0L) {
                nextHireCleanupRow++;
            }
        }
    }

    public int membershipCount() {
        return memberships == null ? 0 : memberships.size();
    }

    private long prepareIdentity(ArmyCommandAuthority authority) {
        if (server == null || roster == null || !commandService.isRunning()) {
            return NOT_RUNNING;
        }
        requireServerThread();
        return authority == null || !authority.hasIdentity() ? PERMISSION_DENIED : 0L;
    }

    private long prepareArmy(ArmyCommandAuthority authority, int armyHandle) {
        long ready = prepareIdentity(authority);
        if (ready != 0L) {
            return ready;
        }
        if (!ecs.isArmyAlive(armyHandle)) {
            return ARMY_NOT_FOUND;
        }
        return commandService.canControl(authority, armyHandle) ? 0L : PERMISSION_DENIED;
    }

    private boolean visibleRecruitmentVillage(Village village, BlockPos origin) {
        return village != null
                && village.getId() != null
                && village.getId().uuid() != null
                && village.getCenter() != null
                && village.getCultureId() != null
                && village.getVillageTypeId() != null
                && squaredHorizontalDistance(origin, village.getCenter())
                        <= (long) ArmiesConfig.RECRUITMENT_VILLAGE_RADIUS
                                * ArmiesConfig.RECRUITMENT_VILLAGE_RADIUS;
    }

    private int recruitmentOption(
            UUID player,
            Village village,
            VillagerRecord record,
            MillVillager villager,
            boolean controlled,
            int reputation,
            int funds) {
        if (player == null || village == null || record == null || villager == null
                || villager.isRemoved() || !villager.isAlive() || record.isKilled()
                || !RecruitmentRules.adult(villager.isChild(), record.getChildSize())) {
            return (int) VILLAGER_UNAVAILABLE;
        }
        UUID uuid = record.getUuid();
        if (uuid == null || memberships.unitHandleForUuid(
                uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()) != 0) {
            return (int) ALREADY_RECRUITED;
        }
        VillagerType type = ModCultures.getVillagerType(record.getVillagerTypeId());
        boolean military = type != null
                && record.getMilitaryStrength() > 0
                && (type.isHelpInAttacks() || type.isRaider());
        if (!military) {
            return (int) NOT_MILITARY;
        }
        UUID hiredBy = villager.getHiredBy();
        boolean hiredByPlayer = villager.isHired() && player.equals(hiredBy);
        if (villager.isHired() && !hiredByPlayer) {
            return (int) HIRED_BY_OTHER;
        }
        boolean busy = record.isAwayRaiding() || record.isRaidingVillage()
                || villager.isRaiderEntity() || villager.isSelling()
                || villager.getAttackTarget() != null;
        if (busy && !hiredByPlayer) {
            return (int) VILLAGER_BUSY;
        }
        if (hiredByPlayer) {
            return ArmiesProtocol.RECRUIT_OPTION_ASSIGN_HIRED;
        }
        if (controlled) {
            return ArmiesProtocol.RECRUIT_OPTION_ENLIST;
        }
        if (!HiringHelper.isHireable(type.hiringCost())) {
            return (int) NOT_HIREABLE;
        }
        int cost = HiringHelper.hireCost(type.hiringCost(), village.isControlledBy(player));
        if (reputation < ReputationConstants.HIRE_REPUTATION_THRESHOLD) {
            return ArmiesProtocol.RECRUIT_OPTION_REPUTATION_LOCKED;
        }
        return funds < cost
                ? ArmiesProtocol.RECRUIT_OPTION_FUNDS_LOCKED
                : ArmiesProtocol.RECRUIT_OPTION_HIRE;
    }

    private int findRetinueArmy(ArmyCommandAuthority authority, int faction) {
        PackedArmyEcs.ArmyCursor cursor = ecs.newArmyCursor();
        while (cursor.advance()) {
            int army = cursor.handle();
            if (cursor.faction() == faction
                    && cursor.unitCount() < ArmiesConfig.MAX_UNITS_PER_ARMY
                    && commandService.canControl(authority, army)) {
                return army;
            }
        }
        return 0;
    }

    private static void rollbackHire(
            ServerPlayer player, Village village, MillVillager villager, boolean paid, int cost) {
        if (!paid) {
            return;
        }
        village.setVillagerHired(player.serverLevel(), villager.getUUID(), null, 0L);
        MoneyHelper.addDeniers(player.getInventory(), cost, player);
    }

    private Village nearestVillage(ServerLevel level, BlockPos origin) {
        long bestDistance = (long) ArmiesConfig.RECRUITMENT_VILLAGE_RADIUS
                * ArmiesConfig.RECRUITMENT_VILLAGE_RADIUS;
        Village best = null;
        for (villageCursor.reset(); villageCursor.advance(); ) {
            Village village = villageCursor.village();
            BlockPos center = village == null ? null : village.getCenter();
            if (villageCursor.level() != level || center == null) {
                continue;
            }
            long distance = squaredHorizontalDistance(origin, center);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = village;
            }
        }
        return best;
    }

    private void classifyRecruit(long recruitedUnit, byte troopClass) {
        if (recruitedUnit < 0L || unitRoles == null) return;
        int unitHandle = (int) recruitedUnit;
        if (unitRoles.assignTroopClass(unitHandle, troopClass) && dirtyMarker != null) {
            dirtyMarker.markDirty();
        }
    }

    private boolean consumeRecruitmentKits(Village village, int count) {
        VillageId id = village.getId();
        UUID uuid = id.uuid();
        return supplyPolicy.tryConsumeRecruitmentKits(
                uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(), count);
    }

    private void refundRecruitmentKits(Village village, int count) {
        VillageId id = village.getId();
        UUID uuid = id.uuid();
        supplyPolicy.refundRecruitmentKits(
                uuid.getMostSignificantBits(), uuid.getLeastSignificantBits(), count);
    }

    private long validateControlledSettlement(
            ArmyCommandAuthority authority, ServerLevel level, Village village) {
        if (village == null || village.getId() == null || village.getId().uuid() == null
                || village.getCenter() == null) {
            return VILLAGE_NOT_FOUND;
        }
        UUID playerId = new UUID(authority.uuidMost(), authority.uuidLeast());
        long access = RecruitmentRules.settlementAccess(
                village.isPlayerControlled(),
                village.isControlledBy(playerId),
                village.getCombinedReputation(level, playerId));
        if (access != 0L) {
            return access;
        }
        UUID villageId = village.getId().uuid();
        if (governance != null
                && governance.readPlayer(playerId, governanceAssignment)
                && (governanceAssignment.villageMost() != villageId.getMostSignificantBits()
                        || governanceAssignment.villageLeast() != villageId.getLeastSignificantBits())) {
            return SETTLEMENT_NOT_CONTROLLED;
        }
        return 0L;
    }

    private boolean armyMatchesVillage(int armyHandle, Village village) {
        VillageId id = village.getId();
        return id != null && id.uuid() != null && factionPolicy.villageBelongsToFaction(
                ecs.armyFaction(armyHandle),
                id.uuid().getMostSignificantBits(),
                id.uuid().getLeastSignificantBits());
    }

    private int collectCandidates(Village village, BlockPos origin, int limit) {
        int count = 0;
        if (village == null || village.getVillagerRecords() == null) {
            return 0;
        }
        for (VillagerRecord record : village.getVillagerRecords().values()) {
            if (record == null || record.getUuid() == null) {
                continue;
            }
            UUID uuid = record.getUuid();
            MillVillager entity = entityBridge.findLoaded(
                    uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
            if (entity == null || validateLoadedRecord(village, entity) != 0L
                    || memberships.unitHandleForUuid(
                            uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()) != 0) {
                continue;
            }
            long distance = squaredDistance(origin, entity.blockPosition());
            if (count == limit && (limit == 0 || distance >= candidateDistances[count - 1])) {
                continue;
            }
            int insert = count < limit ? count : count - 1;
            while (insert > 0 && distance < candidateDistances[insert - 1]) {
                candidateMost[insert] = candidateMost[insert - 1];
                candidateLeast[insert] = candidateLeast[insert - 1];
                candidatePositions[insert] = candidatePositions[insert - 1];
                candidateDistances[insert] = candidateDistances[insert - 1];
                candidateEntities[insert] = candidateEntities[insert - 1];
                insert--;
            }
            candidateMost[insert] = uuid.getMostSignificantBits();
            candidateLeast[insert] = uuid.getLeastSignificantBits();
            candidatePositions[insert] = entity.blockPosition().asLong();
            candidateDistances[insert] = distance;
            candidateEntities[insert] = entity;
            if (count < limit) {
                count++;
            }
        }
        return count;
    }

    private long validateLoadedRecord(Village village, MillVillager villager) {
        if (villager == null || villager.isRemoved()) {
            return VILLAGER_NOT_LOADED;
        }
        Village boundVillage = entityBridge.villageFor(villager);
        if (boundVillage != village) {
            return VILLAGER_NOT_IN_VILLAGE;
        }
        UUID uuid = villager.getUUID();
        VillagerRecord record = uuid == null ? null : village.getVillagerRecord(uuid);
        if (record == null || record.getUuid() == null || !record.getUuid().equals(uuid)) {
            return VILLAGER_NOT_IN_VILLAGE;
        }
        VillagerType type = record.getVillagerTypeId() == null
                ? null
                : ModCultures.getVillagerType(record.getVillagerTypeId());
        boolean adult = !record.isKilled()
                && RecruitmentRules.adult(villager.isChild(), record.getChildSize());
        boolean military = type != null && record.getMilitaryStrength() > 0
                && (type.isHelpInAttacks() || type.isRaider());
        boolean busy = record.isAwayHired() || record.getHiredBy() != null
                || record.isAwayRaiding() || record.isRaidingVillage()
                || villager.isHired() || villager.isRaiderEntity() || villager.isSelling()
                || villager.getAttackTarget() != null;
        return RecruitmentRules.candidate(
                true,
                villager.isAlive(),
                adult,
                military,
                busy,
                memberships.unitHandleForUuid(
                        uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()) != 0);
    }

    private boolean withinVillageRadius(BlockPos actorPosition, Village village) {
        return village != null && village.getCenter() != null
                && squaredHorizontalDistance(actorPosition, village.getCenter())
                        <= (long) ArmiesConfig.RECRUITMENT_VILLAGE_RADIUS
                                * ArmiesConfig.RECRUITMENT_VILLAGE_RADIUS;
    }

    private static long squaredDistance(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dy = (long) first.getY() - second.getY();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static long squaredHorizontalDistance(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private static long ledgerFailure(int result) {
        return result == SettlementRecruitmentLedger.INSUFFICIENT_RESOURCES
                ? INSUFFICIENT_RESOURCES
                : LEDGER_UNAVAILABLE;
    }

    private static long creationFailure(long result) {
        return switch ((int) result) {
            case (int) ArmyCommandService.NOT_RUNNING -> NOT_RUNNING;
            case (int) ArmyCommandService.PERMISSION_DENIED -> PERMISSION_DENIED;
            case (int) ArmyCommandService.LIMIT_REACHED -> ARMY_LIMIT_REACHED;
            case (int) ArmyCommandService.INVALID_FACTION -> WRONG_FACTION;
            case (int) ArmyCommandService.INVALID_DIMENSION -> WRONG_DIMENSION;
            default -> result;
        };
    }

    private void clearCandidates() {
        for (int index = 0; index < candidateEntities.length; index++) {
            candidateEntities[index] = null;
        }
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Recruitment must be scheduled on the Minecraft server thread");
        }
    }

    public interface RecruitmentOptionSink {
        void settlement(Village village, int faction, boolean controlled);

        void recruit(
                MillVillager villager,
                Village village,
                int option,
                int cost,
                int reputation,
                int requiredReputation);
    }

    @FunctionalInterface
    public interface EligibleVillagerSink {
        void accept(
                MillVillager villager,
                String villageName,
                long villageUuidMost,
                long villageUuidLeast,
                long squaredDistance);
    }
}
