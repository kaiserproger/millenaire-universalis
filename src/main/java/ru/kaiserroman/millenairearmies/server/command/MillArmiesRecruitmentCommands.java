package ru.kaiserroman.millenairearmies.server.command;

import static com.mojang.brigadier.arguments.LongArgumentType.getLong;
import static com.mojang.brigadier.arguments.LongArgumentType.longArg;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireRecruitmentService;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandAuthority;

/** Separate Brigadier registrar for Millenaire membership operations. */
public final class MillArmiesRecruitmentCommands {
    private MillArmiesRecruitmentCommands() {}

    /**
     * Adds branches to the existing {@code /millarmies} root. Brigadier merges the root node with
     * the one registered by {@link MillArmiesCommands}, keeping this feature independently wired.
     */
    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            MillenaireRecruitmentService recruitmentService) {
        LiteralArgumentBuilder<CommandSourceStack> root = literal("millarmies");
        root.then(recruitBranch("recruit", recruitmentService));
        root.then(recruitBranch("assign", recruitmentService));
        dispatcher.register(root);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> recruitBranch(
            String literalName, MillenaireRecruitmentService service) {
        return literal(literalName)
                .then(argument("army", longArg(0L, 0xFFFF_FFFFL))
                        .then(argument("villager", UuidArgument.uuid())
                                .executes(context -> recruitLoaded(context, service))));
    }

    private static int recruitLoaded(
            CommandContext<CommandSourceStack> context, MillenaireRecruitmentService service) {
        CommandSourceStack source = context.getSource();
        long unsignedArmy = getLong(context, "army");
        UUID villager = UuidArgument.getUuid(context, "villager");
        long result = service.recruitLoaded(
                authority(source),
                (int) unsignedArmy,
                villager.getMostSignificantBits(),
                villager.getLeastSignificantBits());
        if (result < 0L) {
            source.sendFailure(Component.literal(failureMessage(result)));
            return 0;
        }
        source.sendSuccess(
                () -> Component.literal("Assigned Millenaire villager " + villager
                        + " to army " + unsignedArmy
                        + " as unit " + result),
                true);
        return 1;
    }

    private static ArmyCommandAuthority authority(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return new ArmyCommandAuthority(0L, 0L, false, source.hasPermission(2));
        }
        return ArmyCommandAuthority.player(player.getUUID(), source.hasPermission(2));
    }

    private static String failureMessage(long result) {
        return switch ((int) result) {
            case (int) MillenaireRecruitmentService.NOT_RUNNING ->
                    "Millenaire Armies recruitment service is not running";
            case (int) MillenaireRecruitmentService.PERMISSION_DENIED ->
                    "You do not control the source and target armies";
            case (int) MillenaireRecruitmentService.ARMY_NOT_FOUND -> "Unknown army handle";
            case (int) MillenaireRecruitmentService.VILLAGER_NOT_LOADED ->
                    "Millenaire villager is not currently loaded";
            case (int) MillenaireRecruitmentService.VILLAGE_NOT_FOUND ->
                    "Villager village is not indexed";
            case (int) MillenaireRecruitmentService.VILLAGER_NOT_IN_VILLAGE ->
                    "Villager has no record in that Millenaire village";
            case (int) MillenaireRecruitmentService.WRONG_FACTION ->
                    "Villager village does not belong to the army faction";
            case (int) MillenaireRecruitmentService.VILLAGER_UNAVAILABLE ->
                    "Killed or child villagers cannot be recruited";
            case (int) MillenaireRecruitmentService.UNIT_LIMIT_REACHED ->
                    "Packed unit handle limit reached";
            default -> "Recruitment failed (" + result + ')';
        };
    }
}
