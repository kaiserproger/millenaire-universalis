package ru.kaiserroman.millenairearmies.server.service;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.model.ArmyFormation;
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
    public static final long INVALID_FORMATION = -7L;

    private MinecraftServer server;
    private PackedArmyEcs ecs;
    private PackedArmyEcs.ArmyCursor armyCursor;
    private PackedArmyControllers controllers;
    private StableDimensionTable dimensions;
    private DirtyMarker dirtyMarker;
    private FactionValidator factionValidator = ArmyCommandService::defaultFactionId;
    private ArmyOrderValidator armyOrderValidator = ArmyOrderValidator.ALLOW_ALL;
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

    public void installArmyOrderValidator(ArmyOrderValidator validator) {
        Objects.requireNonNull(validator, "validator");
        if (server != null) requireServerThread();
        armyOrderValidator = validator;
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
        return createArmy(authority, factionId, Level.OVERWORLD.location(), packedPosition);
    }

    public long createArmy(
            ArmyCommandAuthority authority,
            int factionId,
            ResourceLocation targetDimension,
            long packedPosition) {
        if (server == null) {
            return NOT_RUNNING;
        }
        requireServerThread();
        Objects.requireNonNull(authority, "authority");
        if (!authority.operator()) {
            return PERMISSION_DENIED;
        }
        return createArmyInternal(authority, factionId, targetDimension, packedPosition);
    }

    /**
     * Creates a player-controlled army after an integration service has already validated the
     * selected settlement and faction. The authenticated authority remains the controller source.
     */
    public long createControlledArmy(
            ArmyCommandAuthority authority, int factionId, long packedPosition) {
        return createControlledArmy(authority, factionId, Level.OVERWORLD.location(), packedPosition);
    }

    public long createControlledArmy(
            ArmyCommandAuthority authority,
            int factionId,
            ResourceLocation targetDimension,
            long packedPosition) {
        if (server == null) {
            return NOT_RUNNING;
        }
        requireServerThread();
        Objects.requireNonNull(authority, "authority");
        if (!authority.operator() && !authority.hasIdentity()) {
            return PERMISSION_DENIED;
        }
        return createArmyInternal(authority, factionId, targetDimension, packedPosition);
    }

    private long createArmyInternal(
            ArmyCommandAuthority authority,
            int factionId,
            ResourceLocation targetDimension,
            long packedPosition) {
        Objects.requireNonNull(targetDimension, "targetDimension");
        if (!factionValidator.isValid(factionId)) {
            return INVALID_FACTION;
        }
        if (ecs.armySize() >= ArmiesConfig.MAX_ARMIES) {
            return LIMIT_REACHED;
        }

        int targetDimensionId = dimensions.intern(targetDimension);
        int handle = ecs.createArmy(
                factionId, StrategicArmyOrder.HOLD.code(), 0, targetDimensionId, packedPosition);
        controllers.put(
                handle,
                authority.uuidMost(),
                authority.uuidLeast(),
                authority.hasIdentity());
        dirtyMarker.markDirty();
        orderCommitListener.committed(
                handle,
                StrategicArmyOrder.HOLD.code(),
                ecs.armyState(handle),
                targetDimensionId,
                packedPosition);
        return Integer.toUnsignedLong(handle);
    }

    /**
     * Rolls back a just-created controlled army when a compound recruitment command cannot finish.
     * The caller must first remove every unit it created; populated armies are never removed here.
     */
    public boolean rollbackEmptyControlledArmy(ArmyCommandAuthority authority, int armyHandle) {
        if (server == null) {
            return false;
        }
        requireServerThread();
        Objects.requireNonNull(authority, "authority");
        if (!ecs.isArmyAlive(armyHandle)
                || ecs.armyUnitCount(armyHandle) != 0
                || !canControl(authority, armyHandle)) {
            return false;
        }
        boolean controllerRemoved = controllers.remove(armyHandle);
        boolean armyRemoved = ecs.removeArmy(armyHandle);
        if (controllerRemoved || armyRemoved) {
            dirtyMarker.markDirty();
        }
        return controllerRemoved && armyRemoved;
    }

    /** Applies a non-combat strategic intent without performing a path search. */
    public long issueOrder(
            ArmyCommandAuthority authority,
            int armyHandle,
            StrategicArmyOrder order,
            long packedTargetPosition) {
        return issueOrder(
                authority, armyHandle, order, Level.OVERWORLD.location(), packedTargetPosition);
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
        Objects.requireNonNull(targetDimension, "targetDimension");
        if (!armyOrderValidator.isValid(
                authority, armyHandle, order, targetDimension, packedTargetPosition)) {
            return INVALID_ORDER;
        }

        boolean changed = ecs.armyOrder(armyHandle) != order.code();
        if (order.requiresTarget()) {
            int targetDimensionId = dimensions.intern(targetDimension);
            changed |= ecs.armyTargetDimension(armyHandle) != targetDimensionId;
            changed |= ecs.armyPackedTargetPos(armyHandle) != packedTargetPosition;
            if (changed) {
                ecs.armyTargetDimension(armyHandle, targetDimensionId);
            }
        }
        if (changed) {
            ecs.armyOrder(armyHandle, order.code());
            ecs.clearArmyTargetVillage(armyHandle);
            if (order.requiresTarget()) {
                ecs.armyPackedTargetPos(armyHandle, packedTargetPosition);
            }
            dirtyMarker.markDirty();
            orderCommitListener.committed(
                    armyHandle,
                    order.code(),
                    ecs.armyState(armyHandle),
                    ecs.armyTargetDimension(armyHandle),
                    ecs.armyPackedTargetPos(armyHandle));
        }
        return SUCCESS;
    }

    /** Persists one controlled army's formation and forces retained tasks to acknowledge it. */
    public long setFormation(
            ArmyCommandAuthority authority, int armyHandle, ArmyFormation formation) {
        if (server == null) return NOT_RUNNING;
        requireServerThread();
        Objects.requireNonNull(authority, "authority");
        if (!ecs.isArmyAlive(armyHandle)) return ARMY_NOT_FOUND;
        if (!canControl(authority, armyHandle)) return PERMISSION_DENIED;
        if (formation == null) return INVALID_FORMATION;

        int current = ecs.armyState(armyHandle);
        int updated = formation.applyToState(current);
        if (updated == current) return SUCCESS;
        ecs.armyState(armyHandle, updated);
        dirtyMarker.markDirty();
        orderCommitListener.committed(
                armyHandle,
                ecs.armyOrder(armyHandle),
                updated,
                ecs.armyTargetDimension(armyHandle),
                ecs.armyPackedTargetPos(armyHandle));
        return SUCCESS;
    }

    public boolean canControl(ArmyCommandAuthority authority, int armyHandle) {
        Objects.requireNonNull(authority, "authority");
        return authority.operator()
                || authority.hasIdentity()
                        && controllers.matches(armyHandle, authority.uuidMost(), authority.uuidLeast());
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
    public interface ArmyOrderValidator {
        ArmyOrderValidator ALLOW_ALL = (authority, armyHandle, order, dimension, target) -> true;

        boolean isValid(
                ArmyCommandAuthority authority,
                int armyHandle,
                StrategicArmyOrder order,
                ResourceLocation targetDimension,
                long packedTargetPosition);
    }

    @FunctionalInterface
    public interface ArmyOrderCommitListener {
        ArmyOrderCommitListener NOOP =
                (armyHandle, orderCode, armyState, targetDimensionId, packedTargetPosition) -> {};

        /** Called exactly once after a changed order has been committed to the packed ECS. */
        void committed(
                int armyHandle,
                int orderCode,
                int armyState,
                int targetDimensionId,
                long packedTargetPosition);
    }

    private static boolean defaultFactionId(int factionId) {
        return factionId >= 0 && factionId < ArmiesConfig.MAX_FACTIONS;
    }
}
