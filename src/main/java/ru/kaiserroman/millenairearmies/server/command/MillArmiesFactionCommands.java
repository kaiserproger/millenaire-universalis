package ru.kaiserroman.millenairearmies.server.command;

import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import ru.kaiserroman.millenairearmies.integration.millenaire.FactionProjectionService;

/** Read-only command surface for the stable Millenaire culture/faction projection. */
public final class MillArmiesFactionCommands {
    private MillArmiesFactionCommands() {}

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher,
            FactionProjectionService factions) {
        dispatcher.register(literal("millarmies")
                .then(literal("factions").executes(context -> {
                    CommandSourceStack source = context.getSource();
                    int count = factions.size();
                    for (int row = 0; row < count; row++) {
                        int factionId = factions.factionId(row);
                        String line = factionId
                                + " " + factions.displayName(row)
                                + " culture=" + factions.cultureId(row)
                                + " settlements=" + factions.settlementCount(row)
                                + " population=" + factions.population(row)
                                + " influence=" + factions.influence(row)
                                + " capital=" + factions.capitalName(row);
                        source.sendSuccess(() -> Component.literal(line), false);
                    }
                    if (count == 0) {
                        source.sendSuccess(() -> Component.literal("No indexed Millenaire factions"), false);
                    }
                    return Math.max(1, count);
                })));
    }
}
