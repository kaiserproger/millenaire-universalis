package ru.kaiserroman.millenairearmies.server.command;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.mojang.brigadier.arguments.StringArgumentType.greedyString;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import ru.kaiserroman.millenairearmies.server.realm.RealmService;

/** Non-operator gameplay commands mirroring the realm screen for deterministic QA and accessibility. */
public final class MillArmiesRealmCommands {
    private MillArmiesRealmCommands() {}

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            Supplier<RealmService> realmService) {
        Objects.requireNonNull(realmService, "realmService");
        dispatcher.register(literal("millarmies")
                .then(literal("realm")
                        .requires(source -> source.getPlayer() != null)
                        .executes(context -> status(context, realmService.get()))
                        .then(literal("status").executes(context -> status(context, realmService.get())))
                        .then(literal("found")
                                .executes(context -> found(context, realmService.get(), ""))
                                .then(argument("name", greedyString())
                                        .executes(context -> found(
                                                context, realmService.get(), getString(context, "name")))))
                        .then(literal("rename")
                                .then(argument("name", greedyString())
                                        .executes(context -> rename(context, realmService.get()))))
                        .then(literal("tax")
                                .then(argument("percent", integer(0, 25))
                                        .executes(context -> tax(context, realmService.get()))))));
    }

    private static int status(CommandContext<CommandSourceStack> context, RealmService realms) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null || realms == null) return 0;
        RealmService.Snapshot state = realms.snapshot(player.getUUID());
        if (!state.founded()) {
            context.getSource().sendSuccess(
                    () -> Component.literal("No player realm founded. Use /millarmies realm found [name] near a controlled settlement."),
                    false);
            return 1;
        }
        context.getSource().sendSuccess(
                () -> Component.literal(state.name()
                        + ": capital=" + state.capitalName()
                        + ", settlements=" + state.settlementCount()
                        + ", population=" + state.population()
                        + ", tax=" + state.taxRate() + "%"
                        + ", treasury=" + state.treasury()
                        + ", captures=" + state.capturedSettlements()
                        + ", resources={food=" + state.food()
                        + ", iron=" + state.iron()
                        + ", leather=" + state.leather()
                        + ", arrows=" + state.arrows() + '}'),
                false);
        return 1;
    }

    private static int found(
            CommandContext<CommandSourceStack> context, RealmService realms, String name) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null || realms == null) return 0;
        int result = realms.foundNearest(player, name);
        if (result != RealmService.SUCCESS) return failure(context.getSource(), result);
        context.getSource().sendSuccess(
                () -> Component.literal("Player realm founded from the nearest controlled settlement"),
                true);
        return 1;
    }

    private static int rename(CommandContext<CommandSourceStack> context, RealmService realms) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null || realms == null) return 0;
        int result = realms.rename(player, getString(context, "name"));
        if (result != RealmService.SUCCESS) return failure(context.getSource(), result);
        context.getSource().sendSuccess(() -> Component.literal("Realm renamed"), true);
        return 1;
    }

    private static int tax(CommandContext<CommandSourceStack> context, RealmService realms) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null || realms == null) return 0;
        int rate = getInteger(context, "percent");
        int result = realms.setTaxRate(player, rate);
        if (result != RealmService.SUCCESS) return failure(context.getSource(), result);
        context.getSource().sendSuccess(() -> Component.literal("Realm daily tax set to " + rate + '%'), true);
        return 1;
    }

    private static int failure(CommandSourceStack source, int result) {
        String message = switch (result) {
            case RealmService.NOT_FOUNDED -> "Found a realm first";
            case RealmService.ALREADY_FOUNDED -> "You already rule a realm";
            case RealmService.SETTLEMENT_NOT_FOUND -> "No nearby controlled Millenaire settlement";
            case RealmService.SETTLEMENT_NOT_CONTROLLED -> "The selected settlement is not controlled by you";
            case RealmService.TOO_FAR -> "Move closer to the controlled settlement";
            case RealmService.INVALID_TAX -> "Tax rate must be between 0 and 25 percent";
            case RealmService.INVALID_NAME -> "Realm name is invalid or too long";
            default -> "Realm action failed (" + result + ')';
        };
        source.sendFailure(Component.literal(message));
        return 0;
    }
}
