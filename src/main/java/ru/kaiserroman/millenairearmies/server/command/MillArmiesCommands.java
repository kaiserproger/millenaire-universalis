package ru.kaiserroman.millenairearmies.server.command;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.LongArgumentType.getLong;
import static com.mojang.brigadier.arguments.LongArgumentType.longArg;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.network.ArmiesNetwork;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandAuthority;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandService;
import ru.kaiserroman.millenairearmies.server.service.StrategicArmyOrder;

/** Brigadier surface for the server-authoritative strategic command service. */
public final class MillArmiesCommands {
    private MillArmiesCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, ArmyCommandService service) {
        dispatcher.register(literal("millarmies")
                .executes(MillArmiesCommands::openScreen)
                .then(literal("command").executes(MillArmiesCommands::openScreen))
                .then(literal("status").executes(context -> status(context, service)))
                .then(literal("list").executes(context -> list(context, service)))
                .then(literal("create")
                        .requires(source -> source.hasPermission(2))
                        .then(argument("faction", integer(0, Integer.MAX_VALUE))
                                .executes(context -> createAtSource(context, service))
                                .then(argument("position", BlockPosArgument.blockPos())
                                        .executes(context -> createAtPosition(context, service)))))
                .then(literal("order")
                        .then(argument("army", longArg(0L, 0xFFFF_FFFFL))
                                .then(literal("hold")
                                        .executes(context -> orderWithoutPosition(
                                                context, service, StrategicArmyOrder.HOLD)))
                                .then(literal("move")
                                        .then(argument("position", BlockPosArgument.blockPos())
                                                .executes(context -> orderAtPosition(
                                                        context, service, StrategicArmyOrder.MOVE))))
                                .then(literal("rally")
                                        .then(argument("position", BlockPosArgument.blockPos())
                                                .executes(context -> orderAtPosition(
                                                        context, service, StrategicArmyOrder.RALLY))))
                                .then(literal("logistics")
                                        .then(argument("position", BlockPosArgument.blockPos())
                                                .executes(context -> orderAtPosition(
                                                        context, service, StrategicArmyOrder.LOGISTICS))))
                                .then(literal("attack")
                                        .then(argument("position", BlockPosArgument.blockPos())
                                                .executes(context -> orderAtPosition(
                                                        context, service, StrategicArmyOrder.ATTACK)))))));
    }

    private static int openScreen(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("This command requires a player"));
            return 0;
        }
        ArmiesNetwork.openScreen(player);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context, ArmyCommandService service) {
        CommandSourceStack source = context.getSource();
        ArmyCommandAuthority authority = authority(source);
        if (!service.isRunning()) {
            source.sendFailure(Component.literal("Millenaire Armies service is not running"));
            return 0;
        }

        int visible = service.visitVisibleArmies(authority, (handle, faction, order, state, units, target) -> {});
        String total = authority.operator() ? "/" + service.armyCount() : "";
        source.sendSuccess(
                () -> Component.literal("Millenaire Armies: running, armies=" + visible + total
                        + ", units=" + service.unitCount()),
                false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context, ArmyCommandService service) {
        CommandSourceStack source = context.getSource();
        ArmyCommandAuthority authority = authority(source);
        int visible = service.visitVisibleArmies(authority, (handle, faction, order, state, units, target) ->
                source.sendSuccess(
                        () -> Component.literal(formatArmy(handle, faction, order, state, units, target)),
                        false));
        if (visible == ArmyCommandService.NOT_RUNNING) {
            source.sendFailure(Component.literal("Millenaire Armies service is not running"));
            return 0;
        }
        if (visible == 0) {
            source.sendSuccess(() -> Component.literal("No controllable armies"), false);
        } else {
            int count = visible;
            source.sendSuccess(() -> Component.literal("Visible armies: " + count), false);
        }
        return visible;
    }

    private static int createAtSource(
            CommandContext<CommandSourceStack> context, ArmyCommandService service) {
        BlockPos position = BlockPos.containing(context.getSource().getPosition());
        return create(context, service, position.asLong());
    }

    private static int createAtPosition(
            CommandContext<CommandSourceStack> context, ArmyCommandService service) {
        return create(context, service, BlockPosArgument.getBlockPos(context, "position").asLong());
    }

    private static int create(
            CommandContext<CommandSourceStack> context, ArmyCommandService service, long packedPosition) {
        CommandSourceStack source = context.getSource();
        int faction = getInteger(context, "faction");
        long handle = service.createArmy(
                authority(source),
                faction,
                source.getLevel().dimension().location(),
                packedPosition);
        if (handle < 0) {
            return failure(source, handle);
        }
        source.sendSuccess(
                () -> Component.literal("Created army " + handle + " for faction " + faction),
                true);
        return 1;
    }

    private static int orderWithoutPosition(
            CommandContext<CommandSourceStack> context,
            ArmyCommandService service,
            StrategicArmyOrder order) {
        return order(context, service, order, 0L);
    }

    private static int orderAtPosition(
            CommandContext<CommandSourceStack> context,
            ArmyCommandService service,
            StrategicArmyOrder order) {
        long position = BlockPosArgument.getBlockPos(context, "position").asLong();
        return order(context, service, order, position);
    }

    private static int order(
            CommandContext<CommandSourceStack> context,
            ArmyCommandService service,
            StrategicArmyOrder order,
            long packedPosition) {
        CommandSourceStack source = context.getSource();
        long unsignedArmy = getLong(context, "army");
        int army = (int) unsignedArmy;
        long result = service.issueOrder(
                authority(source),
                army,
                order,
                source.getLevel().dimension().location(),
                packedPosition);
        if (result != ArmyCommandService.SUCCESS) {
            return failure(source, result);
        }
        source.sendSuccess(
                () -> Component.literal(
                        "Army " + unsignedArmy + " order: " + StrategicArmyOrder.displayName(order.code())),
                true);
        return 1;
    }

    private static int failure(CommandSourceStack source, long result) {
        int error = (int) result;
        String message = switch (error) {
            case (int) ArmyCommandService.NOT_RUNNING -> "Millenaire Armies service is not running";
            case (int) ArmyCommandService.PERMISSION_DENIED -> "You are neither this army's controller nor an operator";
            case (int) ArmyCommandService.ARMY_NOT_FOUND -> "Unknown army handle";
            case (int) ArmyCommandService.LIMIT_REACHED -> "Configured army limit reached";
            case (int) ArmyCommandService.INVALID_FACTION -> "Invalid faction";
            case (int) ArmyCommandService.INVALID_ORDER -> "Invalid strategic order";
            default -> "Army command failed (" + result + ')';
        };
        source.sendFailure(Component.literal(message));
        return 0;
    }

    private static ArmyCommandAuthority authority(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return new ArmyCommandAuthority(0L, 0L, false, source.hasPermission(2));
        }
        return ArmyCommandAuthority.player(player.getUUID(), source.hasPermission(2));
    }

    private static String formatArmy(int handle, int faction, int order, int state, int units, long target) {
        return "#" + Integer.toUnsignedLong(handle)
                + " faction=" + faction
                + " order=" + StrategicArmyOrder.displayName(order)
                + " state=" + state
                + " units=" + units
                + " target=" + PackedArmyEcs.unpackBlockX(target)
                + ',' + PackedArmyEcs.unpackBlockY(target)
                + ',' + PackedArmyEcs.unpackBlockZ(target);
    }
}
