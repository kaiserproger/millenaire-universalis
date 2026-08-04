package ru.kaiserroman.millenairearmies.server.garrison;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.millenaire.village.Village;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.integration.millenaire.FactionProjectionService;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireVillageIndex;
import ru.kaiserroman.millenairearmies.network.ArmiesProtocol;
import ru.kaiserroman.millenairearmies.network.SetGarrisonIntent;
import ru.kaiserroman.millenairearmies.persistence.ArmySavedData;
import ru.kaiserroman.millenairearmies.persistence.PackedGarrisonState;
import ru.kaiserroman.millenairearmies.persistence.RealmGovernanceSavedData;
import ru.kaiserroman.millenairearmies.persistence.StableDimensionTable;
import ru.kaiserroman.millenairearmies.server.economy.SettlementEconomyEngine;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandAuthority;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandService;
import ru.kaiserroman.millenairearmies.server.service.PackedArmyControllers;
import ru.kaiserroman.millenairearmies.server.service.StrategicArmyOrder;

/**
 * Server-authoritative settlement garrison binding and coarse upkeep.
 *
 * <p>The service never loads chunks. Assignment resolves only the already-indexed Millenaire
 * settlement and a block in the authenticated player's current dimension. Periodic work advances a
 * fixed number of packed rows and draws only from the bound settlement's reserve-protected ledger.</p>
 */
public final class GarrisonService {
    private MinecraftServer server;
    private ArmySavedData data;
    private PackedGarrisonState state;
    private PackedArmyEcs ecs;
    private PackedArmyEcs.ArmyCursor armyCursor;
    private PackedArmyControllers controllers;
    private StableDimensionTable dimensions;
    private MillenaireVillageIndex villages;
    private MillenaireVillageIndex.Cursor villageCursor;
    private FactionProjectionService factions;
    private ArmyCommandService commands;
    private SettlementEconomyEngine economy;
    private RealmGovernanceSavedData governance;
    private int nextRow;

    public boolean start(
            MinecraftServer startingServer,
            ArmySavedData savedData,
            MillenaireVillageIndex villageIndex,
            FactionProjectionService factionProjection,
            ArmyCommandService commandService,
            SettlementEconomyEngine settlementEconomy) {
        Objects.requireNonNull(startingServer, "startingServer");
        if (server == startingServer) {
            return false;
        }
        if (server != null) {
            throw new IllegalStateException("Garrison service is already running");
        }
        if (!startingServer.isSameThread()) {
            throw new IllegalStateException("Garrison service must start on the server thread");
        }
        server = startingServer;
        data = Objects.requireNonNull(savedData, "savedData");
        state = savedData.garrisons();
        ecs = savedData.ecs();
        armyCursor = ecs.newArmyCursor();
        controllers = savedData.controllers();
        dimensions = savedData.dimensions();
        villages = Objects.requireNonNull(villageIndex, "villageIndex");
        villageCursor = villageIndex.newCursor();
        factions = Objects.requireNonNull(factionProjection, "factionProjection");
        commands = Objects.requireNonNull(commandService, "commandService");
        economy = settlementEconomy;
        governance = RealmGovernanceSavedData.get(startingServer);
        state.reserve(Math.min(ArmiesConfig.MAX_ARMIES, Math.max(4, ecs.armySize())));
        nextRow = 0;
        reconcileAll();
        return true;
    }

    public void stop(MinecraftServer stoppingServer) {
        if (server != stoppingServer) {
            return;
        }
        server = null;
        data = null;
        state = null;
        ecs = null;
        armyCursor = null;
        controllers = null;
        dimensions = null;
        villages = null;
        villageCursor = null;
        factions = null;
        commands = null;
        economy = null;
        governance = null;
        nextRow = 0;
    }

    public int apply(ServerPlayer player, SetGarrisonIntent intent) {
        if (server == null || player == null || intent == null || player.server != server) {
            return ArmiesProtocol.RESULT_INVALID;
        }
        requireServerThread();
        ArmyCommandAuthority authority = ArmyCommandAuthority.player(player.getUUID(), player.hasPermissions(2));
        int header = GarrisonAssignmentPolicy.validateHeader(
                intent.expectedRevision(),
                data.armyRevision(),
                ecs.isArmyAlive(intent.armyHandle()),
                ecs.isArmyAlive(intent.armyHandle()) && commands.canControl(authority, intent.armyHandle()));
        if (header != ArmiesProtocol.RESULT_ACCEPTED) {
            return header;
        }
        if (intent.operation() == SetGarrisonIntent.OP_CLEAR) {
            return clear(authority, intent.armyHandle())
                    ? ArmiesProtocol.RESULT_ACCEPTED
                    : ArmiesProtocol.RESULT_NOT_FOUND;
        }
        return set(player, authority, intent);
    }

    public void tick(MinecraftServer tickingServer) {
        if (server != tickingServer || state.size() == 0) {
            return;
        }
        requireServerThread();
        if (nextRow >= state.size()) {
            nextRow = 0;
        }
        int work = Math.min(ArmiesConfig.GARRISON_ROWS_PER_TICK, state.size());
        for (int processed = 0; processed < work && state.size() > 0; processed++) {
            if (nextRow >= state.size()) {
                nextRow = 0;
            }
            int armyHandle = state.armyHandleAt(nextRow);
            if (!bindingValid(nextRow)) {
                detachInvalid(armyHandle);
                continue;
            }
            long gameTime = tickingServer.overworld().getGameTime();
            if (gameTime >= state.nextUpkeepTickAt(nextRow)) {
                applyUpkeep(nextRow, gameTime);
            }
            nextRow++;
        }
    }

    public PackedGarrisonState state() {
        return state;
    }

    /** Called after village-index reconciliation so control loss is detached immediately. */
    public int reconcileAll() {
        if (server == null) {
            return 0;
        }
        requireServerThread();
        int removed = 0;
        for (int row = state.size() - 1; row >= 0; row--) {
            if (!bindingValid(row)) {
                int army = state.armyHandleAt(row);
                detachInvalid(army);
                removed++;
            }
        }
        for (armyCursor.reset(); armyCursor.advance(); ) {
            int army = armyCursor.handle();
            if (armyCursor.order() == StrategicArmyOrder.GARRISON.code()
                    && state.findArmy(army) < 0) {
                clearOrphanOrder(army);
                removed++;
            }
        }
        nextRow = Math.min(nextRow, state.size());
        return removed;
    }

    private int set(ServerPlayer player, ArmyCommandAuthority authority, SetGarrisonIntent intent) {
        ServerLevel playerLevel = player.serverLevel();
        BlockPos muster = BlockPos.of(intent.musterPosition());
        Village village = resolveVillage(player, intent, muster);
        boolean indexedSettlement = village != null && village.getId() != null
                && village.getId().uuid() != null && village.getCenter() != null;
        boolean settlementFound = indexedSettlement
                && economy != null
                && economy.isProjectionReady()
                && economy.hasActiveSettlement(
                        village.getId().uuid().getMostSignificantBits(),
                        village.getId().uuid().getLeastSignificantBits());
        ServerLevel villageLevel = indexedSettlement ? villages.level(village.getId()) : null;
        UUID actor = player.getUUID();
        boolean controlled = settlementFound && canCommandSettlement(actor, village);
        boolean factionMatches = settlementFound
                && factions.factionForVillage(village) == ecs.armyFaction(intent.armyHandle());
        long maxDistanceSq = (long) ArmiesConfig.GARRISON_MAX_MUSTER_DISTANCE
                * ArmiesConfig.GARRISON_MAX_MUSTER_DISTANCE;
        long distanceSq = indexedSettlement
                ? squaredHorizontalDistance(village.getCenter(), muster)
                : Long.MAX_VALUE;
        int validation = GarrisonAssignmentPolicy.validateAssignment(
                intent.targetDimension().equals(playerLevel.dimension().location())
                        && villageLevel == playerLevel,
                withinWorld(playerLevel, muster),
                settlementFound,
                controlled,
                factionMatches,
                distanceSq,
                maxDistanceSq,
                intent.guardRadius(),
                ArmiesConfig.GARRISON_MIN_RADIUS,
                ArmiesConfig.GARRISON_MAX_RADIUS);
        if (validation != ArmiesProtocol.RESULT_ACCEPTED) {
            return validation;
        }
        UUID villageId = village.getId().uuid();

        ResourceLocation dimension = playerLevel.dimension().location();
        long ordered = commands.issueOrder(
                authority,
                intent.armyHandle(),
                StrategicArmyOrder.GARRISON,
                dimension,
                intent.musterPosition());
        if (ordered != ArmyCommandService.SUCCESS) {
            return commandResult(ordered);
        }
        int dimensionId = dimensions.intern(dimension);
        long nextUpkeep = saturatedAdd(playerLevel.getGameTime(), ArmiesConfig.GARRISON_UPKEEP_INTERVAL_TICKS);
        boolean changed = state.assign(
                intent.armyHandle(),
                villageId.getMostSignificantBits(),
                villageId.getLeastSignificantBits(),
                dimensionId,
                intent.musterPosition(),
                intent.guardRadius(),
                nextUpkeep);
        if (changed) {
            data.markArmyChanged();
        }
        return ArmiesProtocol.RESULT_ACCEPTED;
    }

    private boolean clear(ArmyCommandAuthority authority, int armyHandle) {
        if (!state.removeArmy(armyHandle)) {
            return false;
        }
        if (ecs.isArmyAlive(armyHandle) && ecs.armyOrder(armyHandle) == StrategicArmyOrder.GARRISON.code()) {
            long result = commands.issueOrder(authority, armyHandle, StrategicArmyOrder.HOLD, 0L);
            if (result != ArmyCommandService.SUCCESS) {
                throw new IllegalStateException("Garrison clear could not commit HOLD: " + result);
            }
        }
        data.markArmyChanged();
        return true;
    }

    private Village resolveVillage(ServerPlayer player, SetGarrisonIntent intent, BlockPos muster) {
        if (intent.villageUuidMost() != 0L || intent.villageUuidLeast() != 0L) {
            return villages.find(intent.villageUuidMost(), intent.villageUuidLeast());
        }
        long bestDistance = (long) ArmiesConfig.GARRISON_SETTLEMENT_RESOLVE_RADIUS
                * ArmiesConfig.GARRISON_SETTLEMENT_RESOLVE_RADIUS;
        Village best = null;
        for (villageCursor.reset(); villageCursor.advance(); ) {
            Village candidate = villageCursor.village();
            if (candidate == null || villageCursor.level() != player.serverLevel()
                    || candidate.getCenter() == null || candidate.getId() == null
                    || candidate.getId().uuid() == null
                    || factions.factionForVillage(candidate) != ecs.armyFaction(intent.armyHandle())
                    || !canCommandSettlement(player.getUUID(), candidate)) {
                continue;
            }
            long distance = squaredHorizontalDistance(candidate.getCenter(), muster);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    private boolean bindingValid(int row) {
        int armyHandle = state.armyHandleAt(row);
        if (!ecs.isArmyAlive(armyHandle)
                || ecs.armyOrder(armyHandle) != StrategicArmyOrder.GARRISON.code()
                || !controllers.hasController(armyHandle)) {
            return false;
        }
        Village village = villages.find(state.villageMostAt(row), state.villageLeastAt(row));
        if (village == null || village.getId() == null || village.getId().uuid() == null
                || village.getCenter() == null) {
            return false;
        }
        ServerLevel level = villages.level(village.getId());
        if (level == null
                || state.dimensionIdAt(row) < 0
                || state.dimensionIdAt(row) >= dimensions.size()
                || !dimensions.name(state.dimensionIdAt(row)).equals(level.dimension().location())
                || factions.factionForVillage(village) != ecs.armyFaction(armyHandle)) {
            return false;
        }
        if (economy == null) {
            return false;
        }
        if (economy.isProjectionReady()
                && !economy.hasActiveSettlement(state.villageMostAt(row), state.villageLeastAt(row))) {
            return false;
        }
        UUID controller = new UUID(controllers.uuidMost(armyHandle), controllers.uuidLeast(armyHandle));
        if (!canCommandSettlement(controller, village)) {
            return false;
        }
        BlockPos muster = BlockPos.of(state.musterPositionAt(row));
        long maxDistanceSq = (long) ArmiesConfig.GARRISON_MAX_MUSTER_DISTANCE
                * ArmiesConfig.GARRISON_MAX_MUSTER_DISTANCE;
        return state.guardRadiusAt(row) >= ArmiesConfig.GARRISON_MIN_RADIUS
                && state.guardRadiusAt(row) <= ArmiesConfig.GARRISON_MAX_RADIUS
                && squaredHorizontalDistance(village.getCenter(), muster) <= maxDistanceSq
                && withinWorld(level, muster);
    }

    private boolean canCommandSettlement(UUID actor, Village village) {
        if (actor == null || village == null || village.getId() == null || village.getId().uuid() == null) {
            return false;
        }
        return (village.isPlayerControlled() && village.isControlledBy(actor))
                || (governance != null && governance.canCommandSettlement(actor, village.getId().uuid()));
    }

    private void clearOrphanOrder(int armyHandle) {
        if (!ecs.isArmyAlive(armyHandle)
                || ecs.armyOrder(armyHandle) != StrategicArmyOrder.GARRISON.code()) {
            return;
        }
        if (controllers.hasController(armyHandle)) {
            ArmyCommandAuthority authority = new ArmyCommandAuthority(
                    controllers.uuidMost(armyHandle), controllers.uuidLeast(armyHandle), true, false);
            long result = commands.issueOrder(authority, armyHandle, StrategicArmyOrder.HOLD, 0L);
            if (result == ArmyCommandService.SUCCESS) {
                return;
            }
        }
        // Fail closed even for a malformed legacy row which lost its controller. Execution observes
        // the ECS directly on the next bounded scan, so no physical garrison task can survive.
        ecs.armyOrder(armyHandle, StrategicArmyOrder.HOLD.code());
        data.markArmyChanged();
    }

    private void detachInvalid(int armyHandle) {
        if (!state.removeArmy(armyHandle)) {
            return;
        }
        if (ecs.isArmyAlive(armyHandle)
                && ecs.armyOrder(armyHandle) == StrategicArmyOrder.GARRISON.code()
                && controllers.hasController(armyHandle)) {
            ArmyCommandAuthority authority = new ArmyCommandAuthority(
                    controllers.uuidMost(armyHandle), controllers.uuidLeast(armyHandle), true, false);
            commands.issueOrder(authority, armyHandle, StrategicArmyOrder.HOLD, 0L);
        }
        data.markArmyChanged();
    }

    private void applyUpkeep(int row, long gameTime) {
        int armyHandle = state.armyHandleAt(row);
        int units = Math.max(0, ecs.armyUnitCount(armyHandle));
        int food = saturatedMultiply(units, ArmiesConfig.GARRISON_FOOD_PER_UNIT);
        // Until per-unit role tokens are part of the network/persistence contract, reserve arrows for
        // a conservative quarter of the warband rather than charging every melee resident.
        int rangedEstimate = units == 0 ? 0 : Math.max(1, units / 4);
        int arrows = saturatedMultiply(rangedEstimate, ArmiesConfig.GARRISON_ARROWS_PER_RANGED_UNIT);
        boolean supplied = units == 0
                || food == 0 && arrows == 0
                || economy != null && economy.tryConsumeGarrisonUpkeep(
                        state.villageMostAt(row), state.villageLeastAt(row), food, arrows);
        long next = saturatedAdd(gameTime, ArmiesConfig.GARRISON_UPKEEP_INTERVAL_TICKS);
        if (state.recordUpkeep(armyHandle, supplied, next)) {
            data.markArmyChanged();
        }
    }

    private static int commandResult(long result) {
        if (result == ArmyCommandService.SUCCESS) return ArmiesProtocol.RESULT_ACCEPTED;
        if (result == ArmyCommandService.PERMISSION_DENIED) return ArmiesProtocol.RESULT_PERMISSION_DENIED;
        if (result == ArmyCommandService.ARMY_NOT_FOUND) return ArmiesProtocol.RESULT_NOT_FOUND;
        if (result == ArmyCommandService.LIMIT_REACHED) return ArmiesProtocol.RESULT_LIMIT_REACHED;
        return ArmiesProtocol.RESULT_INVALID;
    }

    private static boolean withinWorld(ServerLevel level, BlockPos position) {
        return position.getY() >= level.getMinBuildHeight()
                && position.getY() < level.getMaxBuildHeight()
                && level.getWorldBorder().isWithinBounds(position);
    }

    private static long squaredHorizontalDistance(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static int saturatedMultiply(int left, int right) {
        long value = (long) left * right;
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Garrison service escaped the Minecraft server thread");
        }
    }
}
