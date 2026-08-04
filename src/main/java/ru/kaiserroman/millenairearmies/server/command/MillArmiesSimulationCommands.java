package ru.kaiserroman.millenairearmies.server.command;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.LongArgumentType.getLong;
import static com.mojang.brigadier.arguments.LongArgumentType.longArg;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.word;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import ru.kaiserroman.millenaire.simulation.PackedSettlementSimulationState;
import ru.kaiserroman.millenaire.simulation.ShockType;
import ru.kaiserroman.millenaire.simulation.SimulationEvent;
import ru.kaiserroman.millenaire.simulation.WorldShock;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireWorldSimulationBridge;
import ru.kaiserroman.millenairearmies.lifecycle.ArmyLifecycleService;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;

/** Operator/read-only surface for the persisted world-simulation vertical slice. */
public final class MillArmiesSimulationCommands {
    private static final Logger LOGGER = LogUtils.getLogger();

    private MillArmiesSimulationCommands() {}

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            ArmyLifecycleService lifecycle) {
        dispatcher.register(literal("millarmies")
                .then(literal("simulation")
                        .executes(context -> status(context, lifecycle))
                        .then(literal("status").executes(context -> status(context, lifecycle)))
                        .then(literal("events")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> events(context, lifecycle, 20))
                                .then(argument("limit", integer(1, 100))
                                        .executes(context -> events(
                                                context, lifecycle, getInteger(context, "limit")))))
                        .then(literal("settlements")
                                .executes(context -> settlements(context, lifecycle, 20))
                                .then(argument("limit", integer(1, 100))
                                        .executes(context -> settlements(
                                                context, lifecycle, getInteger(context, "limit")))))
                        .then(literal("ack")
                                .requires(source -> source.hasPermission(2))
                                .then(argument("sequence", longArg(1L))
                                        .executes(context -> acknowledge(context, lifecycle))))
                        .then(literal("settlement")
                                .then(argument("id", longArg(1L))
                                        .executes(context -> settlement(context, lifecycle))))
                        .then(literal("evidence")
                                .requires(source -> source.hasPermission(2))
                                .then(argument("id", longArg(1L))
                                        .executes(context -> evidence(context, lifecycle))))
                        .then(literal("shock")
                                .requires(source -> source.hasPermission(2))
                                .then(argument("settlement", longArg(1L))
                                        .then(argument("type", word())
                                                .then(argument("magnitude", integer(1, 1000))
                                                        .then(argument("cycles", integer(1, 10_000))
                                                                .executes(context -> shock(
                                                                        context,
                                                                        lifecycle)))))))));
    }

    private static int status(
            CommandContext<CommandSourceStack> context,
            ArmyLifecycleService lifecycle) {
        MillenaireWorldSimulationBridge bridge = bridge(context, lifecycle);
        if (bridge == null) return 0;
        SimulationSavedData data = bridge.savedData();
        PackedSettlementSimulationState state = data.state();
        context.getSource().sendSuccess(
                () -> Component.literal(
                        "Millenaire Simulation: settlements=" + state.size()
                                + ", cultures=" + data.keys().cultureCount()
                                + ", dimensions=" + data.keys().dimensionCount()
                                + ", state_revision=" + state.revision()
                                + ", scanning=" + bridge.isScanning()
                                + ", scan_revisions=" + bridge.completedRevisionCount()
                                + ", events=" + data.events().size() + '/' + data.events().capacity()
                                + ", dropped=" + data.events().droppedEventCount()
                                + ", shocks=" + data.shocks().size() + '/' + data.shocks().capacity()
                                + ", world_mutation="
                                + (lifecycle.worldMutationService() == null ? "disabled" : "enabled")
                                + ", mutation_sequence=" + data.mutationSequence()
                                + ", mutation_attempts=" + data.mutationAttempts()
                                + ", mutation_next_tick=" + data.nextMutationAttemptTick()
                                + ", simulated_cycles=" + bridge.engine().simulatedCycleCount()
                                + ", endogenous_shocks="
                                + bridge.regionalDynamics().endogenousShockCount()
                                + ", propagated_shocks="
                                + bridge.regionalDynamics().propagatedShockCount()
                                + ", regional_evaluating=" + bridge.regionalDynamics().isEvaluating()
                                + ", refugee_flows=" + bridge.regionalDynamics().refugeeFlowCount()
                                + ", relocated_population="
                                + bridge.regionalDynamics().relocatedPopulationCount()),
                false);
        return 1;
    }

    private static int events(
            CommandContext<CommandSourceStack> context,
            ArmyLifecycleService lifecycle,
            int limit) {
        MillenaireWorldSimulationBridge bridge = bridge(context, lifecycle);
        if (bridge == null) return 0;
        int[] emitted = {0};
        bridge.savedData().events().visit((sequence, event) -> {
            if (emitted[0] >= limit) return;
            emitted[0]++;
            context.getSource().sendSuccess(
                    () -> Component.literal(formatEvent(sequence, event)),
                    false);
        });
        if (emitted[0] == 0) {
            context.getSource().sendSuccess(
                    () -> Component.literal("Simulation event journal is empty"), false);
        }
        return Math.max(1, emitted[0]);
    }

    private static int settlements(
            CommandContext<CommandSourceStack> context,
            ArmyLifecycleService lifecycle,
            int limit) {
        MillenaireWorldSimulationBridge bridge = bridge(context, lifecycle);
        if (bridge == null) return 0;
        SimulationSavedData data = bridge.savedData();
        PackedSettlementSimulationState state = data.state();
        int emitted = Math.min(limit, state.size());
        for (int row = 0; row < emitted; row++) {
            long settlementId = state.settlementIdAt(row);
            UUID uuid = data.keys().settlement(settlementId);
            String line = "Simulation settlement " + settlementId
                    + " uuid=" + uuid
                    + " culture=" + data.keys().culture(state.cultureKeyAt(row))
                    + " realm=" + state.realmIdAt(row)
                    + " region=" + state.regionKeyAt(row)
                    + " population=" + state.populationAt(row)
                    + " observed=" + state.observedPopulationAt(row)
                    + " status=" + state.statusAt(row)
                    + " present=" + state.physicallyPresentAt(row);
            context.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        if (emitted == 0) {
            context.getSource().sendSuccess(
                    () -> Component.literal("No Simulation settlements"), false);
        }
        return Math.max(1, emitted);
    }

    private static int acknowledge(
            CommandContext<CommandSourceStack> context,
            ArmyLifecycleService lifecycle) {
        MillenaireWorldSimulationBridge bridge = bridge(context, lifecycle);
        if (bridge == null) return 0;
        long sequence = getLong(context, "sequence");
        SimulationSavedData data = bridge.savedData();
        long mutationSequence = data.mutationSequence();
        int removed = data.events().acknowledgeThrough(sequence);
        if (mutationSequence != 0L && mutationSequence <= sequence) {
            data.completeMutationAttempt(mutationSequence);
        }
        if (removed > 0) data.markChanged();
        context.getSource().sendSuccess(
                () -> Component.literal("Acknowledged simulation events through " + sequence
                        + "; removed=" + removed),
                true);
        return 1;
    }

    private static int shock(
            CommandContext<CommandSourceStack> context,
            ArmyLifecycleService lifecycle) {
        MillenaireWorldSimulationBridge bridge = bridge(context, lifecycle);
        if (bridge == null) return 0;
        long settlementId = getLong(context, "settlement");
        SimulationSavedData data = bridge.savedData();
        int row = data.state().find(settlementId);
        if (row < 0 || !data.keys().validSettlement(settlementId)) {
            context.getSource().sendFailure(Component.literal("Unknown simulation settlement id"));
            return 0;
        }
        ShockType type;
        try {
            type = ShockType.valueOf(
                    getString(context, "type").replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            context.getSource().sendFailure(Component.literal(
                    "Unknown shock type; expected harvest_failure, epidemic, trade_boom, "
                            + "migration_wave, war_devastation or technology_diffusion"));
            return 0;
        }
        int magnitude = getInteger(context, "magnitude");
        int cycles = getInteger(context, "cycles");
        long gameTime = context.getSource().getServer().overworld().getGameTime();
        boolean accepted = bridge.applyShock(
                new WorldShock(type, settlementId, 0L, 0, magnitude, cycles),
                gameTime);
        if (!accepted) {
            context.getSource().sendFailure(Component.literal(
                    "Simulation shock capacity is full; shock rejected"));
            return 0;
        }
        context.getSource().sendSuccess(
                () -> Component.literal(
                        "Applied " + type + " to simulation settlement " + settlementId
                                + " magnitude=" + magnitude + " cycles=" + cycles),
                true);
        return 1;
    }

    private static int settlement(
            CommandContext<CommandSourceStack> context,
            ArmyLifecycleService lifecycle) {
        MillenaireWorldSimulationBridge bridge = bridge(context, lifecycle);
        if (bridge == null) return 0;
        long settlementId = getLong(context, "id");
        SimulationSavedData data = bridge.savedData();
        PackedSettlementSimulationState state = data.state();
        int row = state.find(settlementId);
        if (row < 0 || !data.keys().validSettlement(settlementId)) {
            context.getSource().sendFailure(Component.literal("Unknown simulation settlement id"));
            return 0;
        }
        UUID uuid = data.keys().settlement(settlementId);
        StringBuilder prices = new StringBuilder();
        for (int commodity = 0; commodity < state.commodityCount(); commodity++) {
            if (commodity > 0) prices.append(',');
            prices.append(commodityName(commodity))
                    .append('=')
                    .append(state.priceIndexAt(row, commodity));
        }
        String line = "Simulation settlement " + settlementId
                + " uuid=" + uuid
                + " culture=" + data.keys().culture(state.cultureKeyAt(row))
                + " realm=" + state.realmIdAt(row)
                + " region=" + state.regionKeyAt(row)
                + " status=" + state.statusAt(row)
                + " tier=" + state.tierAt(row)
                + " population=" + state.populationAt(row)
                + " observed_population=" + state.observedPopulationAt(row)
                + " productivity=" + state.productivityAt(row)
                + " stability=" + state.stabilityAt(row)
                + " attractiveness=" + state.attractivenessAt(row)
                + " capital=" + state.productiveCapitalAt(row)
                + " present=" + state.physicallyPresentAt(row)
                + " prices[" + prices + ']';
        context.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    private static int evidence(
            CommandContext<CommandSourceStack> context,
            ArmyLifecycleService lifecycle) {
        MillenaireWorldSimulationBridge bridge = bridge(context, lifecycle);
        if (bridge == null) return 0;
        long settlementId = getLong(context, "id");
        SimulationSavedData data = bridge.savedData();
        PackedSettlementSimulationState state = data.state();
        int row = state.find(settlementId);
        if (row < 0 || !data.keys().validSettlement(settlementId)) {
            context.getSource().sendFailure(Component.literal("Unknown simulation settlement id"));
            return 0;
        }
        String json = evidenceJson(data, state, row, bridge);
        LOGGER.info("[BANNEROK_WORLD_DEVELOPMENT_EVIDENCE] {}", json);
        context.getSource().sendSuccess(
                () -> Component.literal("World-development evidence emitted for settlement "
                        + settlementId + " (server log marker BANNEROK_WORLD_DEVELOPMENT_EVIDENCE)"),
                false);
        return 1;
    }

    private static MillenaireWorldSimulationBridge bridge(
            CommandContext<CommandSourceStack> context,
            ArmyLifecycleService lifecycle) {
        MillenaireWorldSimulationBridge bridge = lifecycle.worldSimulationBridge();
        if (bridge == null) {
            context.getSource().sendFailure(Component.literal(
                    "Millenaire Simulation is disabled or the server lifecycle is not running"));
        }
        return bridge;
    }

    private static String formatEvent(long sequence, SimulationEvent event) {
        return "simulation-event #" + sequence
                + " type=" + event.type()
                + " settlement=" + event.settlementId()
                + " source=" + event.sourceSettlementId()
                + " culture=" + event.cultureKey()
                + " realm=" + event.realmId()
                + " region=" + event.regionKey()
                + " score=" + event.score()
                + " reasons=" + event.reasonMask()
                + " cycle=" + event.cycle();
    }

    private static String evidenceJson(
            SimulationSavedData data,
            PackedSettlementSimulationState state,
            int row,
            MillenaireWorldSimulationBridge bridge) {
        StringBuilder json = new StringBuilder(768);
        json.append('{')
                .append("\"schema\":\"millenaire.world-development.evidence.v1\"")
                .append(",\"settlement_id\":").append(state.settlementIdAt(row))
                .append(",\"uuid\":\"").append(data.keys().settlement(state.settlementIdAt(row))).append('\"')
                .append(",\"culture\":\"").append(data.keys().culture(state.cultureKeyAt(row))).append('\"')
                .append(",\"realm_id\":").append(state.realmIdAt(row))
                .append(",\"region\":").append(state.regionKeyAt(row))
                .append(",\"status\":\"").append(state.statusAt(row)).append('\"')
                .append(",\"tier\":\"").append(state.tierAt(row)).append('\"')
                .append(",\"physically_present\":").append(state.physicallyPresentAt(row))
                .append(",\"observed_population\":").append(state.observedPopulationAt(row))
                .append(",\"virtual_population\":").append(state.populationAt(row))
                .append(",\"housing_capacity\":").append(state.housingCapacityAt(row))
                .append(",\"buildings\":").append(state.buildingCountAt(row))
                .append(",\"productive_buildings\":").append(state.productiveBuildingsAt(row))
                .append(",\"productivity\":").append(state.productivityAt(row))
                .append(",\"stability\":").append(state.stabilityAt(row))
                .append(",\"attractiveness\":").append(state.attractivenessAt(row))
                .append(",\"productive_capital\":").append(state.productiveCapitalAt(row))
                .append(",\"market_access\":").append(state.marketAccessAt(row))
                .append(",\"security\":").append(state.securityAt(row))
                .append(",\"damage\":").append(state.damageAt(row))
                .append(",\"state_revision\":").append(state.revision())
                .append(",\"simulated_cycles\":").append(bridge.engine().simulatedCycleCount())
                .append(",\"active_shocks\":").append(data.shocks().size())
                .append(",\"events\":").append(data.events().size())
                .append(",\"endogenous_shocks\":").append(bridge.regionalDynamics().endogenousShockCount())
                .append(",\"propagated_shocks\":").append(bridge.regionalDynamics().propagatedShockCount())
                .append(",\"refugee_flows\":").append(bridge.regionalDynamics().refugeeFlowCount())
                .append(",\"relocated_population\":").append(bridge.regionalDynamics().relocatedPopulationCount())
                .append(",\"prices\":{");
        for (int commodity = 0; commodity < state.commodityCount(); commodity++) {
            if (commodity > 0) json.append(',');
            json.append('\"').append(commodityName(commodity)).append("\":")
                    .append(state.priceIndexAt(row, commodity));
        }
        return json.append("}}").toString();
    }

    private static String commodityName(int commodity) {
        return switch (commodity) {
            case MillenaireWorldSimulationBridge.FOOD -> "food";
            case MillenaireWorldSimulationBridge.TIMBER -> "timber";
            case MillenaireWorldSimulationBridge.STONE -> "stone";
            case MillenaireWorldSimulationBridge.IRON -> "iron";
            case MillenaireWorldSimulationBridge.TEXTILES -> "textiles";
            case MillenaireWorldSimulationBridge.TOOLS -> "tools";
            case MillenaireWorldSimulationBridge.ARMS -> "arms";
            case MillenaireWorldSimulationBridge.LUXURY -> "luxury";
            default -> "commodity_" + commodity;
        };
    }
}
