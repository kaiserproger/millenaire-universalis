package ru.kaiserroman.millenairearmies.server.diplomacy;

import java.util.Objects;
import net.minecraft.server.MinecraftServer;
import ru.kaiserroman.millenairearmies.persistence.PackedFactionState;

/** Server-thread authority boundary around the pure packed diplomacy kernel. */
public final class DiplomacyService {
    public static final int PERMISSION_DENIED = -20;
    public static final int NOT_RUNNING = -21;

    private MinecraftServer server;
    private PackedDiplomacyEngine engine;
    private DirtyMarker dirtyMarker;

    public boolean start(
            MinecraftServer startingServer,
            PackedFactionState persistedRelations,
            int maxFactions,
            int maxScheduledCommands,
            DirtyMarker persistedDirtyMarker) {
        Objects.requireNonNull(startingServer, "startingServer");
        if (server == startingServer) {
            return false;
        }
        if (server != null) {
            throw new IllegalStateException("Diplomacy service is already attached to another server");
        }
        Objects.requireNonNull(persistedRelations, "persistedRelations");
        Objects.requireNonNull(persistedDirtyMarker, "persistedDirtyMarker");
        PackedDiplomacyEngine attachedEngine =
                new PackedDiplomacyEngine(persistedRelations, maxFactions, maxScheduledCommands);
        server = startingServer;
        engine = attachedEngine;
        dirtyMarker = persistedDirtyMarker;
        return true;
    }

    public void stop(MinecraftServer stoppingServer) {
        if (server != stoppingServer) {
            return;
        }
        server = null;
        engine = null;
        dirtyMarker = null;
    }

    public boolean isRunning() {
        return server != null;
    }

    public PackedDiplomacyEngine engine() {
        return engine;
    }

    /** Applies an authenticated player/admin command after faction ownership validation. */
    public int execute(
            DiplomacyAuthority authority,
            byte commandType,
            int sourceFactionId,
            int targetFactionId,
            int value) {
        if (server == null) {
            return NOT_RUNNING;
        }
        requireServerThread();
        Objects.requireNonNull(authority, "authority");
        if (!canIssue(authority, commandType, sourceFactionId)) {
            return PERMISSION_DENIED;
        }
        long oldRevision = engine.revision();
        int result = engine.apply(commandType, sourceFactionId, targetFactionId, value);
        markIfChanged(oldRevision);
        return result;
    }

    /** Trusted simulation hook for Millenaire politics, quests and scripted events. */
    public int executeSystem(byte commandType, int sourceFactionId, int targetFactionId, int value) {
        if (server == null) {
            return NOT_RUNNING;
        }
        requireServerThread();
        long oldRevision = engine.revision();
        int result = engine.apply(commandType, sourceFactionId, targetFactionId, value);
        markIfChanged(oldRevision);
        return result;
    }

    /** Schedules a trusted deterministic event without allocating or retaining a command object. */
    public int scheduleSystem(
            long dueGameTick,
            byte commandType,
            int sourceFactionId,
            int targetFactionId,
            int value) {
        if (server == null) {
            return NOT_RUNNING;
        }
        requireServerThread();
        return engine.schedule(dueGameTick, commandType, sourceFactionId, targetFactionId, value);
    }

    public int tick(MinecraftServer tickingServer, long gameTick, int commandBudget) {
        if (server == null || server != tickingServer) {
            return NOT_RUNNING;
        }
        requireServerThread();
        long oldRevision = engine.revision();
        int result = engine.processDue(gameTick, commandBudget);
        markIfChanged(oldRevision);
        return result;
    }

    private boolean canIssue(DiplomacyAuthority authority, byte commandType, int sourceFactionId) {
        if (authority.operator()) {
            return true;
        }
        if (authority.factionId() != sourceFactionId) {
            return false;
        }
        // Reputation/treaty acceptance is decided by authoritative game systems. A faction may
        // unilaterally start or leave a relationship, but cannot grant itself reputation or force
        // another faction to accept peace/alliance through a client packet.
        return commandType == DiplomacyCommand.DECLARE_WAR
                || commandType == DiplomacyCommand.BREAK_ALLIANCE
                || commandType == DiplomacyCommand.BECOME_VASSAL
                || commandType == DiplomacyCommand.RELEASE_VASSAL;
    }

    private void markIfChanged(long oldRevision) {
        if (oldRevision != engine.revision()) {
            dirtyMarker.markDirty();
        }
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Diplomacy commands must run on the Minecraft server thread");
        }
    }

    @FunctionalInterface
    public interface DirtyMarker {
        void markDirty();
    }
}
