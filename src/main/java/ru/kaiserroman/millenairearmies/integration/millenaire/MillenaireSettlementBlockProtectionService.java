package ru.kaiserroman.millenairearmies.integration.millenaire;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import org.millenaire.building.BuildingInstance;
import org.millenaire.village.Village;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.lifecycle.ArmyLifecycleService;
import ru.kaiserroman.millenairearmies.persistence.PlayerSettlementSavedData;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;
import ru.kaiserroman.millenairearmies.server.settlement.PlayerSettlementService;
import ru.kaiserroman.millenairearmies.server.execution.ArmyOrderExecutionBridge;

/**
 * Server-thread adapter that protects foreign Millenaire settlement blocks without force-loading.
 *
 * <p>Village bounds are rebuilt only after the shared village index reconciles. The hot break-speed
 * path scans reusable primitive columns and creates no per-block wrappers, collections or UUIDs.
 * Owners, operators in creative mode and members of the settlement's canonical Realm are allowed.
 * Everyone else receives the deterministic policy: critical infrastructure is denied, peacetime
 * blocks are extremely slow, and an active physical siege opens only a narrow perimeter breach.</p>
 */
public final class MillenaireSettlementBlockProtectionService {
    private static final int MIN_CAPACITY = 16;

    private final ArmyLifecycleService lifecycle;
    private final SettlementBlockProtectionPolicy policy;
    private final MillenaireVillageIndex.Cursor villageCursor;

    private ServerLevel[] levels = new ServerLevel[MIN_CAPACITY];
    private Village[] villages = new Village[MIN_CAPACITY];
    private int[] minX = new int[MIN_CAPACITY];
    private int[] minY = new int[MIN_CAPACITY];
    private int[] minZ = new int[MIN_CAPACITY];
    private int[] maxX = new int[MIN_CAPACITY];
    private int[] maxY = new int[MIN_CAPACITY];
    private int[] maxZ = new int[MIN_CAPACITY];
    private int[] breachMinX = new int[MIN_CAPACITY];
    private int[] breachMinZ = new int[MIN_CAPACITY];
    private int[] breachMaxX = new int[MIN_CAPACITY];
    private int[] breachMaxZ = new int[MIN_CAPACITY];
    private int size;
    private long indexedReconciliation = Long.MIN_VALUE;
    private long indexedPlayerSettlementRevision = Long.MIN_VALUE;
    private long indexedRealmRegistryRevision = Long.MIN_VALUE;
    private final PlayerSettlementSavedData.View playerSettlementView =
            new PlayerSettlementSavedData.View();

    public MillenaireSettlementBlockProtectionService(ArmyLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
        this.policy = new SettlementBlockProtectionPolicy(
                ArmiesConfig.SETTLEMENT_FOREIGN_BREAK_SPEED_PERMILLE,
                ArmiesConfig.SETTLEMENT_SIEGE_BREAK_SPEED_PERMILLE);
        this.villageCursor = lifecycle.villageIndex().newCursor();
    }

    public SettlementBlockProtectionPolicy.Decision decide(
            ServerPlayer player,
            ServerLevel level,
            BlockPos position) {
        if (!ArmiesConfig.SETTLEMENT_BLOCK_PROTECTION_ENABLED
                || player == null || level == null || position == null
                || lifecycle.state() != ArmyLifecycleService.State.RUNNING) {
            return SettlementBlockProtectionPolicy.Decision.OUTSIDE;
        }
        refreshBoundsIfNeeded();
        int row = containingVillage(player, level, position);
        if (row < 0) return SettlementBlockProtectionPolicy.Decision.OUTSIDE;

        Village village = villages[row];
        boolean authorized = authorized(player, village);
        boolean critical = !authorized && criticalInfrastructure(village, position);
        boolean siege = !authorized && activePhysicalSiege(level, village);
        boolean breachBand = siege && insideBreachBand(row, position);
        return policy.decide(true, authorized, critical, siege, breachBand);
    }

    public float adjustedSpeed(
            float originalSpeed,
            SettlementBlockProtectionPolicy.Decision decision) {
        return policy.adjustedSpeed(originalSpeed, decision);
    }

    public boolean cancelFinalBreak(SettlementBlockProtectionPolicy.Decision decision) {
        return policy.cancelFinalBreak(decision);
    }

    public SettlementBlockProtectionPolicy policy() {
        return policy;
    }

    private void refreshBoundsIfNeeded() {
        long reconciliation = lifecycle.villageIndex().reconciliationCount();
        PlayerSettlementService playerSettlements = lifecycle.playerSettlementService();
        PlayerSettlementSavedData profiles = playerSettlements == null ? null : playerSettlements.profiles();
        RealmSavedData realmData = lifecycle.realmData();
        long playerSettlementRevision = profiles == null
                ? Long.MIN_VALUE
                : profiles.territoryRevision();
        long realmRegistryRevision = realmData == null
                ? Long.MIN_VALUE
                : realmData.registry().revision();
        if (indexedReconciliation == reconciliation
                && indexedPlayerSettlementRevision == playerSettlementRevision
                && indexedRealmRegistryRevision == realmRegistryRevision) {
            return;
        }
        indexedReconciliation = reconciliation;
        indexedPlayerSettlementRevision = playerSettlementRevision;
        indexedRealmRegistryRevision = realmRegistryRevision;
        size = 0;
        for (villageCursor.reset(); villageCursor.advance(); ) {
            Village village = villageCursor.village();
            ServerLevel level = villageCursor.level();
            if (village == null || level == null) continue;
            AABB bounds;
            try {
                bounds = village.computeBounds();
            } catch (RuntimeException ignored) {
                continue;
            }
            if (bounds == null) continue;
            ensureCapacity(size + 1);
            levels[size] = level;
            villages[size] = village;
            int boundedMinX = floor(bounds.minX);
            int boundedMinZ = floor(bounds.minZ);
            int boundedMaxX = ceilInclusive(bounds.maxX);
            int boundedMaxZ = ceilInclusive(bounds.maxZ);
            breachMinX[size] = boundedMinX;
            breachMinZ[size] = boundedMinZ;
            breachMaxX[size] = boundedMaxX;
            breachMaxZ[size] = boundedMaxZ;
            boolean expandedTerritory = false;
            if (profiles != null && realmData != null
                    && village.getId() != null && village.getId().uuid() != null) {
                UUID villageId = village.getId().uuid();
                long realmId = realmData.realmForSettlement(villageId);
                boolean hasProfile = profiles.viewCapital(villageId, playerSettlementView)
                        || realmId != RealmRegistry.NO_REALM
                                && profiles.viewRealm(realmId, playerSettlementView);
                if (hasProfile && village.isControlledBy(playerSettlementView.owner)) {
                    int radius = playerSettlementView.territoryRadius;
                    BlockPos center = village.getCenter();
                    boundedMinX = Math.min(boundedMinX, center.getX() - radius);
                    boundedMinZ = Math.min(boundedMinZ, center.getZ() - radius);
                    boundedMaxX = Math.max(boundedMaxX, center.getX() + radius);
                    boundedMaxZ = Math.max(boundedMaxZ, center.getZ() + radius);
                    expandedTerritory = true;
                }
            }
            minX[size] = boundedMinX;
            minY[size] = expandedTerritory ? level.getMinBuildHeight() : floor(bounds.minY);
            minZ[size] = boundedMinZ;
            maxX[size] = boundedMaxX;
            maxY[size] = expandedTerritory ? level.getMaxBuildHeight() - 1 : ceilInclusive(bounds.maxY);
            maxZ[size] = boundedMaxZ;
            size++;
        }
        Arrays.fill(levels, size, levels.length, null);
        Arrays.fill(villages, size, villages.length, null);
    }

    private int containingVillage(ServerPlayer player, ServerLevel level, BlockPos position) {
        int x = position.getX();
        int y = position.getY();
        int z = position.getZ();
        int nearest = -1;
        long nearestDistance = Long.MAX_VALUE;
        for (int row = 0; row < size; row++) {
            if (levels[row] != level
                    || x < minX[row] || x > maxX[row]
                    || y < minY[row] || y > maxY[row]
                    || z < minZ[row] || z > maxZ[row]) {
                continue;
            }
            Village village = villages[row];
            if (authorized(player, village)) return row;
            BlockPos center = village.getCenter();
            long dx = (long) center.getX() - x;
            long dz = (long) center.getZ() - z;
            long distance = dx * dx + dz * dz;
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = row;
            }
        }
        return nearest;
    }

    private boolean authorized(ServerPlayer player, Village village) {
        UUID playerId = player.getUUID();
        if (village.isControlledBy(playerId)) return true;
        if (ArmiesConfig.SETTLEMENT_OPERATOR_CREATIVE_BYPASS
                && player.isCreative() && player.hasPermissions(2)) {
            return true;
        }
        if (village.getId() == null || village.getId().uuid() == null) return false;
        RealmSavedData realms = lifecycle.realmData();
        if (realms == null) return false;
        long playerRealm = realms.realmForPlayer(playerId);
        return playerRealm != RealmRegistry.NO_REALM
                && playerRealm == realms.realmForSettlement(village.getId().uuid());
    }

    private boolean activePhysicalSiege(ServerLevel level, Village village) {
        if (village.isUnderAttack()) return true;
        ArmyOrderExecutionBridge execution = lifecycle.orderExecution();
        return execution != null && execution.activeSiegeNear(
                level,
                village.getCenter(),
                ArmiesConfig.SETTLEMENT_SIEGE_OBJECTIVE_RADIUS_BLOCKS);
    }

    private boolean insideBreachBand(int row, BlockPos position) {
        int x = position.getX();
        int z = position.getZ();
        int edgeDistance = Math.min(
                Math.min(x - breachMinX[row], breachMaxX[row] - x),
                Math.min(z - breachMinZ[row], breachMaxZ[row] - z));
        return edgeDistance >= 0
                && edgeDistance <= ArmiesConfig.SETTLEMENT_SIEGE_BREACH_BAND_BLOCKS;
    }

    private static boolean criticalInfrastructure(Village village, BlockPos position) {
        BuildingInstance townhall = village.getTownhall();
        if (townhall != null && townhall.containsPos(position)) return true;
        BuildingInstance building = village.getBuildingAt(position);
        if (building == null) return false;
        return contains(building.getChestPositions(), position)
                || contains(building.getFurnacePositions(), position)
                || contains(building.getFirePitPositions(), position);
    }

    private static boolean contains(List<BlockPos> positions, BlockPos requested) {
        if (positions == null || positions.isEmpty()) return false;
        for (int index = 0, length = positions.size(); index < length; index++) {
            BlockPos position = positions.get(index);
            if (requested.equals(position)) return true;
        }
        return false;
    }

    private void ensureCapacity(int required) {
        if (required <= levels.length) return;
        int capacity = Math.max(required, levels.length + (levels.length >>> 1));
        levels = Arrays.copyOf(levels, capacity);
        villages = Arrays.copyOf(villages, capacity);
        minX = Arrays.copyOf(minX, capacity);
        minY = Arrays.copyOf(minY, capacity);
        minZ = Arrays.copyOf(minZ, capacity);
        maxX = Arrays.copyOf(maxX, capacity);
        maxY = Arrays.copyOf(maxY, capacity);
        maxZ = Arrays.copyOf(maxZ, capacity);
        breachMinX = Arrays.copyOf(breachMinX, capacity);
        breachMinZ = Arrays.copyOf(breachMinZ, capacity);
        breachMaxX = Arrays.copyOf(breachMaxX, capacity);
        breachMaxZ = Arrays.copyOf(breachMaxZ, capacity);
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static int ceilInclusive(double value) {
        return (int) Math.ceil(value) - 1;
    }
}
