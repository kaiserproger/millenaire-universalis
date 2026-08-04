package ru.kaiserroman.millenairearmies.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.kaiserroman.millenairearmies.client.ui.MillenaireCommandScreen;
import ru.kaiserroman.millenairearmies.network.IssueOrderIntent;
import ru.kaiserroman.millenairearmies.network.SetGarrisonIntent;
import ru.kaiserroman.millenairearmies.network.SetSupplyChestIntent;

/** One explicit world-click target selection; no GUI-era crosshair result is ever reused. */
public final class ArmyTargetSelection {
    private static final byte MODE_ORDER = 1;
    private static final byte MODE_GARRISON = 2;
    private static final byte MODE_SUPPLY_CHEST = 3;

    private static int armyHandle;
    private static byte orderType;
    private static byte mode;
    private static long villageMost;
    private static long villageLeast;
    private static int guardRadius;
    private static long expectedRevision;
    private static int actionId;
    private static boolean active;
    private static boolean consumePauseOpening;
    private static boolean reopenWarCouncil;

    private ArmyTargetSelection() {}

    public static boolean begin(int army, byte order, long revision, int id) {
        return beginCommon(army, revision, id, MODE_ORDER, order, 0L, 0L, 0);
    }

    public static boolean beginGarrison(
            int army,
            long selectedVillageMost,
            long selectedVillageLeast,
            int radius,
            long revision,
            int id) {
        return beginCommon(
                army,
                revision,
                id,
                MODE_GARRISON,
                (byte) 0,
                selectedVillageMost,
                selectedVillageLeast,
                radius);
    }

    public static boolean beginSupplyChest(int army, long revision, int id) {
        return beginCommon(army, revision, id, MODE_SUPPLY_CHEST, (byte) 0, 0L, 0L, 0);
    }

    private static boolean beginCommon(
            int army,
            long revision,
            int id,
            byte selectionMode,
            byte order,
            long selectedVillageMost,
            long selectedVillageLeast,
            int radius) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null || minecraft.getConnection() == null) {
            return false;
        }
        armyHandle = army;
        orderType = order;
        mode = selectionMode;
        villageMost = selectedVillageMost;
        villageLeast = selectedVillageLeast;
        guardRadius = radius;
        expectedRevision = revision;
        actionId = id;
        active = true;
        consumePauseOpening = false;
        reopenWarCouncil = minecraft.screen instanceof MillenaireCommandScreen;
        minecraft.setScreen(null);
        String prompt = selectionMode == MODE_GARRISON
                ? "gui.millenaire_armies.garrison.target_prompt"
                : selectionMode == MODE_SUPPLY_CHEST
                        ? "gui.millenaire_armies.supply_chest.target_prompt"
                        : "gui.millenaire_armies.target.prompt";
        minecraft.player.displayClientMessage(Component.translatable(prompt), true);
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
        if (mode == MODE_GARRISON) {
            PacketDistributor.sendToServer(new SetGarrisonIntent(
                    actionId,
                    SetGarrisonIntent.OP_SET,
                    armyHandle,
                    villageMost,
                    villageLeast,
                    minecraft.level.dimension().location(),
                    hit.getBlockPos().asLong(),
                    guardRadius,
                    expectedRevision));
        } else if (mode == MODE_SUPPLY_CHEST) {
            PacketDistributor.sendToServer(new SetSupplyChestIntent(
                    actionId,
                    SetSupplyChestIntent.OP_SET,
                    armyHandle,
                    minecraft.level.dimension().location(),
                    hit.getBlockPos().asLong(),
                    expectedRevision));
        } else {
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
        }
        boolean reopen = reopenWarCouncil;
        clearSelection();
        if (reopen) {
            minecraft.setScreen(new MillenaireCommandScreen(selectedArmy));
        }
        return true;
    }

    public static void cancelFromEscape() {
        if (!active) {
            return;
        }
        int selectedArmy = armyHandle;
        boolean reopen = reopenWarCouncil;
        clearSelection();
        consumePauseOpening = true;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.translatable("gui.millenaire_armies.target.cancelled"), true);
        }
        if (reopen) {
            minecraft.setScreen(new MillenaireCommandScreen(selectedArmy));
        }
    }

    public static boolean consumePauseOpening() {
        boolean consume = consumePauseOpening;
        consumePauseOpening = false;
        return consume;
    }

    public static void clear() {
        clearSelection();
        consumePauseOpening = false;
    }

    private static void clearSelection() {
        active = false;
        reopenWarCouncil = false;
        mode = 0;
        villageMost = 0L;
        villageLeast = 0L;
        guardRadius = 0;
    }
}
