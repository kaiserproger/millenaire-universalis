package ru.kaiserroman.millenairearmies.server.command;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.LongArgumentType.getLong;
import static com.mojang.brigadier.arguments.LongArgumentType.longArg;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.millenaire.entity.MillVillager;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireRecruitmentService;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandAuthority;

/** Player-facing Brigadier flow for settlement recruitment and safe release/disband. */
public final class MillArmiesRecruitmentCommands {
    private MillArmiesRecruitmentCommands() {}

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            MillenaireRecruitmentService recruitmentService) {
        LiteralArgumentBuilder<CommandSourceStack> root = literal("millarmies");
        root.then(literal("recruits").executes(context -> listEligible(context, recruitmentService)));
        root.then(literal("raise")
                .executes(context -> form(context, recruitmentService, 1))
                .then(argument("count", integer(1, ArmiesConfig.MAX_UNITS_PER_ARMY))
                        .executes(context -> form(context, recruitmentService, getInteger(context, "count")))));
        root.then(literal("recruit")
                .then(argument("army", longArg(0L, 0xFFFF_FFFFL))
                        .executes(context -> recruitNearest(context, recruitmentService, 1))
                        .then(argument("count", integer(1, ArmiesConfig.MAX_UNITS_PER_ARMY))
                                .executes(context -> recruitNearest(
                                        context, recruitmentService, getInteger(context, "count"))))
                        .then(literal("target")
                                .executes(context -> recruitLookTarget(context, recruitmentService))
                                .then(argument("villager", EntityArgument.entity())
                                        .executes(context -> recruitTarget(context, recruitmentService))))));
        root.then(literal("release")
                .then(argument("army", longArg(0L, 0xFFFF_FFFFL))
                        .then(literal("target")
                                .executes(context -> releaseLookTarget(context, recruitmentService))
                                .then(argument("villager", EntityArgument.entity())
                                        .executes(context -> releaseTarget(context, recruitmentService))))));
        root.then(literal("disband")
                .then(argument("army", longArg(0L, 0xFFFF_FFFFL))
                        .executes(context -> disband(context, recruitmentService))));
        dispatcher.register(root);
    }

    private static int listEligible(
            CommandContext<CommandSourceStack> context, MillenaireRecruitmentService service) {
        CommandSourceStack source = context.getSource();
        long count = service.visitEligible(
                authority(source),
                source.getLevel(),
                BlockPos.containing(source.getPosition()),
                (villager, villageName, villageMost, villageLeast, distance) -> source.sendSuccess(
                        () -> Component.literal("- " + villager.getVillagerDisplayName()
                                + " | " + villager.getNativeRoleName()
                                + " | strength=" + villager.getAttackStrength()
                                + " | pos=" + villager.blockPosition().toShortString()),
                        false));
        if (count < 0L) {
            return failure(source, count);
        }
        int available = (int) count;
        source.sendSuccess(
                () -> Component.literal("Available loaded fighters: " + available
                        + ". Raise: /millarmies raise <count>"),
                false);
        return Math.max(1, available);
    }

    private static int form(
            CommandContext<CommandSourceStack> context,
            MillenaireRecruitmentService service,
            int count) {
        CommandSourceStack source = context.getSource();
        long result = service.formArmy(
                authority(source), source.getLevel(), BlockPos.containing(source.getPosition()), count);
        if (result < 0L) {
            return failure(source, result);
        }
        source.sendSuccess(
                () -> Component.literal("Raised army " + result + " with " + count
                        + " fighter(s); charged "
                        + (ArmiesConfig.ARMY_FORMATION_EMERALD_COST
                                + count * ArmiesConfig.UNIT_RECRUITMENT_EMERALD_COST)
                        + " emerald(s) from the town hall"),
                true);
        return 1;
    }

    private static int recruitNearest(
            CommandContext<CommandSourceStack> context,
            MillenaireRecruitmentService service,
            int count) {
        CommandSourceStack source = context.getSource();
        long army = getLong(context, "army");
        long result = service.recruitNearest(
                authority(source),
                (int) army,
                source.getLevel(),
                BlockPos.containing(source.getPosition()),
                count);
        if (result < 0L) {
            return failure(source, result);
        }
        source.sendSuccess(
                () -> Component.literal("Recruited " + result + " nearest fighter(s) into army "
                        + army + "; charged "
                        + result * ArmiesConfig.UNIT_RECRUITMENT_EMERALD_COST + " emerald(s)"),
                true);
        return 1;
    }

    private static int recruitTarget(
            CommandContext<CommandSourceStack> context, MillenaireRecruitmentService service)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Entity selected = EntityArgument.getEntity(context, "villager");
        if (!(selected instanceof MillVillager villager)) {
            source.sendFailure(Component.literal("Selected entity is not a Millenaire villager"));
            return 0;
        }
        return recruitTarget(context, service, villager);
    }

    private static int recruitLookTarget(
            CommandContext<CommandSourceStack> context, MillenaireRecruitmentService service) {
        MillVillager villager = lookedAtVillager(context.getSource());
        if (villager == null) {
            context.getSource().sendFailure(Component.literal(
                    "Look directly at a loaded Millenaire fighter within 16 blocks"));
            return 0;
        }
        return recruitTarget(context, service, villager);
    }

    private static int recruitTarget(
            CommandContext<CommandSourceStack> context,
            MillenaireRecruitmentService service,
            MillVillager villager) {
        CommandSourceStack source = context.getSource();
        long army = getLong(context, "army");
        long result = service.recruitTarget(
                authority(source),
                (int) army,
                source.getLevel(),
                BlockPos.containing(source.getPosition()),
                villager);
        if (result < 0L) {
            return failure(source, result);
        }
        source.sendSuccess(
                () -> Component.literal("Recruited " + villager.getVillagerDisplayName()
                        + " into army " + army + " as unit " + result),
                true);
        return 1;
    }

    private static int releaseTarget(
            CommandContext<CommandSourceStack> context, MillenaireRecruitmentService service)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        Entity selected = EntityArgument.getEntity(context, "villager");
        if (!(selected instanceof MillVillager villager)) {
            source.sendFailure(Component.literal("Selected entity is not a Millenaire villager"));
            return 0;
        }
        return releaseTarget(context, service, villager);
    }

    private static int releaseLookTarget(
            CommandContext<CommandSourceStack> context, MillenaireRecruitmentService service) {
        MillVillager villager = lookedAtVillager(context.getSource());
        if (villager == null) {
            context.getSource().sendFailure(Component.literal(
                    "Look directly at a loaded Millenaire fighter within 16 blocks"));
            return 0;
        }
        return releaseTarget(context, service, villager);
    }

    private static int releaseTarget(
            CommandContext<CommandSourceStack> context,
            MillenaireRecruitmentService service,
            MillVillager villager) {
        CommandSourceStack source = context.getSource();
        long army = getLong(context, "army");
        long result = service.release(
                authority(source),
                (int) army,
                villager.getUUID().getMostSignificantBits(),
                villager.getUUID().getLeastSignificantBits());
        if (result < 0L) {
            return failure(source, result);
        }
        source.sendSuccess(
                () -> Component.literal("Released " + villager.getVillagerDisplayName()
                        + " from army " + army + "; normal Millenaire goals resume"),
                true);
        return 1;
    }

    private static MillVillager lookedAtVillager(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            return null;
        }
        HitResult hit = ProjectileUtil.getHitResultOnViewVector(
                player, entity -> entity instanceof MillVillager && entity.isAlive(), 16.0D);
        return hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof MillVillager villager
                ? villager
                : null;
    }

    private static int disband(
            CommandContext<CommandSourceStack> context, MillenaireRecruitmentService service) {
        CommandSourceStack source = context.getSource();
        long army = getLong(context, "army");
        long result = service.disband(authority(source), (int) army);
        if (result < 0L) {
            return failure(source, result);
        }
        long released = result - 1L;
        source.sendSuccess(
                () -> Component.literal("Disbanded army " + army + "; released " + released
                        + " fighter(s) to normal Millenaire life"),
                true);
        return 1;
    }

    private static int failure(CommandSourceStack source, long result) {
        source.sendFailure(Component.literal(failureMessage(result)));
        return 0;
    }

    public static String failureMessage(long result) {
        return switch ((int) result) {
            case (int) MillenaireRecruitmentService.NOT_RUNNING ->
                    "Millenaire Armies recruitment service is not running";
            case (int) MillenaireRecruitmentService.PERMISSION_DENIED ->
                    "You do not control this army";
            case (int) MillenaireRecruitmentService.ARMY_NOT_FOUND -> "Unknown army handle";
            case (int) MillenaireRecruitmentService.VILLAGER_NOT_LOADED ->
                    "That villager is dead, unloaded, or in another dimension";
            case (int) MillenaireRecruitmentService.VILLAGE_NOT_FOUND ->
                    "Stand within " + ArmiesConfig.RECRUITMENT_VILLAGE_RADIUS + " blocks of a Millenaire settlement";
            case (int) MillenaireRecruitmentService.VILLAGER_NOT_IN_VILLAGE ->
                    "Villager has no valid record in this settlement";
            case (int) MillenaireRecruitmentService.WRONG_FACTION ->
                    "This army and settlement belong to different projected factions";
            case (int) MillenaireRecruitmentService.VILLAGER_UNAVAILABLE ->
                    "Not enough loaded, living adult fighters are available";
            case (int) MillenaireRecruitmentService.UNIT_LIMIT_REACHED ->
                    "Packed unit handle limit reached";
            case (int) MillenaireRecruitmentService.SETTLEMENT_NOT_CONTROLLED ->
                    "The nearby settlement is not player-controlled by you";
            case (int) MillenaireRecruitmentService.REPUTATION_TOO_LOW ->
                    "Your Millenaire reputation is below the hiring threshold";
            case (int) MillenaireRecruitmentService.ALREADY_RECRUITED ->
                    "That villager is already recruited";
            case (int) MillenaireRecruitmentService.ARMY_FULL ->
                    "Army capacity reached (" + ArmiesConfig.MAX_UNITS_PER_ARMY + ')';
            case (int) MillenaireRecruitmentService.VILLAGER_BUSY ->
                    "Villager is hired, raiding, selling, or fighting";
            case (int) MillenaireRecruitmentService.NOT_MILITARY ->
                    "Villager is not an eligible Millenaire fighter";
            case (int) MillenaireRecruitmentService.LEDGER_UNAVAILABLE ->
                    "Town-hall inventory is unavailable or not fully loaded; nothing was changed";
            case (int) MillenaireRecruitmentService.INSUFFICIENT_RESOURCES ->
                    "The town hall does not contain enough emeralds";
            case (int) MillenaireRecruitmentService.NOT_RECRUITED ->
                    "That villager is not a member of this army";
            case (int) MillenaireRecruitmentService.ARMY_LIMIT_REACHED ->
                    "Configured army limit reached";
            case (int) MillenaireRecruitmentService.INVALID_COUNT ->
                    "Invalid requested fighter count";
            case (int) MillenaireRecruitmentService.WRONG_DIMENSION ->
                    "Recruitment must be performed by a player in the settlement dimension";
            case (int) MillenaireRecruitmentService.SUPPLY_SHORTAGE ->
                    "Settlement reserves cannot equip another recruit";
            default -> "Recruitment failed (" + result + ')';
        };
    }

    private static ArmyCommandAuthority authority(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        return player == null
                ? new ArmyCommandAuthority(0L, 0L, false, source.hasPermission(2))
                : ArmyCommandAuthority.player(player.getUUID(), source.hasPermission(2));
    }
}
