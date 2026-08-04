package ru.kaiserroman.millenairearmies.server.realm;

import com.mojang.logging.LogUtils;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.millenaire.village.Village;
import org.millenaire.village.VillageSavedData;
import org.slf4j.Logger;
import ru.kaiserroman.millenaire.realm.Constitution;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.integration.millenaire.FactionProjectionService;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireRecruitmentService;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireVillageIndex;
import ru.kaiserroman.millenairearmies.persistence.RealmGovernanceSavedData;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandAuthority;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandService;
import ru.kaiserroman.millenairearmies.server.service.StrategicArmyOrder;

/**
 * Server-authoritative suzerain call for a feudal settlement's own physical levy.
 *
 * <p>The resulting army belongs to the local feudal controller, not to the crown. The suzerain gets
 * the requested one-shot objective only after deterministic loyalty evaluation. A rebel lord is
 * detached from legacy governance, takes local ownership, raises a defensive levy where possible,
 * and marks the settlement under attack so the physical hostility/siege systems can react.</p>
 */
public final class FeudalLevyService {
    public enum Outcome {
        ANSWERED,
        REFUSED,
        REBELLED,
        NOT_FEUDAL,
        WRONG_SUZERAIN,
        VILLAGE_UNAVAILABLE,
        REALM_UNAVAILABLE,
        INVALID_ORDER,
        RAISING_FAILED,
        ORDER_FAILED
    }

    public record Result(
            Outcome outcome,
            long armyHandle,
            int requestedUnits,
            int raisedUnits,
            int loyalty,
            int separatism,
            int reasonMask,
            long backendCode) {}

    private static final Logger LOGGER = LogUtils.getLogger();

    private final FeudalLevyPolicy policy = new FeudalLevyPolicy(
            ArmiesConfig.FEUDAL_LEVY_REFUSAL_THRESHOLD,
            ArmiesConfig.FEUDAL_LEVY_REBELLION_THRESHOLD,
            ArmiesConfig.FEUDAL_MAXIMUM_LEVY);
    private final RealmGovernanceSavedData.AssignmentView assignment =
            new RealmGovernanceSavedData.AssignmentView();

    private MinecraftServer server;
    private MillenaireVillageIndex villages;
    private FactionProjectionService factions;
    private MillenaireRecruitmentService recruitment;
    private ArmyCommandService commands;
    private RealmSavedData realms;
    private RealmGovernanceSavedData governance;
    private SimulationSavedData simulation;
    private Runnable reconcileRequest;
    private long answeredCalls;
    private long refusedCalls;
    private long rebellions;
    private long raisedUnits;

    public void start(
            MinecraftServer startingServer,
            MillenaireVillageIndex villageIndex,
            FactionProjectionService factionProjection,
            MillenaireRecruitmentService recruitmentService,
            ArmyCommandService commandService,
            RealmSavedData realmData,
            RealmGovernanceSavedData governanceData,
            SimulationSavedData simulationData,
            Runnable requestReconcile) {
        if (server != null) throw new IllegalStateException("Feudal levy service already started");
        server = Objects.requireNonNull(startingServer, "startingServer");
        villages = Objects.requireNonNull(villageIndex, "villageIndex");
        factions = Objects.requireNonNull(factionProjection, "factionProjection");
        recruitment = Objects.requireNonNull(recruitmentService, "recruitmentService");
        commands = Objects.requireNonNull(commandService, "commandService");
        realms = Objects.requireNonNull(realmData, "realmData");
        governance = Objects.requireNonNull(governanceData, "governanceData");
        simulation = simulationData;
        reconcileRequest = Objects.requireNonNull(requestReconcile, "requestReconcile");
    }

    public void stop(MinecraftServer stoppingServer) {
        if (server != stoppingServer) return;
        LOGGER.info(
                "[BANNEROK_FEUDAL_LEVY_METRICS] answered={} refused={} rebellions={} raised_units={}",
                answeredCalls,
                refusedCalls,
                rebellions,
                raisedUnits);
        server = null;
        villages = null;
        factions = null;
        recruitment = null;
        commands = null;
        realms = null;
        governance = null;
        simulation = null;
        reconcileRequest = null;
    }

    public Result call(
            UUID suzerain,
            UUID villageId,
            int requestedUnits,
            StrategicArmyOrder order,
            ServerLevel targetLevel,
            BlockPos target) {
        if (server == null) return result(Outcome.VILLAGE_UNAVAILABLE, requestedUnits, 0, 0, 0, 0, -1L);
        requireServerThread();
        if (suzerain == null || villageId == null || requestedUnits < 1
                || requestedUnits > ArmiesConfig.FEUDAL_MAXIMUM_LEVY) {
            return result(Outcome.VILLAGE_UNAVAILABLE, requestedUnits, 0, 0, 0, 0, -2L);
        }
        if (order == null || order == StrategicArmyOrder.FOLLOW || order == StrategicArmyOrder.LOGISTICS
                || order.requiresTarget() && (targetLevel == null || target == null)) {
            return result(Outcome.INVALID_ORDER, requestedUnits, 0, 0, 0, 0, -3L);
        }
        if (!governance.readVillage(villageId, assignment)
                || assignment.role() != RealmGovernanceSavedData.ROLE_FEUDAL) {
            return result(Outcome.NOT_FEUDAL, requestedUnits, 0, 0, 0, 0, -4L);
        }
        if (!assignment.head().equals(suzerain)) {
            return result(Outcome.WRONG_SUZERAIN, requestedUnits, 0, 0, 0, 0, -5L);
        }
        Village village = villages.find(villageId.getMostSignificantBits(), villageId.getLeastSignificantBits());
        ServerLevel villageLevel = village == null || village.getId() == null ? null : villages.level(village.getId());
        if (village == null || villageLevel == null || village.getCenter() == null) {
            return result(Outcome.VILLAGE_UNAVAILABLE, requestedUnits, 0, 0, 0, 0, -6L);
        }
        long realmId = realms.realmForSettlement(villageId);
        if (realmId == RealmRegistry.NO_REALM || !realms.registry().exists(realmId)) {
            return result(Outcome.REALM_UNAVAILABLE, requestedUnits, 0, 0, 0, 0, -7L);
        }
        int legitimacy = realms.registry().legitimacy(realmId);
        Constitution constitution = realms.institutions().constitution(realmId);
        if (constitution == null) {
            constitution = Constitution.archetype(realms.registry().government(realmId), legitimacy);
        }
        long population = population(villageId, village);
        FeudalLevyPolicy.Decision decision = policy.evaluate(
                legitimacy,
                constitution.centralization(),
                constitution.noblePower(),
                constitution.landConcentration(),
                constitution.militarization(),
                population,
                requestedUnits);

        if (decision.response() == FeudalLevyPolicy.Response.REFUSE) {
            refusedCalls++;
            logDecision("REFUSED", suzerain, assignment.controller(), villageId, decision, requestedUnits, 0, 0L);
            return result(Outcome.REFUSED, requestedUnits, 0, decision, 0L);
        }
        if (decision.response() == FeudalLevyPolicy.Response.REBEL) {
            rebellions++;
            int rebelUnits = rebellionLevy(population, constitution.militarization(), requestedUnits);
            prepareRebellion(suzerain, assignment.controller(), village, villageLevel);
            long army = raise(
                    assignment.controller(),
                    village,
                    villageLevel,
                    rebelUnits,
                    StrategicArmyOrder.GUARD,
                    villageLevel,
                    village.getCenter());
            int raised = army >= 0L ? rebelUnits : 0;
            raisedUnits += raised;
            logDecision("REBELLED", suzerain, assignment.controller(), villageId, decision, requestedUnits, raised, army);
            return result(Outcome.REBELLED, requestedUnits, raised, decision, army);
        }

        long army = raise(
                assignment.controller(),
                village,
                villageLevel,
                decision.availableLevy(),
                order,
                targetLevel,
                target);
        if (army < 0L) {
            return result(Outcome.RAISING_FAILED, requestedUnits, 0, decision, army);
        }
        answeredCalls++;
        raisedUnits += decision.availableLevy();
        logDecision(
                "ANSWERED",
                suzerain,
                assignment.controller(),
                villageId,
                decision,
                requestedUnits,
                decision.availableLevy(),
                army);
        return result(Outcome.ANSWERED, requestedUnits, decision.availableLevy(), decision, army);
    }

    public FeudalLevyPolicy policy() { return policy; }
    public long answeredCalls() { return answeredCalls; }
    public long refusedCalls() { return refusedCalls; }
    public long rebellions() { return rebellions; }
    public long raisedUnits() { return raisedUnits; }

    private long raise(
            UUID controller,
            Village village,
            ServerLevel villageLevel,
            int units,
            StrategicArmyOrder order,
            ServerLevel targetLevel,
            BlockPos target) {
        if (units < 1) return -10L;
        ArmyCommandAuthority authority = ArmyCommandAuthority.player(controller, false);
        int faction = factions.factionForVillage(village);
        long army = recruitment.formArmyAtVillage(
                authority,
                villageLevel,
                village.getCenter(),
                village.getId().uuid().getMostSignificantBits(),
                village.getId().uuid().getLeastSignificantBits(),
                faction,
                village.getCenter().asLong(),
                units);
        if (army < 0L) return army;
        long orderResult = commands.issueOrder(
                authority,
                (int) army,
                order,
                order.requiresTarget() ? targetLevel.dimension().location() : null,
                order.requiresTarget() ? target.asLong() : village.getCenter().asLong());
        if (orderResult < 0L) {
            recruitment.disband(authority, (int) army);
            return orderResult;
        }
        return army;
    }

    private void prepareRebellion(
            UUID suzerain,
            UUID feudal,
            Village village,
            ServerLevel level) {
        governance.removeRegion(suzerain, village.getId().uuid());
        var onlineFeudal = server.getPlayerList().getPlayer(feudal);
        village.setOwner(
                feudal,
                onlineFeudal == null ? feudal.toString() : onlineFeudal.getGameProfile().getName());
        village.setUnderAttack(true);
        village.recordEvent(level, "millenaire_armies.feudal_rebellion:" + feudal);
        village.markDirty();
        VillageSavedData.get(level).setDirty();
        reconcileRequest.run();
    }

    private long population(UUID villageId, Village village) {
        if (simulation != null) {
            long settlement = simulation.keys().findSettlement(villageId);
            int row = settlement == 0L ? -1 : simulation.state().find(settlement);
            if (row >= 0) return Math.max(1L, simulation.state().populationAt(row));
        }
        long count = 0L;
        for (var record : village.getVillagerRecords().values()) {
            if (record != null && !record.isKilled()) count++;
        }
        return Math.max(1L, count);
    }

    private static int rebellionLevy(long population, int militarization, int requested) {
        long demographic = Math.max(1L, population / 20L);
        int political = 1 + Math.max(0, Math.min(1_000, militarization))
                * ArmiesConfig.FEUDAL_MAXIMUM_LEVY / 1_000;
        return (int) Math.max(1L, Math.min(
                Math.min(demographic, political),
                Math.min(requested, ArmiesConfig.FEUDAL_MAXIMUM_LEVY)));
    }

    private static Result result(
            Outcome outcome,
            int requested,
            int raised,
            FeudalLevyPolicy.Decision decision,
            long backendCode) {
        return result(
                outcome,
                requested,
                raised,
                decision.loyalty(),
                decision.separatism(),
                decision.reasonMask(),
                backendCode);
    }

    private static Result result(
            Outcome outcome,
            int requested,
            int raised,
            int loyalty,
            int separatism,
            int reasons,
            long backendCode) {
        return new Result(
                outcome,
                backendCode >= 0L ? backendCode : 0L,
                requested,
                raised,
                loyalty,
                separatism,
                reasons,
                backendCode);
    }

    private static void logDecision(
            String outcome,
            UUID suzerain,
            UUID feudal,
            UUID village,
            FeudalLevyPolicy.Decision decision,
            int requested,
            int raised,
            long army) {
        LOGGER.info(
                "[BANNEROK_FEUDAL_LEVY] outcome={} suzerain={} feudal={} village={} requested={} raised={} army={} loyalty={} separatism={} reasons={}",
                outcome,
                suzerain,
                feudal,
                village,
                requested,
                raised,
                army,
                decision.loyalty(),
                decision.separatism(),
                decision.reasonMask());
    }

    private void requireServerThread() {
        if (!server.isSameThread()) {
            throw new IllegalStateException("Feudal levy calls must run on the Minecraft server thread");
        }
    }
}
