package ru.kaiserroman.millenairearmies.server.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import ru.kaiserroman.millenairearmies.lifecycle.ArmyLifecycleService;
import ru.kaiserroman.millenairearmies.persistence.PlayerSettlementCustomizationSavedData;
import ru.kaiserroman.millenairearmies.server.settlement.PlayerSettlementProfile;
import ru.kaiserroman.millenairearmies.server.settlement.PlayerSettlementService;

/** Non-operator command surface for founding, developing and customizing a player settlement. */
public final class MillArmiesSettlementCommands {
    private MillArmiesSettlementCommands() {}

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            ArmyLifecycleService lifecycle) {
        dispatcher.register(Commands.literal("millarmies")
                .then(Commands.literal("settlement")
                        .then(Commands.literal("status")
                                .executes(context -> status(context.getSource(), lifecycle)))
                        .then(Commands.literal("types")
                                .executes(context -> types(context.getSource(), lifecycle, 64))
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 128))
                                        .executes(context -> types(
                                                context.getSource(),
                                                lifecycle,
                                                IntegerArgumentType.getInteger(context, "limit")))))
                        .then(createNode("create", lifecycle))
                        .then(createNode("found", lifecycle))
                        .then(Commands.literal("adopt")
                                .then(Commands.argument("capital", UuidArgument.uuid())
                                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                                .executes(context -> adopt(
                                                        context.getSource(),
                                                        lifecycle,
                                                        UuidArgument.getUuid(context, "capital"),
                                                        StringArgumentType.getString(context, "name"))))))
                        .then(Commands.literal("catalog")
                                .executes(context -> catalog(context.getSource(), lifecycle, 40))
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 512))
                                        .executes(context -> catalog(
                                                context.getSource(),
                                                lifecycle,
                                                IntegerArgumentType.getInteger(context, "limit")))))
                        .then(Commands.literal("catalog-in")
                                .then(Commands.argument("settlement", UuidArgument.uuid())
                                        .executes(context -> catalogIn(
                                                context.getSource(),
                                                lifecycle,
                                                UuidArgument.getUuid(context, "settlement"),
                                                40))
                                        .then(Commands.argument("limit", IntegerArgumentType.integer(1, 512))
                                                .executes(context -> catalogIn(
                                                        context.getSource(),
                                                        lifecycle,
                                                        UuidArgument.getUuid(context, "settlement"),
                                                        IntegerArgumentType.getInteger(context, "limit"))))))
                        .then(Commands.literal("projects")
                                .executes(context -> catalog(context.getSource(), lifecycle, 40))
                                .then(Commands.argument("limit", IntegerArgumentType.integer(1, 512))
                                        .executes(context -> catalog(
                                                context.getSource(),
                                                lifecycle,
                                                IntegerArgumentType.getInteger(context, "limit")))))
                        .then(Commands.literal("queue")
                                .then(Commands.argument("plan", ResourceLocationArgument.id())
                                        .executes(context -> queue(
                                                context.getSource(),
                                                lifecycle,
                                                ResourceLocationArgument.getId(context, "plan"),
                                                null))
                                        .then(Commands.argument("variant", StringArgumentType.word())
                                                .executes(context -> queue(
                                                        context.getSource(),
                                                        lifecycle,
                                                        ResourceLocationArgument.getId(context, "plan"),
                                                        StringArgumentType.getString(context, "variant"))))))
                        .then(Commands.literal("queue-in")
                                .then(Commands.argument("settlement", UuidArgument.uuid())
                                        .then(Commands.argument("plan", ResourceLocationArgument.id())
                                                .executes(context -> queueIn(
                                                        context.getSource(),
                                                        lifecycle,
                                                        UuidArgument.getUuid(context, "settlement"),
                                                        ResourceLocationArgument.getId(context, "plan"),
                                                        null))
                                                .then(Commands.argument("variant", StringArgumentType.word())
                                                        .executes(context -> queueIn(
                                                                context.getSource(),
                                                                lifecycle,
                                                                UuidArgument.getUuid(context, "settlement"),
                                                                ResourceLocationArgument.getId(context, "plan"),
                                                                StringArgumentType.getString(context, "variant")))))))
                        .then(Commands.literal("build")
                                .then(Commands.argument("plan", ResourceLocationArgument.id())
                                        .then(Commands.argument("position", BlockPosArgument.blockPos())
                                                .executes(context -> build(
                                                        context.getSource(),
                                                        lifecycle,
                                                        ResourceLocationArgument.getId(context, "plan"),
                                                        BlockPosArgument.getLoadedBlockPos(context, "position"),
                                                        0,
                                                        null))
                                                .then(Commands.argument("rotation", IntegerArgumentType.integer(0, 3))
                                                        .executes(context -> build(
                                                                context.getSource(),
                                                                lifecycle,
                                                                ResourceLocationArgument.getId(context, "plan"),
                                                                BlockPosArgument.getLoadedBlockPos(context, "position"),
                                                                IntegerArgumentType.getInteger(context, "rotation"),
                                                                null))
                                                        .then(Commands.argument("variant", StringArgumentType.word())
                                                                .executes(context -> build(
                                                                        context.getSource(),
                                                                        lifecycle,
                                                                        ResourceLocationArgument.getId(context, "plan"),
                                                                        BlockPosArgument.getLoadedBlockPos(context, "position"),
                                                                        IntegerArgumentType.getInteger(context, "rotation"),
                                                                        StringArgumentType.getString(context, "variant"))))))))
                        .then(Commands.literal("build-in")
                                .then(Commands.argument("settlement", UuidArgument.uuid())
                                        .then(Commands.argument("plan", ResourceLocationArgument.id())
                                                .then(Commands.argument("position", BlockPosArgument.blockPos())
                                                        .executes(context -> buildIn(
                                                                context.getSource(),
                                                                lifecycle,
                                                                UuidArgument.getUuid(context, "settlement"),
                                                                ResourceLocationArgument.getId(context, "plan"),
                                                                BlockPosArgument.getLoadedBlockPos(context, "position"),
                                                                0,
                                                                null))
                                                        .then(Commands.argument("rotation", IntegerArgumentType.integer(0, 3))
                                                                .executes(context -> buildIn(
                                                                        context.getSource(),
                                                                        lifecycle,
                                                                        UuidArgument.getUuid(context, "settlement"),
                                                                        ResourceLocationArgument.getId(context, "plan"),
                                                                        BlockPosArgument.getLoadedBlockPos(context, "position"),
                                                                        IntegerArgumentType.getInteger(context, "rotation"),
                                                                        null))
                                                                .then(Commands.argument("variant", StringArgumentType.word())
                                                                        .executes(context -> buildIn(
                                                                                context.getSource(),
                                                                                lifecycle,
                                                                                UuidArgument.getUuid(context, "settlement"),
                                                                                ResourceLocationArgument.getId(context, "plan"),
                                                                                BlockPosArgument.getLoadedBlockPos(context, "position"),
                                                                                IntegerArgumentType.getInteger(context, "rotation"),
                                                                                StringArgumentType.getString(context, "variant")))))))))
                        .then(Commands.literal("rename")
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(context -> rename(
                                                context.getSource(),
                                                lifecycle,
                                                StringArgumentType.getString(context, "name")))))
                        .then(Commands.literal("profile")
                                .then(Commands.argument("profile", StringArgumentType.word())
                                        .executes(context -> developmentProfile(
                                                context.getSource(),
                                                lifecycle,
                                                StringArgumentType.getString(context, "profile")))))
                        .then(Commands.literal("auto")
                                .then(Commands.argument("enabled", BoolArgumentType.bool())
                                        .executes(context -> automatic(
                                                context.getSource(),
                                                lifecycle,
                                                BoolArgumentType.getBool(context, "enabled")))))
                        .then(Commands.literal("queue-limit")
                                .then(Commands.argument(
                                                "limit",
                                                IntegerArgumentType.integer(
                                                        PlayerSettlementCustomizationSavedData.MIN_QUEUE_LIMIT,
                                                        PlayerSettlementCustomizationSavedData.MAX_QUEUE_LIMIT))
                                        .executes(context -> queueLimit(
                                                context.getSource(),
                                                lifecycle,
                                                IntegerArgumentType.getInteger(context, "limit")))))
                        .then(Commands.literal("clear")
                                .executes(context -> clear(context.getSource(), lifecycle)))
                        .then(Commands.literal("capture")
                                .then(Commands.argument("target", UuidArgument.uuid())
                                        .executes(context -> capture(
                                                context.getSource(),
                                                lifecycle,
                                                UuidArgument.getUuid(context, "target")))))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> createNode(
            String name,
            ArmyLifecycleService lifecycle) {
        return Commands.literal(name)
                .then(Commands.argument("village_type", ResourceLocationArgument.id())
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(context -> create(
                                        context.getSource(),
                                        lifecycle,
                                        ResourceLocationArgument.getId(context, "village_type"),
                                        StringArgumentType.getString(context, "name")))));
    }

    private static int status(CommandSourceStack source, ArmyLifecycleService lifecycle) {
        ServerPlayer player = player(source);
        PlayerSettlementService service = lifecycle.playerSettlementService();
        PlayerSettlementService.Status status =
                player == null || service == null ? null : service.status(player.getUUID());
        if (status == null) {
            source.sendFailure(Component.literal(
                    "No player settlement. Use /millarmies settlement types and found."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Settlement " + status.name()
                        + " type=" + status.villageType()
                        + " tier=" + status.tier()
                        + " capital=" + status.capital()
                        + " realm=" + status.realmId()
                        + " radius=" + status.territoryRadius()
                        + " development=" + status.development() + "/1000"
                        + " buildings=" + status.buildingCount()
                        + " population=" + status.population()
                        + " captured=" + status.capturedSettlements()
                        + " profile=" + status.profile().name().toLowerCase(Locale.ROOT)
                        + " auto=" + status.automatic()
                        + " queue=" + status.queuedProjects() + '/' + status.queueLimit()
                        + " pending=" + status.pendingProject()
                        + " revision=" + status.customizationRevision()), false);
        return 1;
    }

    private static int types(CommandSourceStack source, ArmyLifecycleService lifecycle, int limit) {
        if (player(source) == null) return 0;
        PlayerSettlementService service = lifecycle.playerSettlementService();
        if (service == null) return unavailable(source);
        List<ResourceLocation> types = service.villageTypes(limit);
        if (types.isEmpty()) {
            source.sendFailure(Component.literal("No player-controlled Millenaire village types are loaded"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Player settlement types (" + types.size() + "): "
                        + types.stream().map(ResourceLocation::toString)
                                .collect(java.util.stream.Collectors.joining(", "))), false);
        return types.size();
    }

    private static int members(CommandSourceStack source, ArmyLifecycleService lifecycle, int limit) {
        ServerPlayer player = player(source);
        PlayerSettlementService service = lifecycle.playerSettlementService();
        if (player == null || service == null) return 0;
        List<UUID> members = service.managedSettlements(player.getUUID(), limit);
        if (members.isEmpty()) {
            source.sendFailure(Component.literal("No physically controlled settlements are available"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Controlled settlements (" + members.size() + "): "
                        + members.stream().map(UUID::toString)
                                .collect(java.util.stream.Collectors.joining(", "))), false);
        return members.size();
    }

    private static int create(
            CommandSourceStack source,
            ArmyLifecycleService lifecycle,
            ResourceLocation villageType,
            String name) {
        ServerPlayer player = player(source);
        PlayerSettlementService service = lifecycle.playerSettlementService();
        return player == null || service == null
                ? 0
                : reply(source, service.createSettlement(player, villageType, name));
    }

    private static int adopt(
            CommandSourceStack source,
            ArmyLifecycleService lifecycle,
            UUID capital,
            String name) {
        ServerPlayer player = player(source);
        PlayerSettlementService service = lifecycle.playerSettlementService();
        return player == null || service == null
                ? 0
                : reply(source, service.adoptExisting(player, capital, name));
    }

    private static int catalog(CommandSourceStack source, ArmyLifecycleService lifecycle, int limit) {
        ServerPlayer player = player(source);
        PlayerSettlementService service = lifecycle.playerSettlementService();
        if (player == null || service == null) return 0;
        List<ResourceLocation> catalog = service.catalog(player.getUUID(), limit);
        if (catalog.isEmpty()) {
            source.sendFailure(Component.literal("No building catalog is available for this settlement"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Available building plans (" + catalog.size() + "): "
                        + catalog.stream().map(ResourceLocation::toString)
                                .collect(java.util.stream.Collectors.joining(", "))), false);
        return catalog.size();
    }

    private static int catalogIn(
            CommandSourceStack source,
            ArmyLifecycleService lifecycle,
            UUID settlement,
            int limit) {
        ServerPlayer player = player(source);
        PlayerSettlementService service = lifecycle.playerSettlementService();
        if (player == null || service == null) return 0;
        List<ResourceLocation> catalog = service.catalogIn(player.getUUID(), settlement, limit);
        if (catalog.isEmpty()) {
            source.sendFailure(Component.literal(
                    "No building catalog is available for controlled settlement " + settlement));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Available plans for " + settlement + " (" + catalog.size() + "): "
                        + catalog.stream().map(ResourceLocation::toString)
                                .collect(java.util.stream.Collectors.joining(", "))), false);
        return catalog.size();
    }

    private static int queue(
            CommandSourceStack source,
            ArmyLifecycleService lifecycle,
            ResourceLocation plan,
            String variant) {
        ServerPlayer player = player(source);
        PlayerSettlementService service = lifecycle.playerSettlementService();
        return player == null || service == null
                ? 0
                : reply(source, service.queueNextBuilding(player, plan, variant));
    }

    private static int queueIn(
            CommandSourceStack source,
            ArmyLifecycleService lifecycle,
            UUID settlement,
            ResourceLocation plan,
            String variant) {
        ServerPlayer player = player(source);
        PlayerSettlementService service = lifecycle.playerSettlementService();
        return player == null || service == null
                ? 0
                : reply(source, service.queueNextBuildingIn(player, settlement, plan, variant));
    }

    private static int build(
            CommandSourceStack source,
            ArmyLifecycleService lifecycle,
            ResourceLocation plan,
            net.minecraft.core.BlockPos position,
            int rotation,
            String variant) {
        ServerPlayer player = player(source);
        PlayerSettlementService service = lifecycle.playerSettlementService();
        return player == null || service == null
                ? 0
                : reply(source, service.queueBuilding(player, plan, position, rotation, variant));
    }

    private static int buildIn(
            CommandSourceStack source,
            ArmyLifecycleService lifecycle,
            UUID settlement,
            ResourceLocation plan,
            net.minecraft.core.BlockPos position,
            int rotation,
            String variant) {
        ServerPlayer player = player(source);
        PlayerSettlementService service = lifecycle.playerSettlementService();
        return player == null || service == null
                ? 0
                : reply(source, service.queueBuildingIn(
                        player, settlement, plan, position, rotation, variant));
    }

    private static int rename(
            CommandSourceStack source,
            ArmyLifecycleService lifecycle,
            String name) {
        ServerPlayer player = player(source);
        PlayerSettlementService service = lifecycle.playerSettlementService();
        return player == null || service == null ? 0 : reply(source, service.rename(player, name));
    }

    private static int developmentProfile(
            CommandSourceStack source,
            ArmyLifecycleService lifecycle,
            String value) {
        ServerPlayer player = player(source);
        PlayerSettlementService service = lifecycle.playerSettlementService();
        if (player == null || service == null) return 0;
        PlayerSettlementProfile profile;
        try {
            profile = PlayerSettlementProfile.parse(value);
        } catch (IllegalArgumentException invalid) {
            source.sendFailure(Component.literal(
                    "Unknown profile; use balanced, food, trade, industry, military, or civic"));
            return 0;
        }
        return reply(source, service.setProfile(player, profile));
    }

    private static int automatic(
            CommandSourceStack source,
            ArmyLifecycleService lifecycle,
            boolean enabled) {
        ServerPlayer player = player(source);
        PlayerSettlementService service = lifecycle.playerSettlementService();
        return player == null || service == null
                ? 0
                : reply(source, service.setAutomatic(player, enabled));
    }

    private static int queueLimit(
            CommandSourceStack source,
            ArmyLifecycleService lifecycle,
            int limit) {
        ServerPlayer player = player(source);
        PlayerSettlementService service = lifecycle.playerSettlementService();
        return player == null || service == null
                ? 0
                : reply(source, service.setQueueLimit(player, limit));
    }

    private static int clear(CommandSourceStack source, ArmyLifecycleService lifecycle) {
        ServerPlayer player = player(source);
        PlayerSettlementService service = lifecycle.playerSettlementService();
        return player == null || service == null ? 0 : reply(source, service.clearQueue(player));
    }

    private static int capture(
            CommandSourceStack source,
            ArmyLifecycleService lifecycle,
            UUID target) {
        ServerPlayer player = player(source);
        PlayerSettlementService service = lifecycle.playerSettlementService();
        return player == null || service == null ? 0 : reply(source, service.capture(player, target));
    }

    private static int reply(
            CommandSourceStack source,
            PlayerSettlementService.OperationResult result) {
        if (result.success()) {
            source.sendSuccess(() -> Component.literal(result.message()), true);
            return 1;
        }
        source.sendFailure(Component.literal("[" + result.code() + "] " + result.message()));
        return 0;
    }

    private static int unavailable(CommandSourceStack source) {
        source.sendFailure(Component.literal("Player settlement service is not running"));
        return 0;
    }

    private static ServerPlayer player(CommandSourceStack source) {
        try {
            return source.getPlayerOrException();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            source.sendFailure(Component.literal("This command requires a player"));
            return null;
        }
    }
}
