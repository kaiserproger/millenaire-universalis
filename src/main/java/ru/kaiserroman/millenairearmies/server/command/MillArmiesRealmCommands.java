package ru.kaiserroman.millenairearmies.server.command;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.LongArgumentType.getLong;
import static com.mojang.brigadier.arguments.LongArgumentType.longArg;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.commands.arguments.UuidArgument.getUuid;
import static net.minecraft.commands.arguments.UuidArgument.uuid;
import static net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos;
import static net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import ru.kaiserroman.millenaire.realm.Constitution;
import ru.kaiserroman.millenaire.realm.DiplomaticStatus;
import ru.kaiserroman.millenaire.realm.GovernmentForm;
import ru.kaiserroman.millenaire.realm.RealmHistoricalPhase;
import ru.kaiserroman.millenaire.realm.RealmMemberKind;
import ru.kaiserroman.millenaire.realm.RealmScale;
import ru.kaiserroman.millenaire.realm.RealmRegistry;
import ru.kaiserroman.millenaire.realm.WarGoal;
import ru.kaiserroman.millenaire.simulation.PackedSettlementSimulationState;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.lifecycle.ArmyLifecycleService;
import ru.kaiserroman.millenairearmies.persistence.RealmSavedData;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;
import ru.kaiserroman.millenairearmies.server.integration.CanonicalArmyRealmIdentityResolver;
import ru.kaiserroman.millenairearmies.server.realm.CanonicalRealmDiplomacyService;
import ru.kaiserroman.millenairearmies.server.realm.FeudalLevyService;
import ru.kaiserroman.millenairearmies.server.service.StrategicArmyOrder;

/** Read-only canonical Realm diagnostics plus explicit operator war/truce controls. */
public final class MillArmiesRealmCommands {
    private static final Logger LOGGER = LogUtils.getLogger();

    private MillArmiesRealmCommands() {}

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            ArmyLifecycleService lifecycle) {
        dispatcher.register(literal("millarmies")
                .then(literal("realm")
                        .executes(context -> status(context, lifecycle))
                        .then(literal("status")
                                .executes(context -> status(context, lifecycle)))
                        .then(literal("list")
                                .executes(context -> list(context, lifecycle, 20))
                                .then(argument("limit", integer(1, 100))
                                        .executes(context -> list(
                                                context,
                                                lifecycle,
                                                getInteger(context, "limit")))))
                        .then(literal("show")
                                .then(argument("realm", longArg(1L))
                                        .executes(context -> show(context, lifecycle))))
                        .then(literal("evidence")
                                .requires(source -> source.hasPermission(2))
                                .then(argument("realm", longArg(1L))
                                        .executes(context -> evidence(context, lifecycle))))
                        .then(literal("bootstrap")
                                .requires(source -> source.hasPermission(2))
                                .then(argument("settlement", longArg(1L))
                                        .then(argument("name", word())
                                                .executes(context -> bootstrap(context, lifecycle)))))
                        .then(literal("relations")
                                .executes(context -> relations(context, lifecycle, 20))
                                .then(argument("limit", integer(1, 100))
                                        .executes(context -> relations(
                                                context,
                                                lifecycle,
                                                getInteger(context, "limit")))))
                        .then(literal("dependencies")
                                .executes(context -> dependencies(context, lifecycle, 20))
                                .then(argument("limit", integer(1, 100))
                                        .executes(context -> dependencies(
                                                context,
                                                lifecycle,
                                                getInteger(context, "limit")))))
                        .then(literal("levy")
                                .then(literal("call")
                                        .then(argument("village", uuid())
                                                .then(argument("units", integer(
                                                                1,
                                                                ArmiesConfig.FEUDAL_MAXIMUM_LEVY))
                                                        .then(argument("order", word())
                                                                .then(argument("target", blockPos())
                                                                        .executes(context -> callLevy(
                                                                                context,
                                                                                lifecycle))))))))
                        .then(literal("subject")
                                .requires(source -> source.hasPermission(2))
                                .then(argument("subject", longArg(1L))
                                        .then(argument("overlord", longArg(1L))
                                                .then(argument("autonomy", integer(0, 1000))
                                                        .then(argument("tribute", integer(0, 1000))
                                                                .then(argument("levy", integer(0, 1000))
                                                                        .executes(context -> subject(
                                                                                context,
                                                                                lifecycle))))))))
                        .then(literal("release")
                                .requires(source -> source.hasPermission(2))
                                .then(argument("subject", longArg(1L))
                                        .executes(context -> release(context, lifecycle))))
                        .then(literal("war")
                                .requires(source -> source.hasPermission(2))
                                .then(argument("source", longArg(1L))
                                        .then(argument("target", longArg(1L))
                                                .then(argument("goal", word())
                                                        .executes(context -> war(
                                                                context, lifecycle))))))
                        .then(literal("truce")
                                .requires(source -> source.hasPermission(2))
                                .then(argument("source", longArg(1L))
                                        .then(argument("target", longArg(1L))
                                                .executes(context -> truce(
                                                        context, lifecycle)))))));
    }

    private static int callLevy(
            CommandContext<CommandSourceStack> context,
            ArmyLifecycleService lifecycle) {
        ServerPlayer player = context.getSource().getPlayer();
        FeudalLevyService service = lifecycle.feudalLevyService();
        if (player == null || service == null) {
            context.getSource().sendFailure(Component.literal(
                    "Feudal levy service is unavailable or this command requires a player."));
            return 0;
        }
        StrategicArmyOrder order;
        try {
            order = StrategicArmyOrder.valueOf(getString(context, "order").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            context.getSource().sendFailure(Component.literal(
                    "Unknown order; use hold, move, rally, attack, garrison, siege, or guard."));
            return 0;
        }
        FeudalLevyService.Result result = service.call(
                player.getUUID(),
                getUuid(context, "village"),
                getInteger(context, "units"),
                order,
                context.getSource().getLevel(),
                getBlockPos(context, "target"));
        Component message = Component.literal(
                "Feudal levy " + result.outcome().name().toLowerCase(Locale.ROOT)
                        + ": raised=" + result.raisedUnits()
                        + '/' + result.requestedUnits()
                        + ", army=" + result.armyHandle()
                        + ", loyalty=" + result.loyalty()
                        + ", separatism=" + result.separatism()
                        + ", reasons=" + result.reasonMask()
                        + ", backend=" + result.backendCode());
        if (result.outcome() == FeudalLevyService.Outcome.ANSWERED
                || result.outcome() == FeudalLevyService.Outcome.REBELLED
                || result.outcome() == FeudalLevyService.Outcome.REFUSED) {
            context.getSource().sendSuccess(() -> message, true);
            return 1;
        }
        context.getSource().sendFailure(message);
        return 0;
    }

    private static int status(
            CommandContext<CommandSourceStack> context,
            ArmyLifecycleService lifecycle) {
        RealmSavedData data = data(context, lifecycle);
        if (data == null) return 0;
        CanonicalRealmDiplomacyService diplomacy = lifecycle.canonicalRealmDiplomacyService();
        CanonicalArmyRealmIdentityResolver identities = lifecycle.canonicalRealmIdentityResolver();
        long currentMilliYear = historicalMilliYear(
                context.getSource().getServer().overworld().getGameTime());
        context.getSource().sendSuccess(
                () -> Component.literal(
                        "Canonical Realm: realms=" + data.registry().realmCount()
                                + ", members=" + data.registry().memberCount()
                                + ", subjects=" + data.keys().size()
                                + ", institutions=" + data.institutions().size()
                                + ", histories=" + data.history().size()
                                + ", historical_year=" + formatYear(currentMilliYear)
                                + ", historical_year_ticks=" + ArmiesConfig.HISTORICAL_YEAR_TICKS
                                + ", formation_candidates=" + data.lifecycle().formationSize()
                                + ", crises=" + data.lifecycle().crisisSize()
                                + ", relations=" + data.diplomacy().size()
                                + ", dependencies=" + data.dependencies().size()
                                + ", diplomacy=" + (diplomacy == null ? "disabled" : "running")
                                + ", unresolved_armies="
                                + (identities == null ? "n/a" : identities.unresolvedArmyCount())),
                false);
        return 1;
    }

    private static int list(
            CommandContext<CommandSourceStack> context,
            ArmyLifecycleService lifecycle,
            int limit) {
        RealmSavedData data = data(context, lifecycle);
        if (data == null) return 0;
        long currentMilliYear = historicalMilliYear(
                context.getSource().getServer().overworld().getGameTime());
        int[] emitted = {0};
        data.registry().visitRealms((realmId, capitalMemberId, foundedCycle, government, legitimacy) -> {
            if (emitted[0] >= limit) return;
            emitted[0]++;
            String name = data.name(realmId);
            RealmHistoricalPhase phase = data.history().phase(realmId);
            RealmScale scale = data.history().scale(realmId);
            long foundedMilliYear = data.history().foundedMilliYear(realmId);
            String line = "Realm #" + realmId
                    + " name=" + (name == null ? "<unnamed>" : name)
                    + " government=" + government
                    + " phase=" + (phase == null ? "UNASSESSED" : phase)
                    + " scale=" + (scale == null ? "UNASSESSED" : scale)
                    + " age_years=" + (foundedMilliYear < 0L
                            ? "n/a"
                            : formatYear(Math.max(0L, currentMilliYear - foundedMilliYear)))
                    + " viability=" + data.history().viability(realmId)
                    + " expansion=" + data.history().expansionReadiness(realmId)
                    + " priority=" + data.statePriority(realmId)
                    + " decision_pressure=" + data.stateDecisionPressure(realmId)
                    + " legitimacy=" + legitimacy
                    + " settlements=" + data.registry().settlementCount(realmId)
                    + " members=" + data.registry().memberCount(realmId)
                    + " players=" + data.registry().hasPlayerMembers(realmId)
                    + " overlord=" + data.dependencies().overlordOf(realmId)
                    + " capital_subject=" + capitalMemberId
                    + " founded_cycle=" + foundedCycle;
            context.getSource().sendSuccess(() -> Component.literal(line), false);
        });
        if (emitted[0] == 0) {
            context.getSource().sendSuccess(() -> Component.literal("No canonical Realms"), false);
        }
        return Math.max(1, emitted[0]);
    }

    private static int show(
            CommandContext<CommandSourceStack> context,
            ArmyLifecycleService lifecycle) {
        RealmSavedData data = data(context, lifecycle);
        if (data == null) return 0;
        long realmId = getLong(context, "realm");
        if (!data.registry().exists(realmId)) {
            context.getSource().sendFailure(Component.literal("Unknown canonical Realm id"));
            return 0;
        }
        Constitution constitution = data.institutions().constitution(realmId);
        long currentMilliYear = historicalMilliYear(
                context.getSource().getServer().overworld().getGameTime());
        RealmHistoricalPhase phase = data.history().phase(realmId);
        RealmScale scale = data.history().scale(realmId);
        long foundedMilliYear = data.history().foundedMilliYear(realmId);
        String line = "Realm #" + realmId
                + " name=" + value(data.name(realmId))
                + " government=" + data.registry().government(realmId)
                + " phase=" + (phase == null ? "UNASSESSED" : phase)
                + " scale=" + (scale == null ? "UNASSESSED" : scale)
                + " age_years=" + (foundedMilliYear < 0L
                        ? "n/a"
                        : formatYear(Math.max(0L, currentMilliYear - foundedMilliYear)))
                + " viability=" + data.history().viability(realmId)
                + " capacity=" + data.history().stateCapacity(realmId)
                + " burden=" + data.history().crisisBurden(realmId)
                + " expansion=" + data.history().expansionReadiness(realmId)
                + " priority=" + data.statePriority(realmId)
                + " decision_pressure=" + data.stateDecisionPressure(realmId)
                + " investment=" + data.stateInvestmentPermille(realmId)
                + " last_decision_year=" + formatYear(data.lastStateDecisionMilliYear(realmId))
                + " legitimacy=" + data.registry().legitimacy(realmId)
                + " tax=" + data.taxRate(realmId)
                + " treasury=" + data.treasury(realmId)
                + " legacy=" + data.isLegacy(realmId)
                + " settlements=" + data.registry().settlementCount(realmId)
                + " members=" + data.registry().memberCount(realmId)
                + " overlord=" + data.dependencies().overlordOf(realmId)
                + " autonomy=" + data.dependencies().autonomy(realmId)
                + " tribute=" + data.dependencies().tributeRate(realmId)
                + " levy=" + data.dependencies().militaryLevy(realmId)
                + " capital_subject=" + data.registry().capitalMemberId(realmId);
        context.getSource().sendSuccess(() -> Component.literal(line), false);
        if (constitution != null) {
            String institutions = "institutions centralization=" + constitution.centralization()
                    + " bureaucracy=" + constitution.bureaucracy()
                    + " nobles=" + constitution.noblePower()
                    + " merchants=" + constitution.merchantPower()
                    + " citizens=" + constitution.citizenPower()
                    + " market=" + constitution.marketFreedom()
                    + " land_concentration=" + constitution.landConcentration()
                    + " militarization=" + constitution.militarization()
                    + " stable_years=" + formatYear(data.institutions().stableMilliYears(realmId))
                    + " stable_milli_years=" + data.institutions().stableMilliYears(realmId)
                    + " last_evaluation_milli_year="
                    + data.institutions().lastEvaluationMilliYear(realmId);
            context.getSource().sendSuccess(() -> Component.literal(institutions), false);
        }
        return 1;
    }

    private static int bootstrap(
            CommandContext<CommandSourceStack> context,
            ArmyLifecycleService lifecycle) {
        RealmSavedData realms = data(context, lifecycle);
        if (realms == null || lifecycle.worldSimulationBridge() == null) return 0;
        SimulationSavedData simulation = lifecycle.worldSimulationBridge().savedData();
        long simulationSettlement = getLong(context, "settlement");
        PackedSettlementSimulationState state = simulation.state();
        int row = state.find(simulationSettlement);
        if (row < 0 || !simulation.keys().validSettlement(simulationSettlement)
                || !state.physicallyPresentAt(row)) {
            context.getSource().sendFailure(Component.literal(
                    "Bootstrap requires an existing physically present Simulation settlement"));
            return 0;
        }
        UUID uuid = simulation.keys().settlement(simulationSettlement);
        long subject = realms.keys().internSettlement(uuid);
        if (realms.registry().realmOfMember(subject) != RealmRegistry.NO_REALM
                || state.realmIdAt(row) != RealmRegistry.NO_REALM) {
            context.getSource().sendFailure(Component.literal("Settlement already belongs to a Realm"));
            return 0;
        }
        long gameTime = context.getSource().getServer().overworld().getGameTime();
        long cycle = gameTime / ArmiesConfig.WORLD_SIMULATION_INTERVAL_TICKS;
        long milliYear = historicalMilliYear(gameTime);
        int legitimacy = Math.max(250, Math.min(900,
                (state.stabilityAt(row) + state.attractivenessAt(row)) / 2));
        long realmId = realms.registry().createRealm(
                subject,
                RealmMemberKind.NPC_SETTLEMENT,
                0L,
                GovernmentForm.CLAN_CONFEDERATION,
                legitimacy,
                cycle);
        if (realmId == RealmRegistry.NO_REALM) {
            context.getSource().sendFailure(Component.literal("Could not create canonical NPC Realm"));
            return 0;
        }
        String name = getString(context, "name").replace('_', ' ');
        try {
            realms.institutions().ensureRealm(
                    realmId,
                    Constitution.archetype(GovernmentForm.CLAN_CONFEDERATION, legitimacy),
                    milliYear);
            realms.upsertMetadata(realmId, name, 10, 0L, false);
            state.assignRealm(simulationSettlement, realmId);
            realms.markChanged();
            simulation.markChanged();
        } catch (RuntimeException failure) {
            realms.institutions().removeRealm(realmId);
            realms.history().removeRealm(realmId);
            realms.registry().dissolveRealm(realmId);
            realms.removeMetadata(realmId);
            throw failure;
        }
        context.getSource().sendSuccess(
                () -> Component.literal("Bootstrapped NPC Realm " + realmId
                        + " from Simulation settlement " + simulationSettlement
                        + " name=" + name),
                true);
        return 1;
    }

    private static int evidence(
            CommandContext<CommandSourceStack> context,
            ArmyLifecycleService lifecycle) {
        RealmSavedData data = data(context, lifecycle);
        if (data == null) return 0;
        long realmId = getLong(context, "realm");
        if (!data.registry().exists(realmId)) {
            context.getSource().sendFailure(Component.literal("Unknown canonical Realm id"));
            return 0;
        }
        Constitution constitution = data.institutions().constitution(realmId);
        long currentMilliYear = historicalMilliYear(
                context.getSource().getServer().overworld().getGameTime());
        RealmHistoricalPhase phase = data.history().phase(realmId);
        RealmScale scale = data.history().scale(realmId);
        long foundedMilliYear = data.history().foundedMilliYear(realmId);
        StringBuilder json = new StringBuilder(768);
        json.append('{')
                .append("\"schema\":\"millenaire.realm-development.evidence.v3\"")
                .append(",\"realm_id\":").append(realmId)
                .append(",\"name\":\"").append(value(data.name(realmId)).replace("\"", "'" )).append('\"')
                .append(",\"government\":\"").append(data.registry().government(realmId)).append('\"')
                .append(",\"historical_year_milli\":").append(currentMilliYear)
                .append(",\"historical_year_ticks\":").append(ArmiesConfig.HISTORICAL_YEAR_TICKS)
                .append(",\"phase\":\"").append(phase == null ? "UNASSESSED" : phase).append('\"')
                .append(",\"scale\":\"").append(scale == null ? "UNASSESSED" : scale).append('\"')
                .append(",\"founded_milli_year\":").append(foundedMilliYear)
                .append(",\"age_milli_years\":")
                .append(foundedMilliYear < 0L ? -1L : Math.max(0L, currentMilliYear - foundedMilliYear))
                .append(",\"phase_since_milli_year\":").append(data.history().phaseSinceMilliYear(realmId))
                .append(",\"last_history_evaluation_milli_year\":")
                .append(data.history().lastEvaluationMilliYear(realmId))
                .append(",\"last_secession_milli_year\":")
                .append(data.history().lastSecessionMilliYear(realmId))
                .append(",\"state_capacity\":").append(data.history().stateCapacity(realmId))
                .append(",\"crisis_burden\":").append(data.history().crisisBurden(realmId))
                .append(",\"viability\":").append(data.history().viability(realmId))
                .append(",\"expansion_readiness\":").append(data.history().expansionReadiness(realmId))
                .append(",\"state_priority\":\"").append(data.statePriority(realmId)).append('\"')
                .append(",\"state_decision_pressure\":").append(data.stateDecisionPressure(realmId))
                .append(",\"state_investment_permille\":").append(data.stateInvestmentPermille(realmId))
                .append(",\"last_state_decision_milli_year\":")
                .append(data.lastStateDecisionMilliYear(realmId))
                .append(",\"crisis_momentum\":").append(data.history().crisisMomentum(realmId))
                .append(",\"recovery_momentum\":").append(data.history().recoveryMomentum(realmId))
                .append(",\"crisis_rate_per_year\":").append(data.history().crisisRatePerYear(realmId))
                .append(",\"recovery_rate_per_year\":").append(data.history().recoveryRatePerYear(realmId))
                .append(",\"historical_reason_mask\":").append(data.history().reasonMask(realmId))
                .append(",\"history_revision\":").append(data.history().revision())
                .append(",\"legitimacy\":").append(data.registry().legitimacy(realmId))
                .append(",\"tax_rate\":").append(data.taxRate(realmId))
                .append(",\"treasury\":").append(data.treasury(realmId))
                .append(",\"settlements\":").append(data.registry().settlementCount(realmId))
                .append(",\"members\":").append(data.registry().memberCount(realmId))
                .append(",\"capital_subject\":").append(data.registry().capitalMemberId(realmId))
                .append(",\"overlord\":").append(data.dependencies().overlordOf(realmId))
                .append(",\"autonomy\":").append(data.dependencies().autonomy(realmId))
                .append(",\"tribute_rate\":").append(data.dependencies().tributeRate(realmId))
                .append(",\"military_levy\":").append(data.dependencies().militaryLevy(realmId))
                .append(",\"institution_revision\":").append(data.institutions().revision())
                .append(",\"metadata_revision\":").append(data.metadataRevision());
        if (constitution != null) {
            json.append(",\"centralization\":").append(constitution.centralization())
                    .append(",\"bureaucracy\":").append(constitution.bureaucracy())
                    .append(",\"noble_power\":").append(constitution.noblePower())
                    .append(",\"merchant_power\":").append(constitution.merchantPower())
                    .append(",\"citizen_power\":").append(constitution.citizenPower())
                    .append(",\"market_freedom\":").append(constitution.marketFreedom())
                    .append(",\"land_concentration\":").append(constitution.landConcentration())
                    .append(",\"militarization\":").append(constitution.militarization())
                    .append(",\"stable_milli_years\":")
                    .append(data.institutions().stableMilliYears(realmId))
                    .append(",\"last_evaluation_milli_year\":")
                    .append(data.institutions().lastEvaluationMilliYear(realmId));
        }
        json.append('}');
        LOGGER.info("[BANNEROK_REALM_DEVELOPMENT_EVIDENCE] {}", json);
        context.getSource().sendSuccess(
                () -> Component.literal("Realm-development evidence emitted for Realm " + realmId
                        + " (server log marker BANNEROK_REALM_DEVELOPMENT_EVIDENCE)"),
                false);
        return 1;
    }

    private static int relations(
            CommandContext<CommandSourceStack> context,
            ArmyLifecycleService lifecycle,
            int limit) {
        RealmSavedData data = data(context, lifecycle);
        if (data == null) return 0;
        long cycle = context.getSource().getServer().overworld().getGameTime()
                / ArmiesConfig.WORLD_SIMULATION_INTERVAL_TICKS;
        int[] emitted = {0};
        data.diplomacy().visit((firstRealm, secondRealm, storedStatus, firstGoal, secondGoal,
                firstTrust, secondTrust, firstGrievances, secondGrievances,
                firstFear, secondFear, firstClaims, secondClaims,
                firstExhaustion, secondExhaustion, firstWarScore, secondWarScore,
                trade, border, ideology, commonThreat, truceUntil, lastEvaluation) -> {
            if (emitted[0] >= limit) return;
            emitted[0]++;
            DiplomaticStatus status = data.diplomacy().status(firstRealm, secondRealm, cycle);
            String line = "Relation " + firstRealm + "<->" + secondRealm
                    + " status=" + status
                    + " goals=" + firstGoal + '/' + secondGoal
                    + " trust=" + firstTrust + '/' + secondTrust
                    + " grievances=" + firstGrievances + '/' + secondGrievances
                    + " exhaustion=" + firstExhaustion + '/' + secondExhaustion
                    + " war_score=" + firstWarScore + '/' + secondWarScore
                    + " trade=" + trade
                    + " border=" + border
                    + " ideology=" + ideology
                    + " truce_until=" + truceUntil
                    + " last_evaluation=" + lastEvaluation;
            context.getSource().sendSuccess(() -> Component.literal(line), false);
        });
        if (emitted[0] == 0) {
            context.getSource().sendSuccess(
                    () -> Component.literal("No canonical Realm relations"), false);
        }
        return Math.max(1, emitted[0]);
    }

    private static int dependencies(
            CommandContext<CommandSourceStack> context,
            ArmyLifecycleService lifecycle,
            int limit) {
        RealmSavedData data = data(context, lifecycle);
        if (data == null) return 0;
        int[] emitted = {0};
        data.dependencies().visit((subject, overlord, autonomy, tribute, levy, sinceCycle) -> {
            if (emitted[0] >= limit) return;
            emitted[0]++;
            String line = "Dependency subject=" + subject
                    + " overlord=" + overlord
                    + " autonomy=" + autonomy
                    + " tribute=" + tribute
                    + " levy=" + levy
                    + " since_cycle=" + sinceCycle;
            context.getSource().sendSuccess(() -> Component.literal(line), false);
        });
        if (emitted[0] == 0) {
            context.getSource().sendSuccess(
                    () -> Component.literal("No canonical Realm dependencies"), false);
        }
        return Math.max(1, emitted[0]);
    }

    private static int subject(
            CommandContext<CommandSourceStack> context,
            ArmyLifecycleService lifecycle) {
        RealmSavedData data = data(context, lifecycle);
        if (data == null) return 0;
        long subject = getLong(context, "subject");
        long overlord = getLong(context, "overlord");
        if (!data.registry().exists(subject) || !data.registry().exists(overlord)
                || data.isLegacy(subject) || data.isLegacy(overlord)) {
            context.getSource().sendFailure(Component.literal(
                    "Subject and overlord must be existing non-legacy canonical Realms"));
            return 0;
        }
        int autonomy = getInteger(context, "autonomy");
        int tribute = getInteger(context, "tribute");
        int levy = getInteger(context, "levy");
        long cycle = context.getSource().getServer().overworld().getGameTime()
                / ArmiesConfig.WORLD_SIMULATION_INTERVAL_TICKS;
        if (!data.dependencies().establish(
                subject, overlord, autonomy, tribute, levy, cycle)) {
            context.getSource().sendFailure(Component.literal(
                    "Could not establish dependency; capacity exhausted or hierarchy cycle detected"));
            return 0;
        }
        data.markChanged();
        context.getSource().sendSuccess(
                () -> Component.literal(
                        "Realm " + subject + " is now subject to Realm " + overlord
                                + " autonomy=" + autonomy
                                + " tribute=" + tribute
                                + " levy=" + levy),
                true);
        return 1;
    }

    private static int release(
            CommandContext<CommandSourceStack> context,
            ArmyLifecycleService lifecycle) {
        RealmSavedData data = data(context, lifecycle);
        if (data == null) return 0;
        long subject = getLong(context, "subject");
        long overlord = data.dependencies().overlordOf(subject);
        if (!data.dependencies().release(subject)) {
            context.getSource().sendFailure(Component.literal("Realm is not a dependency"));
            return 0;
        }
        data.markChanged();
        context.getSource().sendSuccess(
                () -> Component.literal(
                        "Released Realm " + subject + " from overlord " + overlord),
                true);
        return 1;
    }

    private static int war(
            CommandContext<CommandSourceStack> context,
            ArmyLifecycleService lifecycle) {
        CanonicalRealmDiplomacyService diplomacy = diplomacy(context, lifecycle);
        if (diplomacy == null) return 0;
        long source = getLong(context, "source");
        long target = getLong(context, "target");
        WarGoal goal;
        try {
            goal = WarGoal.valueOf(getString(context, "goal").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            context.getSource().sendFailure(Component.literal(
                    "Unknown war goal; use BORDER_CLAIM, SUBJUGATE, LIBERATE, TRADE_ACCESS, PUNITIVE or SUCCESSION"));
            return 0;
        }
        long cycle = context.getSource().getServer().overworld().getGameTime()
                / ArmiesConfig.WORLD_SIMULATION_INTERVAL_TICKS;
        if (!diplomacy.declareWar(source, target, goal, cycle)) {
            context.getSource().sendFailure(Component.literal("Could not declare canonical Realm war"));
            return 0;
        }
        context.getSource().sendSuccess(
                () -> Component.literal(
                        "Realm " + source + " declared " + goal + " war on Realm " + target),
                true);
        return 1;
    }

    private static int truce(
            CommandContext<CommandSourceStack> context,
            ArmyLifecycleService lifecycle) {
        CanonicalRealmDiplomacyService diplomacy = diplomacy(context, lifecycle);
        if (diplomacy == null) return 0;
        long source = getLong(context, "source");
        long target = getLong(context, "target");
        long cycle = context.getSource().getServer().overworld().getGameTime()
                / ArmiesConfig.WORLD_SIMULATION_INTERVAL_TICKS;
        if (!diplomacy.makeTruce(source, target, cycle)) {
            context.getSource().sendFailure(Component.literal("Could not establish canonical Realm truce"));
            return 0;
        }
        context.getSource().sendSuccess(
                () -> Component.literal(
                        "Realm " + source + " established a truce with Realm " + target),
                true);
        return 1;
    }

    private static RealmSavedData data(
            CommandContext<CommandSourceStack> context,
            ArmyLifecycleService lifecycle) {
        RealmSavedData data = lifecycle.realmData();
        if (data == null) {
            context.getSource().sendFailure(Component.literal(
                    "Canonical Realm lifecycle is not running"));
        }
        return data;
    }

    private static CanonicalRealmDiplomacyService diplomacy(
            CommandContext<CommandSourceStack> context,
            ArmyLifecycleService lifecycle) {
        CanonicalRealmDiplomacyService diplomacy = lifecycle.canonicalRealmDiplomacyService();
        if (diplomacy == null) {
            context.getSource().sendFailure(Component.literal(
                    "Canonical Realm diplomacy is disabled or Simulation is not running"));
        }
        return diplomacy;
    }

    private static long historicalMilliYear(long gameTime) {
        long yearTicks = ArmiesConfig.HISTORICAL_YEAR_TICKS;
        long years = gameTime / yearTicks;
        long remainder = gameTime % yearTicks;
        if (years > Long.MAX_VALUE / 1000L) return Long.MAX_VALUE;
        return years * 1000L + remainder * 1000L / yearTicks;
    }

    private static String formatYear(long milliYear) {
        if (milliYear < 0L) return "n/a";
        return (milliYear / 1000L) + "." + String.format(Locale.ROOT, "%03d", milliYear % 1000L);
    }

    private static String value(String value) {
        return value == null ? "<unnamed>" : value;
    }
}
