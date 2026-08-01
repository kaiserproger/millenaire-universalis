package ru.kaiserroman.millenairearmies.server.diplomacy;

import net.minecraft.server.MinecraftServer;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.persistence.ArmySavedData;

/**
 * Conflict-free lifecycle adapter. The owning lifecycle calls {@link #start}, {@link #tick} and
 * {@link #stop}; this class does not subscribe to a second server event bus.
 */
public final class DiplomacyIntegration {
    private static final int MAX_DUE_COMMANDS_PER_TICK = 128;
    // Keeps the dormant scheduler/index footprint around 150 KiB at the default 256 factions.
    // A full queue applies back-pressure instead of growing during the server tick.
    private static final int MAX_RUNTIME_SCHEDULED_COMMANDS = 2_048;

    private final DiplomacyService service = new DiplomacyService();

    public boolean start(MinecraftServer server, ArmySavedData savedData) {
        int scheduledCapacity = Math.min(ArmiesConfig.MAX_PENDING_ORDERS, MAX_RUNTIME_SCHEDULED_COMMANDS);
        return service.start(
                server,
                savedData.factions(),
                ArmiesConfig.MAX_FACTIONS,
                scheduledCapacity,
                savedData::setDirty);
    }

    public int tick(MinecraftServer server) {
        return service.tick(
                server,
                server.overworld().getGameTime(),
                MAX_DUE_COMMANDS_PER_TICK);
    }

    public void stop(MinecraftServer server) {
        service.stop(server);
    }

    public DiplomacyService service() {
        return service;
    }
}
