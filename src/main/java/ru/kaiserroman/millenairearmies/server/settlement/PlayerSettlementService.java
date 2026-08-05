package ru.kaiserroman.millenairearmies.server.settlement;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import org.millenaire.building.BuildingInstance;
import org.millenaire.building.BuildingPlan;
import org.millenaire.building.BuildingPlanSet;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillageType;
import org.millenaire.village.ControlledProjectsService;
import org.millenaire.village.ControlledQueuedProject;
import org.millenaire.village.PlacementSignHelper;
import org.millenaire.village.Village;
import org.millenaire.village.VillageGrowthManager;
import ru.kaiserroman.millenairearmies.integration.millenaire.UniversalisGrowthBridge;
import org.millenaire.village.VillageId;
import org.millenaire.village.VillageManager;
import org.millenaire.village.VillageSavedData;
import org.millenaire.world.BuildingLocationFinder;
import org.millenaire.world.VillageSpawner;
import org.slf4j.Logger;
import ru.kaiserroman.millenaire.realm.DiplomaticStatus;
import ru.kaiserroman.millenaire.realm.RealmMemberKind;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireRealmBuildingPolicy;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireVillageIndex;
import ru.kaiserroman.millenairearmies.persistence.PlayerSettlementCustomizationSavedData;
import ru.kaiserroman.millenairearmies.persistence.PlayerSettlementSavedData;
import ru.kaiserroman.millenairearmies.persistence.RealmKeyTable;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;
import ru.kaiserroman.millenairearmies.server.realm.RealmAdministrationService;

/** Player-owned settlement creation, growth, expanded construction and bounded conquest. */
public final class PlayerSettlementService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int ASSESSMENT_INTERVAL_TICKS = 200;
    private static final int AUTOMATIC_ROWS_PER_INTERVAL = 4;
    private static final int MAX_FOUNDATION_VILLAGE_SCAN = 2_048;
    private static final int MAX_AUTOMATIC_CANDIDATES = 128;
    private static final int PROJECT_REACH_BLOCKS = 160;
    private static final int CAPTURE_REACH_BLOCKS = 64;
    private static final int FOUNDATION_COMPLETION_PERCENT = 100;

    private final MillenaireVillageIndex villageIndex;
    private final RealmSavedData realms;
    private final SimulationSavedData simulation;
    private final RealmAdministrationService administration;
    private final MillenaireRealmBuildingPolicy buildingPolicy = new MillenaireRealmBuildingPolicy();
    private MinecraftServer server;
    private PlayerSettlementSavedData settlements;
    private PlayerSettlementCustomizationSavedData customization;
    private int ticksUntilAssessment;
    private int automaticCursor;
    private long publishedTerritoryRevision = Long.MIN_VALUE;
    private long publishedRealmRevision = Long.MIN_VALUE;
    private long publishedVillageReconciliation = Long.MIN_VALUE;
    private long migratedProfileCount;
    private long foundedCount;
    private long manualProjectCount;
    private long automaticProjectCount;
    private long rejectedCount;

    public PlayerSettlementService(
            MillenaireVillageIndex villageIndex,
            RealmSavedData realms,
            SimulationSavedData simulation,
            RealmAdministrationService administration) {
        if (villageIndex == null || realms == null || administration == null) {
            throw new NullPointerException("Player settlement dependency");
        }
        this.villageIndex = villageIndex;
        this.realms = realms;
        this.simulation = simulation;
        this.administration = administration;
    }

    public void start(MinecraftServer server) {
        if (server == null) throw new NullPointerException("server");
        this.server = server;
        this.settlements = PlayerSettlementSavedData.get(server);
        this.customization = PlayerSettlementCustomizationSavedData.get(server);
        this.publishedTerritoryRevision = Long.MIN_VALUE;
        this.publishedRealmRevision = Long.MIN_VALUE;
        this.publishedVillageReconciliation = Long.MIN_VALUE;
        this.migratedProfileCount = reconcileExistingPlayerRealms();
        publishTerritories();
        this.ticksUntilAssessment = 1;
        this.automaticCursor = 0;
        if (migratedProfileCount != 0L) {
            LOGGER.info(
                    "[BANNEROK_PLAYER_SETTLEMENT_MIGRATION] restored_profiles={}",
                    migratedProfileCount);
        }
    }

    public void stop() {
        PlayerSettlementTerritoryRegistry.clear();
        server = null;
        settlements = null;
        customization = null;
        ticksUntilAssessment = 0;
        automaticCursor = 0;
        publishedTerritoryRevision = Long.MIN_VALUE;
        publishedRealmRevision = Long.MIN_VALUE;
        publishedVillageReconciliation = Long.MIN_VALUE;
    }

    public void tick(long gameTime) {
        if (!active() || --ticksUntilAssessment > 0) return;
        settlements.visit(view -> assess(view, gameTime));
        publishTerritories();
        automaticCursor = customization.visitAutomatic(
                automaticCursor,
                AUTOMATIC_ROWS_PER_INTERVAL,
                (ownerMost, ownerLeast, settlementMost, settlementLeast, profile, queueLimit) ->
                        automaticDevelopment(
                                new UUID(ownerMost, ownerLeast),
                                new UUID(settlementMost, settlementLeast),
                                profile,
                                queueLimit));
        ticksUntilAssessment = ASSESSMENT_INTERVAL_TICKS;
    }

    public PlayerSettlementSavedData profiles() { return settlements; }
    public PlayerSettlementCustomizationSavedData customization() { return customization; }

    private long reconcileExistingPlayerRealms() {
        if (server == null || settlements == null || customization == null) return 0L;
        long currentGameTime = server.overworld().getGameTime();
        long[] restored = {0L};
        realms.registry().visitRealms((realmId, capitalMemberId, foundedCycle, government, legitimacy) -> {
            if (realms.registry().memberKind(capitalMemberId) != RealmMemberKind.PLAYER_SETTLEMENT
                    || !realms.keys().valid(capitalMemberId)
                    || realms.keys().kind(capitalMemberId) != RealmKeyTable.SETTLEMENT) {
                return;
            }
            long controllerId = realms.registry().memberControllerId(capitalMemberId);
            if (!realms.keys().valid(controllerId)
                    || realms.keys().kind(controllerId) != RealmKeyTable.PLAYER) {
                return;
            }
            UUID owner = realms.keys().uuid(controllerId);
            UUID capitalId = realms.keys().uuid(capitalMemberId);
            Village capital = village(capitalId);
            ServerLevel level = level(capitalId);
            if (capital == null || level == null || !capital.isControlledBy(owner)) {
                LOGGER.warn(
                        "[BANNEROK_PLAYER_SETTLEMENT_MIGRATION_SKIPPED] realm={} capital={} owner={} reason=physical_ownership",
                        realmId,
                        capitalId,
                        owner);
                return;
            }
            ResourceLocation villageTypeId = capital.getVillageTypeId();
            if (villageTypeId == null) {
                LOGGER.warn(
                        "[BANNEROK_PLAYER_SETTLEMENT_MIGRATION_SKIPPED] realm={} capital={} owner={} reason=village_type",
                        realmId,
                        capitalId,
                        owner);
                return;
            }
            VillageType type = ModCultures.getVillageType(villageTypeId);
            int baseRadius = type == null
                    ? PlayerSettlementPolicy.MINIMUM_RADIUS
                    : Math.max(
                            PlayerSettlementPolicy.MINIMUM_RADIUS,
                            Math.min(PlayerSettlementPolicy.MAXIMUM_RADIUS, type.radius()));
            long foundedTick = Math.min(
                    currentGameTime,
                    saturatedMultiply(
                            foundedCycle,
                            Math.max(1L, ArmiesConfig.WORLD_SIMULATION_INTERVAL_TICKS)));
            PlayerSettlementSavedData.View profile = new PlayerSettlementSavedData.View();
            boolean hasProfile = settlements.view(owner, profile);
            if (hasProfile && (!capitalId.equals(profile.capital) || profile.realmId != realmId)) {
                LOGGER.error(
                        "[BANNEROK_PLAYER_SETTLEMENT_MIGRATION_CONFLICT] realm={} capital={} owner={} stored_capital={} stored_realm={}",
                        realmId,
                        capitalId,
                        owner,
                        profile.capital,
                        profile.realmId);
                return;
            }
            if (!hasProfile) {
                PlayerSettlementSavedData.View capitalProfile = new PlayerSettlementSavedData.View();
                if (settlements.viewCapital(capitalId, capitalProfile)) {
                    LOGGER.error(
                            "[BANNEROK_PLAYER_SETTLEMENT_MIGRATION_CONFLICT] realm={} capital={} owner={} profile_owner={}",
                            realmId,
                            capitalId,
                            owner,
                            capitalProfile.owner);
                    return;
                }
            }

            PlayerSettlementCustomizationSavedData.View settings =
                    new PlayerSettlementCustomizationSavedData.View();
            boolean hasSettings = customization.read(owner, settings);
            if (hasSettings && !capitalId.equals(settings.settlement())) {
                LOGGER.error(
                        "[BANNEROK_PLAYER_SETTLEMENT_MIGRATION_CONFLICT] realm={} capital={} owner={} customization_capital={}",
                        realmId,
                        capitalId,
                        owner,
                        settings.settlement());
                return;
            }
            if (!hasSettings) {
                PlayerSettlementCustomizationSavedData.View capitalSettings =
                        new PlayerSettlementCustomizationSavedData.View();
                if (customization.readSettlement(capitalId, capitalSettings)) {
                    LOGGER.error(
                            "[BANNEROK_PLAYER_SETTLEMENT_MIGRATION_CONFLICT] realm={} capital={} owner={} customization_owner={}",
                            realmId,
                            capitalId,
                            owner,
                            capitalSettings.owner());
                    return;
                }
            }

            boolean changed = false;
            if (!hasProfile) {
                if (!settlements.register(
                        owner,
                        capitalId,
                        realmId,
                        level.dimension().location(),
                        baseRadius,
                        foundedTick)) {
                    LOGGER.error(
                            "[BANNEROK_PLAYER_SETTLEMENT_MIGRATION_FAILED] realm={} capital={} owner={} store=profile",
                            realmId,
                            capitalId,
                            owner);
                    return;
                }
                changed = true;
            }
            if (!hasSettings) {
                String name = migrationName(realms.name(realmId), capital.getVillageName(), realmId);
                if (!customization.found(owner, capitalId, villageTypeId, name)) {
                    throw new IllegalStateException(
                            "Preflighted player settlement customization migration failed for Realm "
                                    + realmId);
                }
                changed = true;
            }
            if (changed) restored[0]++;
        });
        return restored[0];
    }

    public OperationResult createSettlement(
            ServerPlayer player,
            ResourceLocation villageTypeId,
            String name) {
        if (!active() || player == null || villageTypeId == null || name == null) {
            return reject("invalid_input", "Invalid settlement creation input");
        }
        String validatedName;
        try {
            validatedName = PlayerSettlementCustomizationSavedData.normalizeName(name);
        } catch (RuntimeException invalidName) {
            return reject("invalid_name", invalidName.getMessage());
        }
        UUID owner = player.getUUID();
        PlayerSettlementSavedData.View existing = new PlayerSettlementSavedData.View();
        if (settlements.view(owner, existing) || customization.exists(owner)
                || !administration.canFoundPlayerRealm(owner)) {
            return reject("already_founded", "The player already controls a Realm or settlement");
        }
        if (settlements.size() >= PlayerSettlementSavedData.MAX_SETTLEMENTS
                || customization.size() >= PlayerSettlementCustomizationSavedData.MAX_SETTLEMENTS) {
            return reject("limit_reached", "Configured player settlement limit reached");
        }
        VillageType type = ModCultures.getVillageType(villageTypeId);
        if (type == null || !type.playerControlled() || type.loneBuilding() || type.isMarvel()) {
            return reject("not_player_type", "Village type is absent or not player-controlled: " + villageTypeId);
        }
        if (type.radius() > PlayerSettlementPolicy.MAXIMUM_RADIUS) {
            return reject(
                    "unsupported_radius",
                    "Village type radius exceeds the supported player territory maximum: " + type.radius());
        }
        ServerLevel level = player.serverLevel();
        BlockPos playerPosition = player.blockPosition();
        int surfaceY = level.getHeight(
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                playerPosition.getX(),
                playerPosition.getZ());
        BlockPos position = new BlockPos(playerPosition.getX(), surfaceY, playerPosition.getZ());
        if (!areaLoaded(level, position, type.radius() + 32)) {
            return reject("area_not_loaded", "The complete foundation area must be loaded");
        }
        Component validation = VillageSpawner.validateSite(level, position, type);
        if (validation != null) {
            return reject("invalid_site", validation.getString());
        }

        int maximumSpawnedSettlements = 1 + type.hamlets().size();
        if (!administration.canFoundPlayerRealm(owner, maximumSpawnedSettlements)) {
            return reject(
                    "realm_capacity",
                    "Realm and compatibility registries cannot hold the complete village complex");
        }

        VillageSavedData physicalData = VillageSavedData.get(level);
        VillageManager manager = physicalData.getVillageManager();
        Set<UUID> previous = new HashSet<>();
        for (Village village : manager.getAllVillages()) {
            if (previous.size() >= MAX_FOUNDATION_VILLAGE_SCAN) {
                return reject("scan_limit", "Too many villages are loaded for safe foundation discovery");
            }
            if (village != null && village.getId() != null) previous.add(village.getId().uuid());
        }
        Component spawnMessage = VillageSpawner.spawnVillage(
                level,
                position,
                type,
                FOUNDATION_COMPLETION_PERCENT,
                validatedName,
                null,
                player);
        ArrayList<Village> createdVillages = new ArrayList<>();
        Village capital = null;
        boolean capitalIsRoot = false;
        long capitalDistance = Long.MAX_VALUE;
        int inspected = 0;
        for (Village village : manager.getAllVillages()) {
            if (inspected++ >= MAX_FOUNDATION_VILLAGE_SCAN) break;
            if (village == null || village.getId() == null
                    || previous.contains(village.getId().uuid())) {
                continue;
            }
            createdVillages.add(village);
            if (!villageTypeId.equals(village.getVillageTypeId())
                    || !village.isControlledBy(owner)
                    || village.getCenter() == null) {
                continue;
            }
            boolean root = village.getParentVillageId() == null;
            long distance = horizontalDistanceSquared(position, village.getCenter());
            if (capital == null || root && !capitalIsRoot
                    || root == capitalIsRoot && distance < capitalDistance) {
                capital = village;
                capitalIsRoot = root;
                capitalDistance = distance;
            }
        }
        if (capital == null || createdVillages.isEmpty()) {
            rollbackSpawnRegistrations(manager, physicalData, createdVillages);
            return reject(
                    "spawn_failed",
                    spawnMessage == null ? "Millenaire did not register the new village" : spawnMessage.getString());
        }
        if (!administration.canFoundPlayerRealm(owner, createdVillages.size())) {
            rollbackSpawnRegistrations(manager, physicalData, createdVillages);
            return reject(
                    "realm_capacity",
                    "The generated village complex exceeded the preflight registry capacity");
        }
        for (Village created : createdVillages) {
            UUID settlementId = created.getId().uuid();
            if (realms.realmForSettlement(settlementId) != RealmRegistry.NO_REALM
                    || created != capital
                            && !administration.canAttachFoundedRegion(owner, settlementId)) {
                rollbackSpawnRegistrations(manager, physicalData, createdVillages);
                return reject(
                        "realm_conflict",
                        "A generated settlement cannot be represented safely in Realm governance");
            }
        }

        UUID capitalId = capital.getId().uuid();
        long gameTime = level.getGameTime();
        long realmId = administration.foundPlayerRealm(
                owner,
                capitalId,
                validatedName,
                level.dimension().location(),
                gameTime,
                gameTime / Math.max(1L, ArmiesConfig.WORLD_SIMULATION_INTERVAL_TICKS));
        if (realmId == RealmRegistry.NO_REALM) {
            rollbackSpawnRegistrations(manager, physicalData, createdVillages);
            return reject(
                    "realm_failed",
                    "Realm foundation was rejected; physical village registrations were rolled back");
        }
        long ownerSubject = realms.keys().findPlayer(owner);
        if (ownerSubject == 0L) {
            throw new IllegalStateException("Founded Realm has no canonical player subject");
        }
        for (Village created : createdVillages) {
            UUID settlementId = created.getId().uuid();
            created.setOwner(owner, player.getGameProfile().getName());
            if (created != capital) {
                long settlementSubject = realms.keys().internSettlement(settlementId);
                if (!realms.registry().addMember(
                        realmId,
                        settlementSubject,
                        RealmMemberKind.PLAYER_SETTLEMENT,
                        ownerSubject,
                        700)) {
                    throw new IllegalStateException(
                            "Preflighted child settlement could not join founded Realm: " + settlementId);
                }
                if (!administration.attachFoundedRegion(owner, settlementId)) {
                    throw new IllegalStateException(
                            "Preflighted child settlement could not join compatibility governance: "
                                    + settlementId);
                }
                created.setParentVillageId(capital.getId());
                created.setRelation(capital.getId(), 100);
                capital.setRelation(created.getId(), 100);
                assignSimulationRealm(settlementId, realmId);
            }
            created.markDirty();
        }
        capital.markDirty();
        physicalData.setDirty();
        if (!settlements.register(
                owner, capitalId, realmId, level.dimension().location(), type.radius(), level.getGameTime())) {
            throw new IllegalStateException("Canonical Realm exists but player settlement profile could not be registered");
        }
        if (!customization.found(owner, capitalId, villageTypeId, validatedName)) {
            throw new IllegalStateException("Canonical Realm exists but settlement customization could not be registered");
        }
        villageIndex.reconcile(server);
        assess(profile(owner), level.getGameTime());
        publishTerritories();
        foundedCount++;
        LOGGER.info(
                "[BANNEROK_PLAYER_SETTLEMENT_FOUNDED] owner={} capital={} realm={} type={} radius={} settlements={} name={}",
                owner,
                capitalId,
                realmId,
                villageTypeId,
                type.radius(),
                createdVillages.size(),
                validatedName);
        return OperationResult.success(
                "created",
                "Settlement created: " + validatedName + " (capital=" + capitalId
                        + ", realm=" + realmId + ", settlements=" + createdVillages.size() + ")");
    }

    /** Adopts an existing Millenaire player-controlled village as the player's capital. */
    public OperationResult adoptExisting(ServerPlayer player, UUID capitalId, String realmName) {
        if (!active() || player == null || capitalId == null || realmName == null) {
            return OperationResult.fail("invalid_input", "Invalid settlement adoption input");
        }
        UUID owner = player.getUUID();
        String validatedName;
        try {
            validatedName = PlayerSettlementCustomizationSavedData.normalizeName(realmName);
        } catch (RuntimeException invalidName) {
            return reject("invalid_name", invalidName.getMessage());
        }
        PlayerSettlementSavedData.View existing = new PlayerSettlementSavedData.View();
        if (settlements.view(owner, existing)
                || settlements.viewCapital(capitalId, existing)
                || customization.exists(owner)
                || settlements.size() >= PlayerSettlementSavedData.MAX_SETTLEMENTS
                || customization.size() >= PlayerSettlementCustomizationSavedData.MAX_SETTLEMENTS
                || !administration.canFoundPlayerRealm(owner)) {
            return reject("already_founded", "The player, capital or capacity is already assigned");
        }
        Village capital = villageIndex.find(capitalId.getMostSignificantBits(), capitalId.getLeastSignificantBits());
        ServerLevel level = villageIndex.level(new VillageId(capitalId));
        if (capital == null || level == null || !capital.isControlledBy(owner)) {
            return OperationResult.fail("not_owner", "Capital village is absent or is not controlled by this player");
        }
        if (capital.getParentVillageId() != null) {
            return reject("not_capital", "A child hamlet cannot be adopted as the Realm capital");
        }
        List<Village> family = controlledFamily(level, capital, owner);
        if (family == null) {
            return reject("scan_limit", "Too many villages are loaded for safe family discovery");
        }
        if (!administration.canFoundPlayerRealm(owner, family.size())) {
            return reject("realm_capacity", "Realm registries cannot hold the complete village family");
        }
        for (Village member : family) {
            UUID settlementId = member.getId().uuid();
            if (realms.realmForSettlement(settlementId) != RealmRegistry.NO_REALM
                    || member != capital
                            && !administration.canAttachFoundedRegion(owner, settlementId)) {
                return reject(
                        "realm_conflict",
                        "A village family member already belongs to Realm governance");
            }
        }
        VillageType type = ModCultures.getVillageType(capital.getVillageTypeId());
        if (type == null) return OperationResult.fail("missing_type", "Capital village type is not loaded");
        if (type.radius() > PlayerSettlementPolicy.MAXIMUM_RADIUS) {
            return reject(
                    "unsupported_radius",
                    "Capital village type radius exceeds the supported player territory maximum");
        }
        long gameTime = level.getGameTime();
        long realmId = administration.foundPlayerRealm(
                owner,
                capitalId,
                validatedName,
                level.dimension().location(),
                gameTime,
                gameTime / Math.max(1L, ArmiesConfig.WORLD_SIMULATION_INTERVAL_TICKS));
        if (realmId == RealmRegistry.NO_REALM) {
            return OperationResult.fail("realm_failed", "Realm foundation was rejected");
        }
        long ownerSubject = realms.keys().findPlayer(owner);
        if (ownerSubject == 0L) {
            throw new IllegalStateException("Adopted Realm has no canonical player subject");
        }
        for (Village member : family) {
            UUID settlementId = member.getId().uuid();
            member.setOwner(owner, player.getGameProfile().getName());
            if (member != capital) {
                long settlementSubject = realms.keys().internSettlement(settlementId);
                if (!realms.registry().addMember(
                        realmId,
                        settlementSubject,
                        RealmMemberKind.PLAYER_SETTLEMENT,
                        ownerSubject,
                        700)) {
                    throw new IllegalStateException(
                            "Preflighted adopted child could not join Realm: " + settlementId);
                }
                if (!administration.attachFoundedRegion(owner, settlementId)) {
                    throw new IllegalStateException(
                            "Preflighted adopted child could not join compatibility governance: "
                                    + settlementId);
                }
                member.setParentVillageId(capital.getId());
                member.setRelation(capital.getId(), 100);
                capital.setRelation(member.getId(), 100);
                assignSimulationRealm(settlementId, realmId);
            }
            member.markDirty();
        }
        capital.setVillageName(validatedName);
        capital.markDirty();
        VillageSavedData.get(level).setDirty();
        if (!settlements.register(
                owner, capitalId, realmId, level.dimension().location(), type.radius(), level.getGameTime())) {
            throw new IllegalStateException("Realm foundation/profile registration mismatch");
        }
        if (!customization.found(owner, capitalId, capital.getVillageTypeId(), validatedName)) {
            throw new IllegalStateException("Realm foundation/customization registration mismatch");
        }
        villageIndex.reconcile(server);
        assess(profile(owner), level.getGameTime());
        publishTerritories();
        foundedCount++;
        LOGGER.info(
                "[BANNEROK_PLAYER_SETTLEMENT_ADOPTED] owner={} capital={} realm={} settlements={} name={}",
                owner,
                capitalId,
                realmId,
                family.size(),
                validatedName);
        return OperationResult.success(
                "adopted",
                "Existing village family adopted as Realm " + realmId
                        + " (settlements=" + family.size() + ")");
    }

    public OperationResult queueBuilding(
            ServerPlayer player,
            ResourceLocation planSetId,
            BlockPos clickedPos,
            int rotation) {
        return queueBuilding(player, planSetId, clickedPos, rotation, null);
    }

    public OperationResult queueBuilding(
            ServerPlayer player,
            ResourceLocation planSetId,
            BlockPos clickedPos,
            int rotation,
            String requestedVariant) {
        return queueBuildingIn(player, null, planSetId, clickedPos, rotation, requestedVariant);
    }

    public OperationResult queueBuildingIn(
            ServerPlayer player,
            UUID settlementId,
            ResourceLocation planSetId,
            BlockPos clickedPos,
            int rotation,
            String requestedVariant) {
        if (!active() || player == null || planSetId == null || clickedPos == null
                || rotation < 0 || rotation > 3) {
            return OperationResult.fail("invalid_input", "Invalid building project input");
        }
        PlayerSettlementSavedData.View profile = profile(player.getUUID());
        PlayerSettlementCustomizationSavedData.View settings = customization(player.getUUID());
        if (profile == null || settings == null) {
            return reject("no_settlement", "Create a player settlement first");
        }
        UUID managedSettlement = settlementId == null ? profile.capital : settlementId;
        Village village = village(managedSettlement);
        ServerLevel level = level(managedSettlement);
        if (village == null || level == null || level != player.serverLevel()
                || !mayManageSettlement(player.getUUID(), profile, managedSettlement, village)) {
            return reject("not_owner", "Settlement is unavailable or does not belong to this Realm");
        }
        if (village.getControlledQueue().size() >= settings.queueLimit()) {
            return reject("queue_full", "Configured controlled project queue limit reached");
        }
        if (!player.blockPosition().closerThan(clickedPos, PROJECT_REACH_BLOCKS)) {
            return OperationResult.fail("too_far", "Stand within " + PROJECT_REACH_BLOCKS + " blocks of the project");
        }
        BuildingPlanSet set = ModCultures.getBuildingPlanSet(planSetId);
        if (set == null) return OperationResult.fail("missing_plan", "Unknown building plan set: " + planSetId);
        VillageType baseType = ModCultures.getVillageType(village.getVillageTypeId());
        if (baseType == null || !set.culture().equals(baseType.culture())) {
            return OperationResult.fail("wrong_culture", "Only buildings of the settlement culture can be queued");
        }
        VillageType.LayoutSlot offered = ControlledProjectsService.findOfferedSlot(baseType, planSetId, set);
        boolean extended = offered == null;
        if (extended && (forbiddenByVillageType(baseType, set)
                || !PlayerSettlementPolicy.allowsExtendedPlan(
                        profile.tier,
                        set.category(),
                        set.isTownHall(),
                        set.isSubBuilding(),
                        set.isWallSegment(),
                        set.isGift(),
                        set.price()))) {
            return OperationResult.fail(
                    "locked_plan",
                    "Building is not unlocked at tier " + profile.tier + ": " + planSetId);
        }
        offered = territorySlot(offered, planSetId, set, profile.territoryRadius);
        if (offered == null) return OperationResult.fail("not_offered", "Building could not be offered safely");

        String variant = requestedVariant == null || requestedVariant.isBlank()
                ? set.pickRandomVariant(ThreadLocalRandom.current())
                : requestedVariant;
        if (!set.variants().containsKey(variant)) {
            return reject("invalid_variant", "Unknown variant " + variant + " for " + planSetId);
        }
        BuildingPlanSet.LevelDef levelZero = set.getLevel(variant, 0);
        BuildingPlan plan = levelZero == null ? null : ModCultures.getBuildingPlan(levelZero.planId());
        if (plan == null) return OperationResult.fail("missing_level", "Building has no valid level-0 plan");
        BuildingLocationFinder.AnchorEvaluation evaluation = UniversalisGrowthBridge.validateLocationAt(
                level,
                village,
                set,
                plan,
                offered,
                clickedPos,
                rotation,
                profile.territoryRadius);
        if (evaluation == null) {
            return reject(
                    "area_not_loaded",
                    "Load a continuous area around the settlement before validating this project");
        }
        if (!evaluation.isSuccess()) {
            String reason = evaluation.reason() == null ? "unknown" : evaluation.reason().name().toLowerCase(Locale.ROOT);
            return OperationResult.fail("invalid_location", "Building location rejected: " + reason);
        }
        ControlledQueuedProject project = new ControlledQueuedProject(
                planSetId, variant, 0, evaluation.location());
        if (!village.enqueueControlledProject(project)) {
            return OperationResult.fail("queue_full", "Controlled project queue is full");
        }
        village.setNoProjectsLeftUntil(0L);
        VillageGrowthManager.evaluateGrowth(level, village);
        village.markDirty();
        VillageSavedData.get(level).setDirty();
        PlacementSignHelper.placeCornerSigns(level, plan, evaluation.location(), set.nativeName());
        manualProjectCount++;
        LOGGER.info(
                "[BANNEROK_PLAYER_SETTLEMENT_PROJECT] owner={} settlement={} plan={} tier={} radius={} extended={}",
                player.getUUID(), managedSettlement, planSetId, profile.tier, profile.territoryRadius, extended);
        return OperationResult.success(
                "queued",
                "Queued " + set.nativeName() + " at " + evaluation.location().position()
                        + (extended ? " from the expanded catalog" : ""));
    }

    public OperationResult queueNextBuilding(
            ServerPlayer player,
            ResourceLocation planSetId,
            String requestedVariant) {
        return queueNextBuildingIn(player, null, planSetId, requestedVariant);
    }

    public OperationResult queueNextBuildingIn(
            ServerPlayer player,
            UUID settlementId,
            ResourceLocation planSetId,
            String requestedVariant) {
        if (!active() || player == null || planSetId == null) {
            return reject("invalid_input", "Invalid building queue input");
        }
        PlayerSettlementSavedData.View profile = profile(player.getUUID());
        PlayerSettlementCustomizationSavedData.View settings = customization(player.getUUID());
        if (profile == null || settings == null) {
            return reject("no_settlement", "Create a player settlement first");
        }
        UUID managedSettlement = settlementId == null ? profile.capital : settlementId;
        Village village = village(managedSettlement);
        ServerLevel level = level(managedSettlement);
        if (village == null || level == null || level != player.serverLevel()
                || !mayManageSettlement(player.getUUID(), profile, managedSettlement, village)) {
            return reject("not_owner", "Settlement is unavailable or does not belong to this Realm");
        }
        if (village.getControlledQueue().size() >= settings.queueLimit()) {
            return reject("queue_full", "Configured controlled project queue limit reached");
        }
        BuildingPlanSet set = ModCultures.getBuildingPlanSet(planSetId);
        VillageType baseType = ModCultures.getVillageType(village.getVillageTypeId());
        if (set == null || baseType == null || !set.culture().equals(baseType.culture())) {
            return reject("missing_plan", "Unknown or wrong-culture building plan set: " + planSetId);
        }
        VillageType.LayoutSlot offered = ControlledProjectsService.findOfferedSlot(baseType, planSetId, set);
        boolean extended = offered == null;
        if (extended && (forbiddenByVillageType(baseType, set)
                || !PlayerSettlementPolicy.allowsExtendedPlan(
                        profile.tier,
                        set.category(),
                        set.isTownHall(),
                        set.isSubBuilding(),
                        set.isWallSegment(),
                        set.isGift(),
                        set.price()))) {
            return reject("locked_plan", "Building is not unlocked at tier " + profile.tier);
        }
        offered = territorySlot(offered, planSetId, set, profile.territoryRadius);
        if (offered == null || reachedMaximum(village, set)) {
            return reject("not_offered", "Building is unavailable or already at maxCount");
        }
        String variant = requestedVariant == null || requestedVariant.isBlank()
                ? set.variants().keySet().stream().sorted().findFirst().orElse(null)
                : requestedVariant;
        if (variant == null || !set.variants().containsKey(variant)) {
            return reject("invalid_variant", "Unknown variant for " + planSetId);
        }
        BuildingPlanSet.LevelDef levelZero = set.getLevel(variant, 0);
        BuildingPlan plan = levelZero == null ? null : ModCultures.getBuildingPlan(levelZero.planId());
        if (plan == null) return reject("missing_level", "Building has no valid level-0 plan");
        UniversalisGrowthBridge.PlacementSession placement =
                UniversalisGrowthBridge.begin(level, village, profile.territoryRadius);
        if (placement == null) {
            return reject(
                    "area_not_loaded",
                    "Load a continuous area around the settlement before searching for a project site");
        }
        var location = placement.findLocation(set, plan, offered);
        if (location == null) {
            return reject("no_location", "No valid location was found inside the loaded settlement territory");
        }
        ControlledQueuedProject project = new ControlledQueuedProject(planSetId, variant, 0, location);
        if (!village.enqueueControlledProject(project)) {
            return reject("queue_full", "Millenaire rejected the controlled project");
        }
        village.setNoProjectsLeftUntil(0L);
        VillageGrowthManager.evaluateGrowth(level, village);
        village.markDirty();
        VillageSavedData.get(level).setDirty();
        manualProjectCount++;
        LOGGER.info(
                "[BANNEROK_PLAYER_SETTLEMENT_PROJECT] owner={} settlement={} plan={} variant={} tier={} auto_location=true",
                player.getUUID(), managedSettlement, planSetId, variant, profile.tier);
        return OperationResult.success(
                "queued",
                "Queued " + set.nativeName() + " variant=" + variant + " at " + location.position());
    }

    public OperationResult rename(ServerPlayer player, String requestedName) {
        if (!active() || player == null || requestedName == null) {
            return reject("invalid_input", "Invalid settlement rename input");
        }
        String name;
        try {
            name = PlayerSettlementCustomizationSavedData.normalizeName(requestedName);
        } catch (RuntimeException invalidName) {
            return reject("invalid_name", invalidName.getMessage());
        }
        PlayerSettlementSavedData.View profile = profile(player.getUUID());
        Village capital = profile == null ? null : village(profile.capital);
        ServerLevel level = profile == null ? null : level(profile.capital);
        if (profile == null || capital == null || level == null
                || !mayManageSettlement(player.getUUID(), profile, profile.capital, capital)) {
            return reject("no_settlement", "Create a player settlement first");
        }
        if (!administration.renamePlayerRealm(player.getUUID(), name)) {
            return reject("realm_rejected", "Canonical Realm rename was rejected");
        }
        if (!customization.rename(player.getUUID(), name)) {
            throw new IllegalStateException("Realm renamed but settlement customization row disappeared");
        }
        capital.setVillageName(name);
        capital.markDirty();
        VillageSavedData.get(level).setDirty();
        return OperationResult.success("renamed", "Settlement display name changed to " + name);
    }

    public OperationResult setProfile(ServerPlayer player, PlayerSettlementProfile profile) {
        if (!active() || player == null || profile == null) {
            return reject("invalid_input", "Invalid development profile input");
        }
        return customization.setProfile(player.getUUID(), profile)
                ? OperationResult.success("profile", "Development profile set to " + profile.name().toLowerCase(Locale.ROOT))
                : reject("no_settlement", "Create a player settlement first");
    }

    public OperationResult setAutomatic(ServerPlayer player, boolean enabled) {
        if (!active() || player == null) return reject("invalid_input", "Invalid player");
        return customization.setAutomatic(player.getUUID(), enabled)
                ? OperationResult.success("automatic", "Automatic development " + (enabled ? "enabled" : "disabled"))
                : reject("no_settlement", "Create a player settlement first");
    }

    public OperationResult setQueueLimit(ServerPlayer player, int limit) {
        if (!active() || player == null) return reject("invalid_input", "Invalid player");
        try {
            return customization.setQueueLimit(player.getUUID(), limit)
                    ? OperationResult.success("queue_limit", "Controlled project queue limit set to " + limit)
                    : reject("no_settlement", "Create a player settlement first");
        } catch (IllegalArgumentException invalidLimit) {
            return reject("invalid_limit", invalidLimit.getMessage());
        }
    }

    public OperationResult clearQueue(ServerPlayer player) {
        if (!active() || player == null) return reject("invalid_input", "Invalid player");
        PlayerSettlementSavedData.View profile = profile(player.getUUID());
        if (profile == null) return reject("no_settlement", "Create a player settlement first");
        Village village = village(profile.capital);
        ServerLevel level = level(profile.capital);
        if (village == null || level == null || !village.isControlledBy(player.getUUID())) {
            return reject("not_owner", "Capital is unavailable or ownership changed");
        }
        int cleared = village.getControlledQueue().size();
        boolean cancelledPending = village.getPendingProject() != null;
        village.getControlledQueue().clear();
        village.setPendingProject(null);
        village.setNoProjectsLeftUntil(0L);
        village.markDirty();
        VillageSavedData.get(level).setDirty();
        return OperationResult.success(
                "cleared",
                "Cleared " + cleared + " controlled projects"
                        + (cancelledPending ? " and cancelled the pending project" : ""));
    }

    public List<ResourceLocation> villageTypes(int limit) {
        int bounded = Math.max(1, Math.min(128, limit));
        return ModCultures.getAllVillageTypes().values().stream()
                .filter(type -> type.playerControlled() && !type.loneBuilding() && !type.isMarvel())
                .map(VillageType::id)
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .limit(bounded)
                .toList();
    }

    public List<UUID> managedSettlements(UUID owner, int limit) {
        PlayerSettlementSavedData.View profile = profile(owner);
        if (profile == null) return List.of();
        int bounded = Math.max(1, Math.min(512, limit));
        ArrayList<UUID> result = new ArrayList<>();
        realms.registry().visitMembers(profile.realmId, (memberId, kind, controllerId, influence) -> {
            if (result.size() >= bounded || kind == RealmMemberKind.PLAYER) return;
            UUID settlementId = realms.keys().uuid(memberId);
            Village village = village(settlementId);
            if (village != null && village.isControlledBy(owner)) result.add(settlementId);
        });
        result.sort(Comparator.comparing(UUID::toString));
        return List.copyOf(result);
    }

    public List<ResourceLocation> catalog(UUID owner, int limit) {
        PlayerSettlementSavedData.View profile = profile(owner);
        return profile == null ? List.of() : catalogIn(owner, profile.capital, limit);
    }

    public List<ResourceLocation> catalogIn(UUID owner, UUID settlementId, int limit) {
        PlayerSettlementSavedData.View profile = profile(owner);
        Village village = village(settlementId);
        if (profile == null || village == null
                || !mayManageSettlement(owner, profile, settlementId, village)) {
            return List.of();
        }
        VillageType type = ModCultures.getVillageType(village.getVillageTypeId());
        if (type == null) return List.of();
        int bounded = Math.max(1, Math.min(512, limit));
        ArrayList<ResourceLocation> result = new ArrayList<>();
        ModCultures.getAllBuildingPlanSets().entrySet().stream()
                .filter(entry -> entry.getValue().culture().equals(type.culture()))
                .filter(entry -> {
                    BuildingPlanSet set = entry.getValue();
                    boolean offered = ControlledProjectsService.findOfferedSlot(
                            type, entry.getKey(), set) != null;
                    return offered || !forbiddenByVillageType(type, set)
                            && PlayerSettlementPolicy.allowsExtendedPlan(
                                    profile.tier,
                                    set.category(),
                                    set.isTownHall(),
                                    set.isSubBuilding(),
                                    set.isWallSegment(),
                                    set.isGift(),
                                    set.price());
                })
                .map(java.util.Map.Entry::getKey)
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .limit(bounded)
                .forEach(result::add);
        return List.copyOf(result);
    }

    public OperationResult capture(ServerPlayer player, UUID targetId) {
        if (!active() || player == null || targetId == null) {
            return OperationResult.fail("invalid_input", "Invalid capture input");
        }
        PlayerSettlementSavedData.View profile = profile(player.getUUID());
        if (profile == null || profile.tier != PlayerSettlementTier.CITY_STATE) {
            return OperationResult.fail("not_city_state", "Only a developed city-state can capture settlements");
        }
        Village capital = village(profile.capital);
        ServerLevel capitalLevel = level(profile.capital);
        Village target = village(targetId);
        ServerLevel targetLevel = level(targetId);
        if (capital == null || capitalLevel == null || target == null || targetLevel == null
                || targetId.equals(profile.capital)) {
            return OperationResult.fail("missing_target", "Target settlement is absent or is the capital");
        }
        if (capitalLevel != targetLevel || targetLevel != player.serverLevel()
                || !player.blockPosition().closerThan(target.getCenter(), CAPTURE_REACH_BLOCKS)) {
            return OperationResult.fail("too_far", "Stand within " + CAPTURE_REACH_BLOCKS + " blocks of the target town hall");
        }
        int conquestDistance = PlayerSettlementPolicy.conquestDistance(profile.territoryRadius);
        if (!capital.getCenter().closerThan(target.getCenter(), conquestDistance)) {
            return OperationResult.fail("outside_frontier", "Target is outside the city-state frontier of " + conquestDistance + " blocks");
        }
        UUID targetOwner = target.getOwnerUUID();
        if (targetOwner != null && !targetOwner.equals(player.getUUID())) {
            return OperationResult.fail("player_owned", "Another player's settlement cannot be captured by this operation");
        }

        long playerSubject = realms.keys().findPlayer(player.getUUID());
        long playerRealm = realms.registry().realmOfMember(playerSubject);
        if (playerSubject == 0L || playerRealm != profile.realmId || playerRealm == RealmRegistry.NO_REALM) {
            return OperationResult.fail("realm_mismatch", "Player settlement and canonical Realm disagree");
        }
        if (!administration.canRecordCapture(player.getUUID(), targetId)) {
            return reject(
                    "compatibility_capacity",
                    "Compatibility governance cannot safely represent another captured region");
        }
        if (!target.isControlledBy(player.getUUID()) && !target.isUnderAttack()) {
            return reject(
                    "not_occupied",
                    "The target must be under an active physical attack before annexation");
        }
        long targetSubject = realms.keys().findSettlement(targetId);
        long targetRealm = targetSubject == 0L
                ? RealmRegistry.NO_REALM
                : realms.registry().realmOfMember(targetSubject);
        if (targetRealm == playerRealm) return OperationResult.fail("already_owned", "Settlement already belongs to this Realm");

        if (targetRealm != RealmRegistry.NO_REALM) {
            long cycle = targetLevel.getGameTime()
                    / Math.max(1L, ArmiesConfig.WORLD_SIMULATION_INTERVAL_TICKS);
            DiplomaticStatus status = realms.diplomacy().status(playerRealm, targetRealm, cycle);
            if (status != DiplomaticStatus.WAR) {
                return reject("not_at_war", "A Realm-owned settlement can only be captured during war");
            }
            if (realms.registry().capitalMemberId(targetRealm) == targetSubject) {
                if (realms.registry().settlementCount(targetRealm) > 1 || realms.registry().hasPlayerMembers(targetRealm)) {
                    return OperationResult.fail(
                            "major_capital",
                            "A multi-settlement or player Realm capital requires siege/peace-treaty resolution");
                }
                dissolveNpcRealm(targetRealm);
                targetRealm = RealmRegistry.NO_REALM;
            }
        }

        if (targetSubject == 0L) {
            if (realms.keys().size() >= RealmSavedData.MAX_SUBJECTS
                    || realms.registry().memberCount() >= RealmSavedData.MAX_MEMBERS) {
                return reject("realm_capacity", "Canonical Realm registry is full");
            }
            targetSubject = realms.keys().internSettlement(targetId);
        } else if (targetRealm == RealmRegistry.NO_REALM
                && realms.registry().memberCount() >= RealmSavedData.MAX_MEMBERS) {
            return reject("realm_capacity", "Canonical Realm member registry is full");
        }
        boolean transferred = targetRealm == RealmRegistry.NO_REALM
                ? realms.registry().addMember(
                        playerRealm, targetSubject, RealmMemberKind.PLAYER_SETTLEMENT, playerSubject, 650)
                : realms.registry().updateMember(
                        targetSubject, playerRealm, RealmMemberKind.PLAYER_SETTLEMENT, playerSubject, 650);
        if (!transferred) return OperationResult.fail("transfer_failed", "Canonical Realm transfer was rejected");
        target.setOwner(player.getUUID(), player.getGameProfile().getName());
        target.setParentVillageId(capital.getId());
        target.setRelation(capital.getId(), 100);
        capital.setRelation(target.getId(), 100);
        target.setUnderAttack(false);
        target.clearRaid();
        target.markDirty();
        capital.markDirty();
        VillageSavedData.get(targetLevel).setDirty();
        assignSimulationRealm(targetId, playerRealm);
        if (!administration.recordCapture(player.getUUID(), targetId)) {
            throw new IllegalStateException("Committed capture could not update Realm metadata");
        }
        realms.markChanged();
        assess(profile(player.getUUID()), targetLevel.getGameTime());
        publishTerritories();
        LOGGER.info(
                "[BANNEROK_PLAYER_SETTLEMENT_CAPTURE] owner={} realm={} target={} radius={} tier={}",
                player.getUUID(), playerRealm, targetId, profile.territoryRadius, profile.tier);
        return OperationResult.success("captured", "Settlement captured into city-state Realm " + playerRealm);
    }

    public Status status(UUID owner) {
        PlayerSettlementSavedData.View profile = profile(owner);
        PlayerSettlementCustomizationSavedData.View settings = customization(owner);
        if (profile == null || settings == null) return null;
        Village capital = village(profile.capital);
        int buildings = capital == null ? 0 : capital.getBuildings().size();
        long population = population(profile.capital, capital);
        int queued = capital == null ? 0 : capital.getControlledQueue().size();
        boolean pending = capital != null && capital.getPendingProject() != null;
        return new Status(
                settings.name(),
                settings.villageType(),
                profile.capital,
                profile.realmId,
                profile.tier,
                profile.territoryRadius,
                profile.development,
                buildings,
                population,
                realms.capturedSettlementCount(profile.realmId),
                settings.profile(),
                settings.automatic(),
                settings.queueLimit(),
                queued,
                pending,
                settings.revision());
    }

    private void assess(PlayerSettlementSavedData.View profile, long gameTime) {
        if (profile == null || gameTime < profile.foundedTick) return;
        Village capital = village(profile.capital);
        if (capital == null) return;
        int buildings = capital.getBuildings().size();
        long population = population(profile.capital, capital);
        PlayerSettlementPolicy.Assessment assessment = PlayerSettlementPolicy.assess(
                profile.baseRadius,
                buildings,
                population,
                gameTime - profile.foundedTick,
                realms.capturedSettlementCount(profile.realmId));
        settlements.updateAssessment(
                profile.owner,
                assessment.tier(),
                assessment.territoryRadius(),
                assessment.development(),
                gameTime);
    }

    private long population(UUID settlementId, Village village) {
        if (simulation != null) {
            long key = simulation.keys().findSettlement(settlementId);
            int row = key == 0L ? -1 : simulation.state().find(key);
            if (row >= 0) return Math.max(simulation.state().populationAt(row), simulation.state().observedPopulationAt(row));
        }
        return village == null ? 0L : village.getVillagerRecords().size();
    }

    private void assignSimulationRealm(UUID settlementId, long realmId) {
        if (simulation == null) return;
        long key = simulation.keys().findSettlement(settlementId);
        if (key != 0L && simulation.state().assignRealm(key, realmId)) simulation.markChanged();
    }

    private void dissolveNpcRealm(long realmId) {
        realms.diplomacy().removeRealm(realmId);
        realms.dependencies().removeRealm(realmId);
        realms.institutions().removeRealm(realmId);
        realms.history().removeRealm(realmId);
        realms.removeMetadata(realmId);
        if (!realms.registry().dissolveRealm(realmId)) {
            throw new IllegalStateException("Failed to dissolve single-settlement NPC Realm " + realmId);
        }
    }

    private static VillageType.LayoutSlot territorySlot(
            VillageType.LayoutSlot offered,
            ResourceLocation planSetId,
            BuildingPlanSet set,
            int territoryRadius) {
        if (planSetId == null || set == null) return null;
        int radius = Math.max(
                PlayerSettlementPolicy.MINIMUM_RADIUS,
                Math.min(PlayerSettlementPolicy.MAXIMUM_RADIUS, territoryRadius));
        double minimum = offered != null && offered.hasMinDistanceOverride()
                ? offered.minDistance()
                : set.minDistance();
        double configuredMaximum = offered != null && offered.hasMaxDistanceOverride()
                ? offered.maxDistance()
                : set.maxDistance();
        int priority = offered != null && offered.hasPriorityOverride() ? offered.priority() : 0;
        return new VillageType.LayoutSlot(
                planSetId,
                offered == null ? BlockPos.ZERO : offered.offset(),
                offered == null ? Rotation.NONE : offered.rotation(),
                offered == null ? "extra" : offered.role(),
                minimum,
                Math.max(configuredMaximum, radius),
                priority,
                offered == null ? set.farFromTags() : offered.farFromTags(),
                offered == null ? set.closeToTags() : offered.closeToTags(),
                Math.max(
                        set.clearMargins().maxMargin(),
                        offered == null ? 0 : offered.clearMargin()),
                offered != null && offered.fixedOrientation() != null
                        ? offered.fixedOrientation()
                        : set.fixedOrientation());
    }

    private void automaticDevelopment(
            UUID owner,
            UUID settlementId,
            PlayerSettlementProfile developmentProfile,
            int queueLimit) {
        Village village = village(settlementId);
        ServerLevel level = level(settlementId);
        if (village == null || level == null || !village.isActive()
                || !village.isControlledBy(owner)
                || village.getPendingProject() != null
                || village.getControlledQueue().size() >= queueLimit
                || !level.isLoaded(village.getCenter())) {
            return;
        }
        PlayerSettlementSavedData.View progression = profile(owner);
        if (progression == null || !settlementId.equals(progression.capital)) return;
        AutomaticCandidate candidate = bestAutomaticCandidate(
                level,
                village,
                developmentProfile,
                progression.tier,
                progression.territoryRadius);
        if (candidate == null) return;
        ControlledQueuedProject project = new ControlledQueuedProject(
                candidate.planSet().id(), candidate.variant(), 0, candidate.location());
        if (!village.enqueueControlledProject(project)) return;
        village.setNoProjectsLeftUntil(0L);
        VillageGrowthManager.evaluateGrowth(level, village);
        village.markDirty();
        VillageSavedData.get(level).setDirty();
        automaticProjectCount++;
        LOGGER.info(
                "[BANNEROK_PLAYER_SETTLEMENT_AUTO_PROJECT] owner={} capital={} profile={} plan={} variant={} queue={}/{}",
                owner,
                settlementId,
                developmentProfile,
                candidate.planSet().id(),
                candidate.variant(),
                village.getControlledQueue().size(),
                queueLimit);
    }

    private AutomaticCandidate bestAutomaticCandidate(
            ServerLevel level,
            Village village,
            PlayerSettlementProfile developmentProfile,
            PlayerSettlementTier tier,
            int territoryRadius) {
        VillageType baseType = ModCultures.getVillageType(village.getVillageTypeId());
        if (baseType == null) return null;
        ArrayList<BuildingPlanSet> culturePlans = new ArrayList<>();
        for (BuildingPlanSet set : ModCultures.getAllBuildingPlanSets().values()) {
            if (set != null && set.culture().equals(baseType.culture())) culturePlans.add(set);
        }
        culturePlans.sort(Comparator.comparing(set -> set.id().toString()));
        ArrayList<ScoredAutomaticPlan> scored = new ArrayList<>(16);
        int inspected = 0;
        for (BuildingPlanSet set : culturePlans) {
            if (inspected++ >= MAX_AUTOMATIC_CANDIDATES) break;
            ResourceLocation planId = set.id();
            if (set.isTownHall() || reachedMaximum(village, set)) {
                continue;
            }
            VillageType.LayoutSlot slot =
                    ControlledProjectsService.findOfferedSlot(baseType, planId, set);
            boolean extended = slot == null;
            if (extended && (forbiddenByVillageType(baseType, set)
                    || !PlayerSettlementPolicy.allowsExtendedPlan(
                            tier,
                            set.category(),
                            set.isTownHall(),
                            set.isSubBuilding(),
                            set.isWallSegment(),
                            set.isGift(),
                            set.price()))) {
                continue;
            }
            slot = territorySlot(slot, planId, set, territoryRadius);
            if (slot == null) continue;
            String variant = set.variants().keySet().stream().sorted().findFirst().orElse(null);
            BuildingPlanSet.LevelDef levelZero = variant == null ? null : set.getLevel(variant, 0);
            BuildingPlan plan = levelZero == null ? null : ModCultures.getBuildingPlan(levelZero.planId());
            if (plan == null) continue;
            int primary = buildingPolicy.score(developmentProfile.primaryPriority(), set, slot);
            int secondary = buildingPolicy.score(developmentProfile.secondaryPriority(), set, slot);
            int score = developmentProfile == PlayerSettlementProfile.BALANCED
                    ? Math.max(primary, secondary)
                    : primary + Math.max(0, secondary / 4);
            if (extended) score -= 20;
            if (score > 0) scored.add(new ScoredAutomaticPlan(set, variant, plan, slot, score));
        }
        scored.sort(Comparator
                .comparingInt(ScoredAutomaticPlan::score)
                .reversed()
                .thenComparing(candidate -> candidate.planSet().id().toString()));
        int placementAttempts = Math.min(12, scored.size());
        if (placementAttempts == 0) return null;
        UniversalisGrowthBridge.PlacementSession placement =
                UniversalisGrowthBridge.begin(level, village, territoryRadius);
        if (placement == null) return null;
        for (int index = 0; index < placementAttempts; index++) {
            ScoredAutomaticPlan candidate = scored.get(index);
            var location = placement.findLocation(
                    candidate.planSet(),
                    candidate.plan(),
                    candidate.slot());
            if (location != null) {
                return new AutomaticCandidate(
                        candidate.planSet(),
                        candidate.variant(),
                        location,
                        candidate.score());
            }
        }
        return null;
    }

    private static boolean forbiddenByVillageType(VillageType type, BuildingPlanSet set) {
        if (type == null || set == null || type.neverBuildings().isEmpty()) return false;
        String fullId = set.id().toString().toLowerCase(Locale.ROOT);
        String path = set.id().getPath().toLowerCase(Locale.ROOT);
        String buildingId = set.buildingId() == null
                ? ""
                : set.buildingId().toLowerCase(Locale.ROOT);
        for (String raw : type.neverBuildings()) {
            if (raw == null) continue;
            String token = raw.strip().toLowerCase(Locale.ROOT);
            if (token.isEmpty()) continue;
            if (token.equals(fullId)
                    || token.equals(path)
                    || token.equals(buildingId)
                    || set.hasTag(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean reachedMaximum(Village village, BuildingPlanSet planSet) {
        int maximum = planSet.maxCount();
        if (maximum <= 0) return false;
        int count = 0;
        for (BuildingInstance building : village.getBuildings()) {
            if (building != null && planSet.id().equals(building.getPlanSetId())
                    && ++count >= maximum) {
                return true;
            }
        }
        for (ControlledQueuedProject project : village.getControlledQueue()) {
            if (project != null && planSet.id().equals(project.planSetId())
                    && ++count >= maximum) {
                return true;
            }
        }
        Village.PendingProject pending = village.getPendingProject();
        return pending != null
                && planSet.id().equals(pending.planSetId())
                && ++count >= maximum;
    }

    private List<Village> controlledFamily(
            ServerLevel level,
            Village capital,
            UUID owner) {
        VillageManager manager = VillageSavedData.get(level).getVillageManager();
        ArrayList<Village> candidates = new ArrayList<>();
        int inspected = 0;
        for (Village village : manager.getAllVillages()) {
            if (inspected++ >= MAX_FOUNDATION_VILLAGE_SCAN) return null;
            if (village != null && village.getId() != null && village.isControlledBy(owner)) {
                candidates.add(village);
            }
        }
        ArrayList<Village> family = new ArrayList<>();
        HashSet<UUID> familyIds = new HashSet<>();
        family.add(capital);
        familyIds.add(capital.getId().uuid());
        boolean changed;
        do {
            changed = false;
            for (Village candidate : candidates) {
                UUID candidateId = candidate.getId().uuid();
                VillageId parent = candidate.getParentVillageId();
                if (!familyIds.contains(candidateId) && parent != null
                        && familyIds.contains(parent.uuid())) {
                    family.add(candidate);
                    familyIds.add(candidateId);
                    changed = true;
                }
            }
        } while (changed);
        return List.copyOf(family);
    }

    private void rollbackSpawnRegistrations(
            VillageManager manager,
            VillageSavedData physicalData,
            List<Village> createdVillages) {
        for (Village village : createdVillages) {
            if (village != null && village.getId() != null) manager.removeVillage(village.getId());
        }
        physicalData.setDirty();
        villageIndex.reconcile(server);
    }

    private static long horizontalDistanceSquared(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private static String migrationName(String canonicalName, String physicalName, long realmId) {
        String source = canonicalName == null || canonicalName.isBlank() ? physicalName : canonicalName;
        if (source == null) source = "Realm " + realmId;
        StringBuilder cleaned = new StringBuilder(source.length());
        for (int index = 0; index < source.length(); index++) {
            char value = source.charAt(index);
            if (!Character.isISOControl(value)) cleaned.append(value);
        }
        String result = cleaned.toString().strip();
        if (result.isEmpty()) result = "Realm " + realmId;
        int maximum = PlayerSettlementCustomizationSavedData.MAX_NAME_LENGTH;
        return result.length() <= maximum ? result : result.substring(0, maximum).stripTrailing();
    }

    private static long saturatedMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) return 0L;
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    private static boolean areaLoaded(ServerLevel level, BlockPos center, int radius) {
        int minChunkX = (center.getX() - radius) >> 4;
        int maxChunkX = (center.getX() + radius) >> 4;
        int minChunkZ = (center.getZ() - radius) >> 4;
        int maxChunkZ = (center.getZ() + radius) >> 4;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) return false;
            }
        }
        return true;
    }

    private PlayerSettlementCustomizationSavedData.View customization(UUID owner) {
        if (customization == null || owner == null) return null;
        PlayerSettlementCustomizationSavedData.View view =
                new PlayerSettlementCustomizationSavedData.View();
        return customization.read(owner, view) ? view : null;
    }

    private OperationResult reject(String code, String message) {
        rejectedCount++;
        return OperationResult.fail(code, message == null ? code : message);
    }

    public long migratedProfileCount() { return migratedProfileCount; }
    public long foundedCount() { return foundedCount; }
    public long manualProjectCount() { return manualProjectCount; }
    public long automaticProjectCount() { return automaticProjectCount; }
    public long rejectedCount() { return rejectedCount; }

    private void publishTerritories() {
        if (!active()) return;
        long territoryRevision = settlements.territoryRevision();
        long realmRevision = realms.registry().revision();
        long villageReconciliation = villageIndex.reconciliationCount();
        if (publishedTerritoryRevision == territoryRevision
                && publishedRealmRevision == realmRevision
                && publishedVillageReconciliation == villageReconciliation) {
            return;
        }
        PlayerSettlementTerritoryRegistry.clear();
        settlements.visit(profile -> realms.registry().visitMembers(
                profile.realmId,
                (memberId, kind, controllerId, influence) -> {
                    if (kind == RealmMemberKind.PLAYER
                            || !realms.keys().valid(memberId)
                            || realms.keys().kind(memberId) != RealmKeyTable.SETTLEMENT) {
                        return;
                    }
                    UUID settlementId = realms.keys().uuid(memberId);
                    Village physical = village(settlementId);
                    if (physical != null && physical.isControlledBy(profile.owner)
                            && !PlayerSettlementTerritoryRegistry.put(
                                    settlementId,
                                    profile.territoryRadius)) {
                        throw new IllegalStateException("Player settlement territory registry is full");
                    }
                }));
        publishedTerritoryRevision = territoryRevision;
        publishedRealmRevision = realmRevision;
        publishedVillageReconciliation = villageReconciliation;
    }

    private boolean mayManageSettlement(
            UUID owner,
            PlayerSettlementSavedData.View profile,
            UUID settlementId,
            Village village) {
        return owner != null && profile != null && settlementId != null && village != null
                && village.isControlledBy(owner)
                && realms.realmForPlayer(owner) == profile.realmId
                && realms.realmForSettlement(settlementId) == profile.realmId;
    }

    private PlayerSettlementSavedData.View profile(UUID owner) {
        if (settlements == null || owner == null) return null;
        PlayerSettlementSavedData.View view = new PlayerSettlementSavedData.View();
        return settlements.view(owner, view) ? view : null;
    }

    private Village village(UUID id) {
        return id == null ? null : villageIndex.find(id.getMostSignificantBits(), id.getLeastSignificantBits());
    }

    private ServerLevel level(UUID id) {
        return id == null ? null : villageIndex.level(new VillageId(id));
    }

    private boolean active() {
        return server != null && settlements != null && customization != null;
    }

    private record ScoredAutomaticPlan(
            BuildingPlanSet planSet,
            String variant,
            BuildingPlan plan,
            VillageType.LayoutSlot slot,
            int score) {}

    private record AutomaticCandidate(
            BuildingPlanSet planSet,
            String variant,
            org.millenaire.world.PlacedLocation location,
            int score) {}

    public record OperationResult(boolean success, String code, String message) {
        public static OperationResult success(String code, String message) {
            return new OperationResult(true, code, message);
        }
        public static OperationResult fail(String code, String message) {
            return new OperationResult(false, code, message);
        }
    }

    public record Status(
            String name,
            ResourceLocation villageType,
            UUID capital,
            long realmId,
            PlayerSettlementTier tier,
            int territoryRadius,
            int development,
            int buildingCount,
            long population,
            int capturedSettlements,
            PlayerSettlementProfile profile,
            boolean automatic,
            int queueLimit,
            int queuedProjects,
            boolean pendingProject,
            long customizationRevision) {}
}
