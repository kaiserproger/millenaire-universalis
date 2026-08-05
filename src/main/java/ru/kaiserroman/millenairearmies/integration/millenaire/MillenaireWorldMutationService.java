package ru.kaiserroman.millenairearmies.integration.millenaire;

import com.mojang.logging.LogUtils;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import org.millenaire.block.LockedChestBlockEntity;
import org.millenaire.building.BuildingInstance;
import org.millenaire.combat.raid.RaidManager;
import org.millenaire.culture.ModCultures;
import org.millenaire.culture.VillageType;
import org.millenaire.entity.MillVillager;
import org.millenaire.village.Village;
import org.millenaire.village.VillageChunkLoader;
import org.millenaire.village.VillageEventType;
import org.millenaire.village.VillageManager;
import org.millenaire.village.VillageSavedData;
import org.millenaire.village.VillagerRecord;
import org.millenaire.world.SiteValidator;
import org.millenaire.world.VillageSpawner;
import org.slf4j.Logger;
import ru.kaiserroman.millenaire.realm.RealmMemberKind;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenaire.simulation.PackedSettlementSimulationState;
import ru.kaiserroman.millenaire.simulation.SettlementStatus;
import ru.kaiserroman.millenaire.simulation.SimulationEvent;
import ru.kaiserroman.millenaire.simulation.SimulationEventType;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;

/**
 * Opt-in two-phase physical world mutation. It consumes only the persisted FIFO head, retries
 * transient failures with persisted backoff and uses Millenaire's own public spawner/cleanup APIs.
 */
public final class MillenaireWorldMutationService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int[] DIR_X = {
        1024, 946, 724, 392, 0, -392, -724, -946,
        -1024, -946, -724, -392, 0, 392, 724, 946
    };
    private static final int[] DIR_Z = {
        0, 392, 724, 946, 1024, 946, 724, 392,
        0, -392, -724, -946, -1024, -946, -724, -392
    };

    private final SimulationSavedData data;
    private final MutationExecutor executor;
    private final int eventsPerTick;
    private final int maximumAttempts;
    private final int retryTicks;
    private final int deferTicks;
    private final int maximumDeferRuns;

    private final long[] headSequence = new long[1];
    private final SimulationEvent[] headEvent = new SimulationEvent[1];

    private long committedCount;
    private long finalRejectedCount;
    private long retryCount;
    private long exhaustedRetryCount;
    private long executorFailureCount;
    private long deferCount;
    private int lastTickWorkUnits;

    /** Consecutive deferrals of the current head. Session-scoped; never persisted. */
    private long deferHeadSequence;
    private int deferRuns;

    public MillenaireWorldMutationService(
            MillenaireVillageIndex villages,
            SimulationSavedData data,
            RealmSavedData realms,
            Runnable reconcileRequester,
            boolean foundingEnabled,
            boolean abandonmentEnabled,
            int refugeesPerEvent,
            int eventsPerTick,
            int siteAttempts,
            int maximumAttempts,
            int retryTicks,
            int deferTicks,
            int maximumDeferRuns,
            int minimumFoundingDistance,
            int maximumFoundingDistance,
            int minimumVillageDistance,
            int playerSafetyRadius,
            int foundingCompletion,
            int regionSizeBlocks) {
        this(
                data,
                new RealMutationExecutor(
                        villages,
                        data,
                        realms,
                        reconcileRequester,
                        foundingEnabled,
                        abandonmentEnabled,
                        refugeesPerEvent,
                        siteAttempts,
                        minimumFoundingDistance,
                        maximumFoundingDistance,
                        minimumVillageDistance,
                        playerSafetyRadius,
                        foundingCompletion,
                        regionSizeBlocks),
                eventsPerTick,
                maximumAttempts,
                retryTicks,
                deferTicks,
                maximumDeferRuns);
    }

    MillenaireWorldMutationService(
            SimulationSavedData data,
            MutationExecutor executor,
            int eventsPerTick,
            int maximumAttempts,
            int retryTicks,
            int deferTicks,
            int maximumDeferRuns) {
        if (data == null || executor == null) {
            throw new NullPointerException("world mutation dependency");
        }
        if (eventsPerTick <= 0 || maximumAttempts <= 0 || retryTicks <= 0
                || deferTicks <= 0 || maximumDeferRuns <= 0) {
            throw new IllegalArgumentException("Invalid world mutation bounds");
        }
        this.data = data;
        this.executor = executor;
        this.eventsPerTick = eventsPerTick;
        this.maximumAttempts = maximumAttempts;
        this.retryTicks = retryTicks;
        this.deferTicks = deferTicks;
        this.maximumDeferRuns = maximumDeferRuns;
    }

    public void tick(long gameTime) {
        if (gameTime < 0L) throw new IllegalArgumentException("Negative gameTime");
        lastTickWorkUnits = 0;
        for (int budget = eventsPerTick; budget > 0; budget--) {
            headSequence[0] = 0L;
            headEvent[0] = null;
            if (!data.events().visitHead((sequence, event) -> {
                headSequence[0] = sequence;
                headEvent[0] = event;
            })) {
                return;
            }
            long sequence = headSequence[0];
            SimulationEvent event = headEvent[0];
            if (!data.prepareMutationAttempt(sequence, gameTime)) return;

            MutationResult result;
            try {
                result = executor.apply(sequence, event, data.mutationAttempts(), gameTime);
            } catch (RuntimeException failure) {
                executorFailureCount++;
                LOGGER.warn(
                        "World mutation sequence {} type {} failed closed",
                        sequence,
                        event.type(),
                        failure);
                result = MutationResult.RETRY;
            }
            lastTickWorkUnits++;
            if (result == MutationResult.DEFER) {
                if (deferHeadSequence != sequence) {
                    deferHeadSequence = sequence;
                    deferRuns = 0;
                }
                if (deferRuns < maximumDeferRuns) {
                    deferRuns++;
                    deferCount++;
                    data.scheduleMutationDefer(sequence, saturatedAdd(gameTime, deferTicks));
                    return;
                }
                if (deferRuns == maximumDeferRuns) {
                    // Latch the demotion for this head: once the budget is spent the ordinary
                    // retry ladder owns it, so the queue cannot stall forever.
                    deferRuns++;
                    LOGGER.warn(
                            "World mutation sequence {} type {} deferred {} times; demoting to retry",
                            sequence,
                            event.type(),
                            maximumDeferRuns);
                }
                result = MutationResult.RETRY;
            }
            if (result == MutationResult.RETRY) {
                int nextAttempt = data.mutationAttempts() == Integer.MAX_VALUE
                        ? Integer.MAX_VALUE
                        : data.mutationAttempts() + 1;
                if (nextAttempt >= maximumAttempts) {
                    exhaustedRetryCount++;
                    acknowledge(sequence);
                    LOGGER.warn(
                            "World mutation sequence {} type {} exhausted {} attempts and was rejected",
                            sequence,
                            event.type(),
                            nextAttempt);
                    continue;
                }
                retryCount++;
                long multiplier = Math.min(16L, Math.max(1L, nextAttempt));
                data.scheduleMutationRetry(
                        sequence,
                        saturatedAdd(gameTime, saturatedMultiply(retryTicks, multiplier)));
                return;
            }
            if (result == MutationResult.COMMITTED) committedCount++;
            else finalRejectedCount++;
            acknowledge(sequence);
        }
    }

    private void acknowledge(long sequence) {
        data.events().acknowledgeThrough(sequence);
        data.completeMutationAttempt(sequence);
        data.markChanged();
        if (deferHeadSequence == sequence) {
            deferHeadSequence = 0L;
            deferRuns = 0;
        }
    }

    public long committedCount() { return committedCount; }
    public long finalRejectedCount() { return finalRejectedCount; }
    public long retryCount() { return retryCount; }
    public long exhaustedRetryCount() { return exhaustedRetryCount; }
    public long executorFailureCount() { return executorFailureCount; }
    public long deferCount() { return deferCount; }
    public int lastTickWorkUnits() { return lastTickWorkUnits; }

    public void logShutdownMetrics() {
        LOGGER.info(
                "[BANNEROK_WORLD_MUTATION_METRICS] committed={} final_rejected={} retries={} exhausted={} deferred={} executor_failures={} pending={} current_sequence={} attempts={} next_attempt_tick={} last_work={}",
                committedCount,
                finalRejectedCount,
                retryCount,
                exhaustedRetryCount,
                deferCount,
                executorFailureCount,
                data.events().size(),
                data.mutationSequence(),
                data.mutationAttempts(),
                data.nextMutationAttemptTick(),
                lastTickWorkUnits);
    }

    enum MutationResult {
        COMMITTED,
        FINAL_REJECT,
        /** Transient failure. Consumes an attempt and escalates the backoff. */
        RETRY,
        /**
         * Not applicable yet: the affected villages exist but are not loaded. Rescheduled on a
         * short fixed interval without consuming a retry attempt.
         */
        DEFER
    }

    @FunctionalInterface
    interface MutationExecutor {
        MutationResult apply(long sequence, SimulationEvent event, int attempt, long gameTime);
    }

    private static final class RealMutationExecutor implements MutationExecutor {
        private final MillenaireVillageIndex villages;
        private final MillenaireVillageIndex.Cursor cursor;
        private final SimulationSavedData data;
        private final RealmSavedData realms;
        private final Runnable reconcileRequester;
        private final boolean foundingEnabled;
        private final boolean abandonmentEnabled;
        private final int refugeesPerEvent;
        private final int siteAttempts;
        private final int minimumFoundingDistance;
        private final int maximumFoundingDistance;
        private final int minimumVillageDistance;
        private final long playerSafetyRadiusSquared;
        private final int foundingCompletion;
        private final int regionSizeBlocks;

        RealMutationExecutor(
                MillenaireVillageIndex villages,
                SimulationSavedData data,
                RealmSavedData realms,
                Runnable reconcileRequester,
                boolean foundingEnabled,
                boolean abandonmentEnabled,
                int refugeesPerEvent,
                int siteAttempts,
                int minimumFoundingDistance,
                int maximumFoundingDistance,
                int minimumVillageDistance,
                int playerSafetyRadius,
                int foundingCompletion,
                int regionSizeBlocks) {
            if (villages == null || data == null || realms == null || reconcileRequester == null) {
                throw new NullPointerException("real world mutation dependency");
            }
            if (refugeesPerEvent <= 0 || siteAttempts <= 0 || minimumFoundingDistance <= 0
                    || maximumFoundingDistance < minimumFoundingDistance
                    || minimumVillageDistance <= 0 || playerSafetyRadius <= 0
                    || foundingCompletion < 0 || foundingCompletion > 100
                    || regionSizeBlocks <= 0) {
                throw new IllegalArgumentException("Invalid real world mutation bounds");
            }
            this.villages = villages;
            cursor = villages.newCursor();
            this.data = data;
            this.realms = realms;
            this.reconcileRequester = reconcileRequester;
            this.foundingEnabled = foundingEnabled;
            this.abandonmentEnabled = abandonmentEnabled;
            this.refugeesPerEvent = refugeesPerEvent;
            this.siteAttempts = siteAttempts;
            this.minimumFoundingDistance = minimumFoundingDistance;
            this.maximumFoundingDistance = maximumFoundingDistance;
            this.minimumVillageDistance = minimumVillageDistance;
            playerSafetyRadiusSquared = (long) playerSafetyRadius * playerSafetyRadius;
            this.foundingCompletion = foundingCompletion;
            this.regionSizeBlocks = regionSizeBlocks;
        }

        @Override
        public MutationResult apply(
                long sequence,
                SimulationEvent event,
                int attempt,
                long gameTime) {
            return switch (event.type()) {
                case FOUNDING_CANDIDATE -> foundingEnabled
                        ? foundVillage(sequence, event, attempt)
                        : MutationResult.FINAL_REJECT;
                case ABANDONMENT_CANDIDATE -> abandonmentEnabled
                        ? abandonVillage(event)
                        : MutationResult.FINAL_REJECT;
                case REFUGEE_FLOW -> migrateRefugees(event);
                case DECLINE_STARTED, RUINED, RECOVERED, TIER_CHANGED, PRICE_SHOCK ->
                        MutationResult.COMMITTED;
            };
        }

        private MutationResult foundVillage(long sequence, SimulationEvent event, int attempt) {
            long sourceSettlement = event.sourceSettlementId() != 0L
                    ? event.sourceSettlementId()
                    : event.settlementId();
            PackedSettlementSimulationState state = data.state();
            int sourceRow = state.find(sourceSettlement);
            if (sourceRow < 0
                    || !state.physicallyPresentAt(sourceRow)
                    || state.statusAt(sourceRow) != SettlementStatus.ACTIVE
                    || state.cultureKeyAt(sourceRow) != event.cultureKey()) {
                return MutationResult.FINAL_REJECT;
            }
            long currentRealm = state.realmIdAt(sourceRow);
            if (event.realmId() != 0L && event.realmId() != currentRealm) {
                return MutationResult.FINAL_REJECT;
            }
            if (currentRealm != 0L
                    && (!realms.registry().exists(currentRealm)
                            || realms.isLegacy(currentRealm)
                            || realms.registry().hasPlayerMembers(currentRealm))) {
                return MutationResult.FINAL_REJECT;
            }
            UUID sourceUuid = data.keys().settlement(sourceSettlement);
            VillageLocation source = findVillage(sourceUuid);
            if (source == null) return MutationResult.DEFER;
            ResourceLocation culture = data.keys().culture(event.cultureKey());
            if (!culture.equals(source.village().getCultureId())
                    || source.village().isPlayerControlled()
                    || source.village().getOwnerUUID() != null) {
                return MutationResult.FINAL_REJECT;
            }

            VillageType sourceType = ModCultures.getVillageType(source.village().getVillageTypeId());
            for (int site = 0; site < siteAttempts; site++) {
                long seed = mix(sequence
                        ^ Long.rotateLeft(Integer.toUnsignedLong(attempt), 17)
                        ^ Long.rotateLeft(Integer.toUnsignedLong(site), 37));
                int direction = (int) seed & 15;
                int span = maximumFoundingDistance - minimumFoundingDistance + 1;
                int distance = minimumFoundingDistance
                        + Math.floorMod((int) (seed >>> 16), span);
                int x = source.village().getCenter().getX() + DIR_X[direction] * distance / 1024;
                int z = source.village().getCenter().getZ() + DIR_Z[direction] * distance / 1024;
                if (!source.level().hasChunk(x >> 4, z >> 4)) continue;
                int y = source.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos candidate = new BlockPos(x, y, z);
                int dimensionKey = (int) (event.regionKey() >>> 40);
                if (MillenaireWorldSimulationBridge.packRegion(
                                dimensionKey, candidate, regionSizeBlocks)
                        != event.regionKey()) {
                    continue;
                }
                if (!areaLoaded(source.level(), candidate, 96)
                        || playersNear(source.level(), candidate)
                        || VillageSavedData.get(source.level())
                                .getVillageManager()
                                .isWithinMinDistance(candidate, minimumVillageDistance)) {
                    continue;
                }
                VillageType villageType = selectVillageType(
                        source.level(), candidate, sourceType, culture, seed);
                if (villageType == null
                        || !areaLoaded(source.level(), candidate, villageType.radius() + 32)
                        || VillageSavedData.get(source.level())
                                .getVillageManager()
                                .overlapsExistingVillage(candidate, villageType.radius())
                        || !SiteValidator.validate(source.level(), candidate, villageType.radius())) {
                    continue;
                }
                Component validation = VillageSpawner.validateSite(
                        source.level(), candidate, villageType);
                if (validation != null) continue;

                Component spawnError = VillageSpawner.spawnVillage(
                        source.level(),
                        candidate,
                        villageType,
                        foundingCompletion,
                        null,
                        source.village().getId());
                if (spawnError != null) continue;
                VillageManager manager = VillageSavedData.get(source.level()).getVillageManager();
                Village founded = manager.findNearestVillage(
                        candidate, villageType.radius() + 64.0);
                if (founded != null
                        && founded.getId() != null
                        && founded.getId().uuid() != null
                        && currentRealm != 0L) {
                    long subject = realms.keys().internSettlement(founded.getId().uuid());
                    if (!realms.registry().addMember(
                            currentRealm,
                            subject,
                            RealmMemberKind.NPC_SETTLEMENT,
                            0L,
                            Math.max(250, event.score()))) {
                        LOGGER.warn(
                                "Founded village {} could not attach to Realm {}; it remains independent",
                                founded.getId().uuid(),
                                currentRealm);
                    } else {
                        realms.markChanged();
                    }
                }
                reconcileRequester.run();
                LOGGER.info(
                        "[BANNEROK_WORLD_MUTATION_FOUNDED] sequence={} source={} realm={} culture={} type={} pos={} attempt={}",
                        sequence,
                        sourceSettlement,
                        currentRealm,
                        culture,
                        villageType.id(),
                        candidate.toShortString(),
                        attempt);
                return MutationResult.COMMITTED;
            }
            return MutationResult.RETRY;
        }

        private MutationResult migrateRefugees(SimulationEvent event) {
            long sourceSettlement = event.sourceSettlementId();
            long destinationSettlement = event.settlementId();
            if (sourceSettlement <= 0L || sourceSettlement == destinationSettlement) {
                return MutationResult.FINAL_REJECT;
            }
            PackedSettlementSimulationState state = data.state();
            int sourceRow = state.find(sourceSettlement);
            int destinationRow = state.find(destinationSettlement);
            if (sourceRow < 0 || destinationRow < 0) return MutationResult.FINAL_REJECT;

            UUID sourceUuid = data.keys().settlement(sourceSettlement);
            UUID destinationUuid = data.keys().settlement(destinationSettlement);
            if (sourceUuid == null || destinationUuid == null) return MutationResult.FINAL_REJECT;
            VillageLocation source = findVillage(sourceUuid);
            VillageLocation destination = findVillage(destinationUuid);
            if (source == null || destination == null) {
                return state.physicallyPresentAt(sourceRow) && state.physicallyPresentAt(destinationRow)
                        ? MutationResult.DEFER
                        : MutationResult.COMMITTED;
            }
            if (source.level() != destination.level()) return MutationResult.COMMITTED;
            if (source.village().isPlayerControlled()
                    || destination.village().isPlayerControlled()
                    || source.village().getOwnerUUID() != null
                    || destination.village().getOwnerUUID() != null) {
                return MutationResult.FINAL_REJECT;
            }
            if (!source.village().isActive() || !destination.village().isActive()) {
                return MutationResult.DEFER;
            }
            BuildingInstance destinationHome = destination.village().getTownhall();
            if (destinationHome == null || !destinationHome.isOperational()) {
                return MutationResult.DEFER;
            }

            long room = Math.max(0L,
                    state.housingCapacityAt(destinationRow)
                            - state.observedPopulationAt(destinationRow));
            long proportional = Math.max(1L,
                    state.populationAt(sourceRow) * Math.max(1, event.score()) / 1000L);
            int requested = (int) Math.min(
                    Math.min(room, proportional),
                    refugeesPerEvent);
            if (requested <= 0) return MutationResult.FINAL_REJECT;

            int moved = 0;
            UUID[] candidates = source.village().getVillagerUuids().toArray(UUID[]::new);
            for (UUID villagerUuid : candidates) {
                if (moved >= requested) break;
                VillagerRecord record = source.village().getVillagerRecord(villagerUuid);
                if (record == null
                        || record.isKilled()
                        || record.isAwayRaiding()
                        || record.isRaidingVillage()
                        || record.isAwayHired()) {
                    continue;
                }
                source.village().transferVillagerPermanently(
                        source.level(),
                        villagerUuid,
                        destination.village(),
                        destinationHome.getId());
                moved++;
            }
            if (moved == 0) return MutationResult.FINAL_REJECT;

            source.village().recordChronicleEvent(
                    source.level(),
                    VillageEventType.MIGRATION,
                    "millenaire_armies.refugees.departed",
                    displayName(destination.village()));
            destination.village().recordChronicleEvent(
                    destination.level(),
                    VillageEventType.MIGRATION,
                    "millenaire_armies.refugees.arrived",
                    displayName(source.village()));
            VillageSavedData.get(source.level()).setDirty();
            reconcileRequester.run();
            LOGGER.info(
                    "[BANNEROK_WORLD_MUTATION_REFUGEES] source={} destination={} moved={} requested={} culture={} realm={} score={}",
                    sourceSettlement,
                    destinationSettlement,
                    moved,
                    requested,
                    event.cultureKey(),
                    event.realmId(),
                    event.score());
            return MutationResult.COMMITTED;
        }

        private MutationResult abandonVillage(SimulationEvent event) {
            UUID uuid = data.keys().settlement(event.settlementId());
            VillageLocation target = findVillage(uuid);
            if (target == null) return MutationResult.COMMITTED;
            Village village = target.village();
            long realmId = realms.realmForSettlement(uuid);
            if (village.isPlayerControlled()
                    || village.getOwnerUUID() != null
                    || village.areChestsLocked()
                    || realmId != 0L
                            && (realms.isLegacy(realmId)
                                    || realms.registry().hasPlayerMembers(realmId))) {
                return MutationResult.FINAL_REJECT;
            }
            if (!village.isActive() || playersNear(target.level(), village.getCenter())) {
                return MutationResult.DEFER;
            }

            VillageSavedData savedData = VillageSavedData.get(target.level());
            for (BuildingInstance building : village.getBuildings()) {
                for (BlockPos chestPos : building.getChestPositions()) {
                    if (target.level().getBlockEntity(chestPos)
                            instanceof LockedChestBlockEntity chest) {
                        chest.setBuildingId(null);
                    }
                }
            }
            int removedVillagers = 0;
            for (UUID villagerUuid : village.getVillagerUuids()) {
                if (target.level().getEntity(villagerUuid) instanceof MillVillager villager) {
                    villager.discard();
                    removedVillagers++;
                }
            }
            if (!village.getLoadedChunks().isEmpty()) {
                VillageChunkLoader.releaseVillageChunks(
                        target.level(), village.getCenter(), village.getLoadedChunks());
                village.setLoadedChunks(Set.of());
                village.setChunksForceLoaded(false);
            }
            savedData.getVillageManager().removeVillage(village.getId());
            for (Village other : savedData.getVillageManager().getAllVillages()) {
                other.removeRelation(village.getId());
                if (village.getId().equals(other.getParentVillageId())) {
                    other.setParentVillageId(null);
                }
                if (village.getId().equals(other.getRaidTarget())) {
                    RaidManager.abortRaidForAttacker(other, target.level());
                }
            }
            savedData.removeLoneBuilding(village.getCenter());
            savedData.setDirty();
            detachCanonicalSettlement(uuid, realmId);
            data.state().assignRealm(event.settlementId(), RealmRegistry.NO_REALM);
            data.markChanged();
            reconcileRequester.run();
            LOGGER.info(
                    "[BANNEROK_WORLD_MUTATION_ABANDONED] settlement={} realm={} village={} villagers_removed={} buildings_left_as_ruins={}",
                    event.settlementId(),
                    realmId,
                    uuid,
                    removedVillagers,
                    village.getBuildings().size());
            return MutationResult.COMMITTED;
        }

        private void detachCanonicalSettlement(UUID uuid, long realmId) {
            if (realmId == 0L || !realms.registry().exists(realmId)) return;
            long subject = realms.keys().findSettlement(uuid);
            if (subject == 0L || realms.registry().realmOfMember(subject) != realmId) return;
            if (realms.registry().capitalMemberId(realmId) == subject) {
                long[] replacement = {0L};
                int[] bestInfluence = {-1};
                realms.registry().visitMembers(realmId, (memberId, kind, controllerId, influence) -> {
                    if (memberId != subject
                            && kind != RealmMemberKind.PLAYER
                            && influence > bestInfluence[0]) {
                        replacement[0] = memberId;
                        bestInfluence[0] = influence;
                    }
                });
                if (replacement[0] != 0L) {
                    realms.registry().setCapital(realmId, replacement[0]);
                } else {
                    realms.institutions().removeRealm(realmId);
                    realms.lifecycle().removeCrisis(realmId);
                    realms.diplomacy().removeRealm(realmId);
                    realms.dependencies().removeRealm(realmId);
                    realms.history().removeRealm(realmId);
                    realms.registry().dissolveRealm(realmId);
                    realms.removeMetadata(realmId);
                    realms.markChanged();
                    return;
                }
            }
            realms.registry().removeMember(subject);
            realms.markChanged();
        }

        private VillageType selectVillageType(
                ServerLevel level,
                BlockPos candidate,
                VillageType sourceType,
                ResourceLocation culture,
                long seed) {
            VillageType hamlet = selectVillageType(
                    level, candidate, sourceType, culture, seed, true);
            return hamlet != null
                    ? hamlet
                    : selectVillageType(level, candidate, sourceType, culture, seed, false);
        }

        private VillageType selectVillageType(
                ServerLevel level,
                BlockPos candidate,
                VillageType sourceType,
                ResourceLocation culture,
                long seed,
                boolean hamletOnly) {
            VillageType best = null;
            long bestScore = 0L;
            for (VillageType type : ModCultures.getAllVillageTypes().values()) {
                if (!culture.equals(type.culture())
                        || type.loneBuilding()
                        || type.playerControlled()
                        || type.isMarvel()) {
                    continue;
                }
                boolean listedHamlet = sourceType != null && sourceType.hamlets().contains(type.id());
                if (hamletOnly) {
                    if (!listedHamlet) continue;
                } else if (!type.isRegularVillage()
                        || !type.spawnable()
                        || !type.hamlets().isEmpty()) {
                    continue;
                }
                if (!biomeCompatible(level, candidate, type)) continue;
                long score = mix(seed ^ Integer.toUnsignedLong(type.id().hashCode()));
                if (best == null || Long.compareUnsigned(score, bestScore) > 0) {
                    best = type;
                    bestScore = score;
                }
            }
            return best;
        }

        private boolean biomeCompatible(
                ServerLevel level,
                BlockPos candidate,
                VillageType type) {
            if (type.biomeTags().isEmpty()) return true;
            int sampleRadius = Math.min(type.radius(), 64);
            int valid = 0;
            int total = 0;
            int y = level.getMaxBuildHeight() - 1;
            for (int x = -sampleRadius; x <= sampleRadius; x += 16) {
                for (int z = -sampleRadius; z <= sampleRadius; z += 16) {
                    total++;
                    Holder<Biome> biome = level.getBiome(candidate.offset(x, y - candidate.getY(), z));
                    if (matchesAnyTag(biome, type.biomeTags())) valid++;
                }
            }
            float required = type.minimumBiomeValidity();
            required += SiteValidator.radiusRelaxation(type.radius()) * (0.2F - required);
            return total > 0 && (float) valid / total >= required;
        }

        private boolean playersNear(ServerLevel level, BlockPos pos) {
            for (ServerPlayer player : level.players()) {
                if (player.blockPosition().distSqr(pos) < playerSafetyRadiusSquared) return true;
            }
            return false;
        }

        private static String displayName(Village village) {
            if (village.getVillageName() != null && !village.getVillageName().isBlank()) {
                return village.getVillageName();
            }
            return village.getVillageTypeId() == null
                    ? village.getId().uuid().toString()
                    : village.getVillageTypeId().toString();
        }

        private VillageLocation findVillage(UUID uuid) {
            for (cursor.reset(); cursor.advance(); ) {
                Village village = cursor.village();
                if (village != null
                        && village.getId() != null
                        && uuid.equals(village.getId().uuid())) {
                    return new VillageLocation(village, cursor.level());
                }
            }
            return null;
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

        private static boolean matchesAnyTag(
                Holder<Biome> biome,
                Iterable<TagKey<Biome>> tags) {
            for (TagKey<Biome> tag : tags) {
                if (biome.is(tag)) return true;
            }
            return false;
        }

        private record VillageLocation(Village village, ServerLevel level) {}
    }

    private static long saturatedMultiply(long left, long right) {
        if (left <= 0L || right <= 0L) return 0L;
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    private static long saturatedAdd(long left, long right) {
        if (right <= 0L) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdL;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53L;
        value ^= value >>> 33;
        return value;
    }
}
