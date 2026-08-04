package ru.kaiserroman.millenairearmies.server.economy;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.millenaire.item.MoneyHelper;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireRecruitmentService;
import ru.kaiserroman.millenairearmies.persistence.ArmySavedData;
import ru.kaiserroman.millenairearmies.persistence.PackedUnitMembership;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandAuthority;
import ru.kaiserroman.millenairearmies.server.service.PackedArmyControllers;
import ru.kaiserroman.millenairearmies.server.service.StrategicArmyOrder;
import ru.kaiserroman.millenairearmies.server.unit.PackedUnitRoleState;

/**
 * Bounded server-thread recurring upkeep for player-controlled physical armies.
 *
 * <p>The same Millenaire denier inventory used by one-day hiring is the only player currency source.
 * A daily cold snapshot avoids cursor invalidation while armies desert/disband. Missing payments are
 * persisted per unit: first a warning, then forced HOLD/demobilization, then one expensive fighter
 * deserts per failed cycle. Regulars and nobles are intentionally much dearer than levies.</p>
 */
public final class ArmyUpkeepService {
    private final ArmyUpkeepPolicy policy = new ArmyUpkeepPolicy(
            ArmiesConfig.ARMY_LEVY_UPKEEP_DENIERS,
            ArmiesConfig.ARMY_REGULAR_UPKEEP_DENIERS,
            ArmiesConfig.ARMY_NOBLE_UPKEEP_DENIERS,
            ArmiesConfig.ARMY_DEMOBILIZE_AFTER_MISSED_UPKEEP,
            ArmiesConfig.ARMY_DESERT_AFTER_MISSED_UPKEEP);

    private MinecraftServer server;
    private ArmySavedData data;
    private MillenaireRecruitmentService recruitment;
    private PackedArmyControllers.Cursor controllerCursor;
    private int[] armies = new int[0];
    private long[] ownerMost = new long[0];
    private long[] ownerLeast = new long[0];
    private int sweepSize;
    private int sweepRow;
    private long nextSweepTime;
    private long chargedDeniers;
    private long missedPayments;
    private long desertions;

    public void start(
            MinecraftServer startingServer,
            ArmySavedData savedData,
            MillenaireRecruitmentService recruitmentService) {
        if (server != null) throw new IllegalStateException("Army upkeep service already started");
        server = Objects.requireNonNull(startingServer, "startingServer");
        data = Objects.requireNonNull(savedData, "savedData");
        recruitment = Objects.requireNonNull(recruitmentService, "recruitmentService");
        controllerCursor = data.controllers().newCursor();
        nextSweepTime = server.overworld().getGameTime() + ArmiesConfig.ARMY_UPKEEP_INTERVAL_TICKS;
        sweepSize = 0;
        sweepRow = 0;
    }

    public void stop(MinecraftServer stoppingServer) {
        if (server != stoppingServer) return;
        server = null;
        data = null;
        recruitment = null;
        controllerCursor = null;
        sweepSize = 0;
        sweepRow = 0;
        nextSweepTime = 0L;
    }

    public void tick(MinecraftServer tickingServer) {
        if (server != tickingServer || data == null) return;
        requireServerThread();
        long gameTime = server.overworld().getGameTime();
        if (sweepRow >= sweepSize && gameTime >= nextSweepTime) {
            snapshotControllers();
            nextSweepTime = gameTime + ArmiesConfig.ARMY_UPKEEP_INTERVAL_TICKS;
        }
        int work = 0;
        while (sweepRow < sweepSize && work++ < ArmiesConfig.ARMY_UPKEEP_ARMIES_PER_TICK) {
            processArmy(armies[sweepRow], ownerMost[sweepRow], ownerLeast[sweepRow]);
            sweepRow++;
        }
    }

    public ArmyUpkeepPolicy policy() { return policy; }
    public long chargedDeniers() { return chargedDeniers; }
    public long missedPayments() { return missedPayments; }
    public long desertions() { return desertions; }
    public long nextSweepTime() { return nextSweepTime; }

    private void snapshotControllers() {
        sweepSize = 0;
        sweepRow = 0;
        for (controllerCursor.reset(); controllerCursor.advance(); ) {
            if (!controllerCursor.hasController()) continue;
            ensureCapacity(sweepSize + 1);
            armies[sweepSize] = controllerCursor.armyHandle();
            ownerMost[sweepSize] = controllerCursor.uuidMost();
            ownerLeast[sweepSize] = controllerCursor.uuidLeast();
            sweepSize++;
        }
    }

    private void processArmy(int armyHandle, long controllerMost, long controllerLeast) {
        PackedArmyEcs ecs = data.ecs();
        if (!ecs.isArmyAlive(armyHandle)) return;
        PackedUnitMembership memberships = data.memberships();
        PackedUnitRoleState roles = data.unitRoles();

        int levies = 0;
        int regulars = 0;
        int nobles = 0;
        int units = 0;
        int desertCandidateUnit = 0;
        long desertCandidateMost = 0L;
        long desertCandidateLeast = 0L;
        byte desertCandidateClass = Byte.MIN_VALUE;
        boolean changed = false;

        for (int row = 0; row < memberships.size(); row++) {
            int unitHandle = memberships.unitHandleAt(row);
            if (!ecs.isUnitAlive(unitHandle) || ecs.unitArmy(unitHandle) != armyHandle) continue;
            units++;
            byte troopClass = roles.troopClass(unitHandle);
            if (troopClass == PackedUnitRoleState.TROOP_CLASS_UNCLASSIFIED) {
                roles.assignTroopClass(unitHandle, PackedUnitRoleState.TROOP_CLASS_LEVY);
                troopClass = PackedUnitRoleState.TROOP_CLASS_LEVY;
                changed = true;
            }
            switch (troopClass) {
                case PackedUnitRoleState.TROOP_CLASS_REGULAR -> regulars++;
                case PackedUnitRoleState.TROOP_CLASS_NOBLE -> nobles++;
                default -> levies++;
            }
            if (troopClass > desertCandidateClass) {
                desertCandidateClass = troopClass;
                desertCandidateUnit = unitHandle;
                desertCandidateMost = memberships.uuidMostAt(row);
                desertCandidateLeast = memberships.uuidLeastAt(row);
            }
        }
        if (units == 0) {
            if (changed) data.markArmyChanged();
            return;
        }

        int cost = policy.totalCost(levies, regulars, nobles);
        UUID controllerId = new UUID(controllerMost, controllerLeast);
        ServerPlayer player = server.getPlayerList().getPlayer(controllerId);
        boolean paid = cost == 0 || player != null && MoneyHelper.removeDeniers(player.getInventory(), cost);
        if (paid) chargedDeniers += cost;
        else missedPayments++;

        int maximumMissed = 0;
        for (int row = 0; row < memberships.size(); row++) {
            int unitHandle = memberships.unitHandleAt(row);
            if (!ecs.isUnitAlive(unitHandle) || ecs.unitArmy(unitHandle) != armyHandle) continue;
            if (paid) {
                changed |= roles.recordUpkeepPaid(unitHandle);
            } else {
                maximumMissed = Math.max(maximumMissed, roles.recordUpkeepMissed(unitHandle));
                changed = true;
            }
        }

        ArmyUpkeepPolicy.Consequence consequence = policy.consequence(paid, maximumMissed);
        if (consequence == ArmyUpkeepPolicy.Consequence.DEMOBILIZE
                || consequence == ArmyUpkeepPolicy.Consequence.DESERTION) {
            if (ecs.armyOrder(armyHandle) != StrategicArmyOrder.HOLD.code()
                    || ecs.armyState(armyHandle) != 0) {
                ecs.armyOrder(armyHandle, StrategicArmyOrder.HOLD.code());
                ecs.armyState(armyHandle, 0);
                changed = true;
            }
        }
        if (consequence == ArmyUpkeepPolicy.Consequence.DESERTION && desertCandidateUnit != 0) {
            ArmyCommandAuthority authority = ArmyCommandAuthority.player(controllerId, false);
            long released = recruitment.release(
                    authority,
                    armyHandle,
                    desertCandidateMost,
                    desertCandidateLeast);
            if (released > 0L) {
                desertions++;
                changed = true;
            }
        }
        if (changed) data.markArmyChanged();
    }

    private void ensureCapacity(int required) {
        if (required <= armies.length) return;
        int capacity = Math.max(required, armies.length < 8 ? 8 : armies.length + (armies.length >>> 1));
        armies = Arrays.copyOf(armies, capacity);
        ownerMost = Arrays.copyOf(ownerMost, capacity);
        ownerLeast = Arrays.copyOf(ownerLeast, capacity);
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Army upkeep must run on the Minecraft server thread");
        }
    }
}
