package ru.kaiserroman.millenairearmies.server.service;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.persistence.StableDimensionTable;

/**
 * Server-authoritative façade for commands and authenticated network requests.
 *
 * <p>The persisted stores are attached at server start and have exactly one owner outside this
 * façade. This class never creates a shadow ECS and never clears persisted state on stop. All
 * mutations are command-path operations. There is deliberately no tick method and no combat,
 * target selection, pathfinding, or world mutation.</p>
 */
public final class ArmyCommandService {
    public static final long SUCCESS = 1L;
    public static final long NOT_RUNNING = -1L;
    public static final long PERMISSION_DENIED = -2L;
    public static final long ARMY_NOT_FOUND = -3L;
    public static final long LIMIT_REACHED = -4L;
    public static final long INVALID_FACTION = -5L;
    public static final long INVALID_ORDER = -6L;
    public static final long INVALID_DIMENSION = -7L;

    private MinecraftServer server;
    private PackedArmyEcs ecs;
    private PackedArmyEcs.ArmyCursor armyCursor;
    private PackedArmyControllers controllers;
    private StableDimensionTable dimensions;
    private DirtyMarker dirtyMarker;
    private FactionValidator factionValidator = ArmyCommandService::defaultFactionId;
    private ArmyOrderCommitListener orderCommitListener = ArmyOrderCommitListener.NOOP;

    /** Attaches the single persisted stores. Called once from lifecycle on the server thread. */
    public boolean start(
            MinecraftServer startingServer,
            PackedArmyEcs persistedEcs,
            PackedArmyControllers persistedControllers,
            StableDimensionTable persistedDimensions,
            DirtyMarker persistedDirtyMarker) {
        Objects.requireNonNull(startingServer, "startingServer");
        if (server == startingServer) {
            return false;
        }
        if (server != null) {
            throw new IllegalStateException("Army command service is already attached to another server");
        }
        Objects.requireNonNull(persistedEcs, "persistedEcs");
        Objects.requireNonNull(persistedControllers, "persistedControllers");
        Objects.requireNonNull(persistedDimensions, "persistedDimensions");
        Objects.requireNonNull(persistedDirtyMarker, "persistedDirtyMarker");
        server = startingServer;
        ecs = persistedEcs;
        controllers = persistedControllers;
        dimensions = persistedDimensions;
        dirtyMarker = persistedDirtyMarker;
        ecs.reserveArmies(ArmiesConfig.MAX_ARMIES);
        controllers.reserve(ArmiesConfig.MAX_ARMIES);
        armyCursor = ecs.newArmyCursor();
        return true;
    }

    /** Installs the lifecycle-owned stable faction dictionary after Millenaire projection starts. */
    public void installFactionValidator(FactionValidator validator) {
        if (server == null) {
            throw new IllegalStateException("Army command service is not running");
        }
        requireServerThread();
        factionValidator = Objects.requireNonNull(validator, "validator");
    }

    /** Installs the server-thread execution projection; the persisted ECS remains authoritative. */
    public void installOrderCommitListener(ArmyOrderCommitListener listener) {
        if (server == null) {
            throw new IllegalStateException("Army command service is not running");
        }
        requireServerThread();
        orderCommitListener = Objects.requireNonNull(listener, "listener");
    }

    /** Detaches references only. Persisted stores remain intact for save/shutdown. */
    public void stop(MinecraftServer stoppingServer) {
        if (server != stoppingServer) {
            return;
        }
        server = null;
        ecs = null;
        armyCursor = null;
        controllers = null;
        dimensions = null;
        dirtyMarker = null;
        factionValidator = ArmyCommandService::defaultFactionId;
        orderCommitListener = ArmyOrderCommitListener.NOOP;
    }

    public boolean isRunning() {
        return server != null;
    }

    public int armyCount() {
        return ecs == null ? 0 : ecs.armySize();
    }

    public int unitCount() {
        return ecs == null ? 0 : ecs.unitSize();
    }

    /**
     * Creates an empty strategic army. Only an operator may create one through this boundary.
     * The unsigned raw 32-bit handle is returned as a non-negative long; failures are negative.
     */
    public long createArmy(ArmyCommandAuthority authority, int factionId, long packedPosition) {
        return createArmy(authority, factionId, packedPosition, null);
    }

    public long createArmy(
            ArmyCommandAuthority authority,
            int factionId,
            long packedPosition,
            ResourceLocation targetDimension) {
        if (server == null) {
            return NOT_RUNNING;
        }
        requireServerThread();
        Objects.requireNonNull(authority, "authority");
        if (!authority.operator()) {
            return PERMISSION_DENIED;
        }
        return createAuthorizedArmy(
                authority,
                factionId,
                StrategicArmyOrder.HOLD,
                packedPosition,
                targetDimension);
    }

    /**
     * Trusted server-side boundary for player raising after the caller has validated Millenaire
     * settlement ownership. It is intentionally not used by packet or Brigadier create handlers.
     */
    public long createArmyForVerifiedSettlementOwner(
            ArmyCommandAuthority authority,
            int factionId,
            StrategicArmyOrder initialOrder,
            long packedTargetPosition,
            ResourceLocation targetDimension) {
        if (server == null) {
            return NOT_RUNNING;
        }
        requireServerThread();
        Objects.requireNonNull(authority, "authority");
        if (!authority.hasIdentity()) {
            return PERMISSION_DENIED;
        }
        if (initialOrder == null) {
            return INVALID_ORDER;
        }
        return createAuthorizedArmy(
                authority,
                factionId,
                initialOrder,
                packedTargetPosition,
                targetDimension);
    }

    private long createAuthorizedArmy(
            ArmyCommandAuthority authority,
            int factionId,
            StrategicArmyOrder initialOrder,
            long packedTargetPosition,
            ResourceLocation targetDimension) {
        if (!factionValidator.isValid(factionId)) {
            return INVALID_FACTION;
        }
        if (ecs.armySize() >= ArmiesConfig.MAX_ARMIES) {
            return LIMIT_REACHED;
        }
        if (targetDimension == null) {
            return INVALID_DIMENSION;
        }

        int targetDimensionId = dimensions.intern(targetDimension);
        int handle = ecs.createArmy(
                factionId,
                initialOrder.code(),
                0,
                targetDimensionId,
                packedTargetPosition);
        controllers.put(
                handle,
                authority.uuidMost(),
                authority.uuidLeast(),
                authority.hasIdentity());
        dirtyMarker.markDirty();
        orderCommitListener.committed(
                handle, initialOrder.code(), targetDimensionId, packedTargetPosition);
        return Integer.toUnsignedLong(handle);
    }

    /** Applies a non-combat strategic intent without performing a path search. */
    public long issueOrder(
            ArmyCommandAuthority authority,
            int armyHandle,
            StrategicArmyOrder order,
            long packedTargetPosition) {
        return issueOrder(authority, armyHandle, order, null, packedTargetPosition);
    }

    public long issueOrder(
            ArmyCommandAuthority authority,
            int armyHandle,
            StrategicArmyOrder order,
            ResourceLocation targetDimension,
            long packedTargetPosition) {
        if (server == null) {
            return NOT_RUNNING;
        }
        requireServerThread();
        Objects.requireNonNull(authority, "authority");
        if (!ecs.isArmyAlive(armyHandle)) {
            return ARMY_NOT_FOUND;
        }
        if (!canControl(authority, armyHandle)) {
            return PERMISSION_DENIED;
        }
        if (order == null) {
            return INVALID_ORDER;
        }
        if (order.requiresTarget() && targetDimension == null) {
            return INVALID_DIMENSION;
        }

        boolean changed = ecs.armyOrder(armyHandle) != order.code();
        int targetDimensionId = ecs.armyTargetDimension(armyHandle);
        if (order.requiresTarget()) {
            targetDimensionId = dimensions.intern(targetDimension);
            changed |= ecs.armyTargetDimension(armyHandle) != targetDimensionId;
            changed |= ecs.armyPackedTargetPos(armyHandle) != packedTargetPosition;
        }
        if (changed) {
            ecs.armyOrder(armyHandle, order.code());
            if (order.requiresTarget()) {
                ecs.armyTargetDimension(armyHandle, targetDimensionId);
                ecs.armyPackedTargetPos(armyHandle, packedTargetPosition);
            }
            dirtyMarker.markDirty();
            orderCommitListener.committed(
                    armyHandle,
                    order.code(),
                    ecs.armyTargetDimension(armyHandle),
                    ecs.armyPackedTargetPos(armyHandle));
        }
        return SUCCESS;
    }

    public boolean canControl(ArmyCommandAuthority authority, int armyHandle) {
        return ArmyCommandAuthorization.canControl(authority, controllers, armyHandle);
    }

    /** Visits only armies visible to this authority. Intended for command output and UI sync. */
    public int visitVisibleArmies(ArmyCommandAuthority authority, ArmyViewSink sink) {
        if (server == null) {
            return (int) NOT_RUNNING;
        }
        requireServerThread();
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(sink, "sink");
        int visited = 0;
        armyCursor.reset();
        while (armyCursor.advance()) {
            int handle = armyCursor.handle();
            if (!canControl(authority, handle)) {
                continue;
            }
            sink.accept(
                    handle,
                    armyCursor.faction(),
                    armyCursor.order(),
                    armyCursor.state(),
                    armyCursor.unitCount(),
                    armyCursor.packedTargetPos());
            visited++;
        }
        return visited;
    }

    public PackedArmyEcs ecs() {
        return ecs;
    }

    public PackedArmyControllers controllers() {
        return controllers;
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Army commands must be scheduled on the Minecraft server thread");
        }
    }

    @FunctionalInterface
    public interface ArmyViewSink {
        void accept(int handle, int faction, int order, int state, int units, long packedTargetPosition);
    }

    @FunctionalInterface
    public interface DirtyMarker {
        void markDirty();
    }

    @FunctionalInterface
    public interface FactionValidator {
        boolean isValid(int factionId);
    }

    @FunctionalInterface
    public interface ArmyOrderCommitListener {
        ArmyOrderCommitListener NOOP =
                (armyHandle, orderCode, targetDimensionId, packedTargetPosition) -> {};

        /** Called exactly once after a changed order has been committed to the packed ECS. */
        void committed(
                int armyHandle,
                int orderCode,
                int targetDimensionId,
                long packedTargetPosition);
    }

    private static boolean defaultFactionId(int factionId) {
        return factionId >= 0 && factionId < ArmiesConfig.MAX_FACTIONS;
    }
}
