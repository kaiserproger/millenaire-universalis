package ru.kaiserroman.millenairearmies.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.kaiserroman.millenairearmies.client.ui.MillenaireCommandScreen;
import ru.kaiserroman.millenairearmies.network.IssueOrderIntent;

/** One explicit world-click target selection; no GUI-era crosshair result is ever reused. */
public final class ArmyTargetSelection {
    private static int armyHandle;
    private static byte orderType;
    private static long expectedRevision;
    private static int actionId;
    private static boolean active;
    private static boolean consumePauseOpening;

    private ArmyTargetSelection() {}

    public static boolean begin(int army, byte order, long revision, int id) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) {
            return false;
        }
        armyHandle = army;
        orderType = order;
        expectedRevision = revision;
        actionId = id;
        active = true;
        consumePauseOpening = false;
        minecraft.setScreen(null);
        minecraft.player.displayClientMessage(
                Component.translatable("gui.millenaire_armies.target.prompt"), true);
        return true;
    }

    public static boolean active() {
        return active;
    }

    public static boolean confirm(BlockHitResult hit) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!active || minecraft.player == null || minecraft.level == null
                || minecraft.getConnection() == null || hit == null) {
            return false;
        }
        int selectedArmy = armyHandle;
        PacketDistributor.sendToServer(new IssueOrderIntent(
                actionId,
                armyHandle,
                orderType,
                minecraft.level.dimension().location(),
                hit.getBlockPos().asLong(),
                0L,
                -1,
                expectedRevision,
                (byte) 0));
        active = false;
        minecraft.setScreen(new MillenaireCommandScreen(selectedArmy));
        return true;
    }

    public static void cancelFromEscape() {
        if (!active) {
            return;
        }
        int selectedArmy = armyHandle;
        active = false;
        consumePauseOpening = true;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.translatable("gui.millenaire_armies.target.cancelled"), true);
        }
        minecraft.setScreen(new MillenaireCommandScreen(selectedArmy));
    }

    public static boolean consumePauseOpening() {
        boolean consume = consumePauseOpening;
        consumePauseOpening = false;
        return consume;
    }

    public static void clear() {
        active = false;
        consumePauseOpening = false;
    }
}
