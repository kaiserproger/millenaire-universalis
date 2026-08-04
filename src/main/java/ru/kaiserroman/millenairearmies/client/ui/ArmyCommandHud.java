package ru.kaiserroman.millenairearmies.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.client.ArmyClientMirror;
import ru.kaiserroman.millenairearmies.client.ArmyClientState;

/**
 * Screenless field command strip. It deliberately operates only on the immutable client mirror and
 * sends the same validated intents as the strategic screens. The strip is a compact bottom-left
 * parchment panel: warband chips, the selected warband's status, and the Alt command chords.
 */
public final class ArmyCommandHud {
    private static final Component TITLE = Component.translatable("gui.millenaire_armies.hud.title");
    private static final Component NO_ARMIES = Component.translatable("gui.millenaire_armies.hud.no_armies");
    private static final Component COMMAND_UNAVAILABLE = Component.translatable("gui.millenaire_armies.command.unavailable");
    private static final Component COMMAND_SENT = Component.translatable("gui.millenaire_armies.command.sent");

    private static final String[] FORMATION_KEYS = {
            "gui.millenaire_armies.formation.line",
            "gui.millenaire_armies.formation.column",
            "gui.millenaire_armies.formation.wedge",
            "gui.millenaire_armies.formation.square",
            "gui.millenaire_armies.formation.skirmish"
    };
    private static final String[] ORDER_KEYS = {
            "gui.millenaire_armies.order.hold",
            "gui.millenaire_armies.order.move",
            "gui.millenaire_armies.order.rally",
            "gui.millenaire_armies.order.logistics",
            "gui.millenaire_armies.order.attack",
            "gui.millenaire_armies.order.garrison",
            "gui.millenaire_armies.order.siege"
    };

    private static final String[] HINT_LABEL_KEYS = {
            "gui.millenaire_armies.action.hold_short",
            "gui.millenaire_armies.action.move_short",
            "gui.millenaire_armies.action.attack_short",
            "gui.millenaire_armies.action.rally_short",
            null,
            "gui.millenaire_armies.action.siege_short",
            "gui.millenaire_armies.action.garrison_short",
            "gui.millenaire_armies.action.logistics_short",
    };

    private static final char[] HINT_KEYS = {'H', 'M', 'A', 'R', 'F', 'S', 'G', 'L'};
    private static final int HINT_COUNT = HINT_KEYS.length;

    private static boolean active;
    private static int selectedArmyId;
    private static boolean hasSelectedArmy;
    private static long lastViewVersion = Long.MIN_VALUE;
    private static Component feedback;
    private static long feedbackUntil;

    record HudLayout(
            int x, int y, int width, int height,
            int chipStart, int chipCount, int chipX, int chipY, int chipWidth, int chipGap,
            int infoX, int infoY, int infoWidth,
            int hintsX, int hintsY, int hintsWidth,
            int feedbackY) {
    }

    private ArmyCommandHud() {
    }

    static HudLayout computeHudLayout(int screenWidth, int screenHeight, int armyCount, int selectedIndex) {
        int x = 8;
        int width = Math.min(480, Math.max(160, screenWidth - 16));
        if (x + width > screenWidth) {
            width = screenWidth - x;
        }
        int height = 50;
        int y = screenHeight - height - 6;
        int capacity = screenWidth < 400 ? 3 : screenWidth < 560 ? 6 : 9;
        int chipCount = Math.min(armyCount, capacity);
        int chipStart;
        if (selectedIndex >= chipCount) {
            chipStart = Math.min(selectedIndex - chipCount + 1, armyCount - chipCount);
        } else {
            chipStart = 0;
        }
        if (chipStart < 0) chipStart = 0;
        int chipX = x + 8;
        int chipY = y + 6;
        int chipWidth = 13;
        int chipGap = 2;
        int infoX = chipX + chipCount * (chipWidth + chipGap) + (chipCount > 0 ? 6 : 0);
        int infoWidth = Math.max(1, width - (infoX - x) - 8);
        int infoY = y + 6;
        int hintsX = x + 8;
        int hintsY = y + 31;
        int hintsWidth = width - 16;
        int feedbackY = y - 18;
        return new HudLayout(x, y, width, height,
                chipStart, chipCount, chipX, chipY, chipWidth, chipGap,
                infoX, infoY, infoWidth,
                hintsX, hintsY, hintsWidth,
                feedbackY);
    }

    public static void toggle() {
        active = !active;
        if (active) {
            ArmyClientState.current().requestFullSync();
            validateSelection(ArmyClientState.current());
        }
    }

    public static void deactivate() {
        active = false;
    }

    public static boolean active() {
        return active;
    }

    public static boolean hasSelectedArmy() {
        return hasSelectedArmy;
    }

    public static int selectedArmyId() {
        return selectedArmyId;
    }

    public static boolean handleKey(int key, int action, int modifiers) {
        if (!active || action != GLFW.GLFW_PRESS || (modifiers & GLFW.GLFW_MOD_ALT) == 0) {
            return false;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null) {
            return false;
        }

        ArmyClientMirror mirror = ArmyClientState.current();
        validateSelection(mirror);
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9) {
            selectArmy(mirror, key - GLFW.GLFW_KEY_1);
            return true;
        }
        return switch (key) {
            case GLFW.GLFW_KEY_H -> issueOrder(mirror, 0);
            case GLFW.GLFW_KEY_M -> issueOrder(mirror, 1);
            case GLFW.GLFW_KEY_R -> issueOrder(mirror, 2);
            case GLFW.GLFW_KEY_L -> issueOrder(mirror, 3);
            case GLFW.GLFW_KEY_A -> issueOrder(mirror, 4);
            case GLFW.GLFW_KEY_S -> issueOrder(mirror, 6);
            case GLFW.GLFW_KEY_F -> cycleFormation(mirror);
            case GLFW.GLFW_KEY_G -> assignGarrison(mirror);
            default -> false;
        };
    }

    public static void render(GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!active || minecraft.player == null || minecraft.options.hideGui || minecraft.screen != null) {
            return;
        }

        ArmyClientMirror mirror = ArmyClientState.current();
        if (mirror.viewVersion() != lastViewVersion) {
            lastViewVersion = mirror.viewVersion();
            validateSelection(mirror);
        }

        Font font = minecraft.font;
        int screenWidth = minecraft.getWindow().getGuiScaledWidth();
        int screenHeight = minecraft.getWindow().getGuiScaledHeight();
        int selectedIndex = selectedArmyIndex(mirror);

        HudLayout layout = computeHudLayout(screenWidth, screenHeight, mirror.armyCount(), Math.max(0, selectedIndex));
        renderHud(graphics, font, mirror, selectedIndex, layout);
        drawFeedback(graphics, font, screenWidth, layout);
    }

    private static void renderHud(
            GuiGraphics graphics, Font font, ArmyClientMirror mirror,
            int selectedIndex, HudLayout l) {

        if (!mirror.isReady() || mirror.armyCount() == 0) {
            int h = 24;
            int y = l.y() + l.height() - h;
            graphics.fill(l.x(), y, l.x() + l.width(), y + h, MilitaryUi.PANEL);
            MilitaryUi.outline(graphics, l.x(), y, l.width(), h, MilitaryUi.BORDER_DARK);
            String titleC = font.plainSubstrByWidth(TITLE.getString(), Math.max(40, l.width() - 16 - 60));
            graphics.drawString(font, titleC, l.x() + 8, y + 8, MilitaryUi.WARNING, true);
            int remW = l.width() - 16 - font.width(titleC) - 8;
            String noA = font.plainSubstrByWidth(NO_ARMIES.getString(), Math.max(20, remW));
            graphics.drawString(font, noA, l.x() + 8 + font.width(titleC) + 8, y + 8, MilitaryUi.MUTED, false);
            return;
        }

        MilitaryUi.frame(graphics, l.x(), l.y(), l.width(), l.height());

        int gap = 4;
        for (int i = 0; i < l.chipCount(); i++) {
            int cx = l.chipX() + i * (l.chipWidth() + l.chipGap());
            int realIndex = l.chipStart() + i;
            boolean sel = realIndex == selectedIndex;
            graphics.fill(cx, l.chipY(), cx + l.chipWidth(), l.chipY() + 12,
                    sel ? MilitaryUi.CARD_SELECTED : MilitaryUi.PANEL_INNER);
            MilitaryUi.outline(graphics, cx, l.chipY(), l.chipWidth(), 12,
                    sel ? MilitaryUi.GOLD : MilitaryUi.BORDER_DARK);
            if (sel) {
                graphics.fill(cx, l.chipY(), cx + 3, l.chipY() + 12, MilitaryUi.GOLD);
            }
            String num = Integer.toString(realIndex + 1);
            graphics.drawCenteredString(font, num, cx + l.chipWidth() / 2, l.chipY() + 2,
                    sel ? MilitaryUi.WARNING : MilitaryUi.MUTED);
        }

        if (selectedIndex < 0 || selectedIndex >= mirror.armyCount()) return;

        int rightX = l.infoX() + l.infoWidth();
        int row1TextY = l.infoY() + 6;

        String ready = mirror.armyReadyUnitCount(selectedIndex) + "/" + mirror.armyUnitCount(selectedIndex);
        String supply = mirror.armySupplyPercent(selectedIndex) + "%";
        String morale = mirror.armyMoralePercent(selectedIndex) + "%";
        boolean hasGarrison = mirror.armyHasGarrison(selectedIndex);
        int orderCode = mirror.armyOrderTypeCode(selectedIndex);
        int formation = mirror.armyFormationCode(selectedIndex);
        boolean wide = l.width() >= 400;

        int orderItemW = wide ? 54 : 16;
        int formItemW = wide ? 54 : 16;
        int garrisonItemW = 16;

        int statusW = 0;
        statusW += font.width(ready) + gap;
        statusW += font.width(supply) + gap;
        statusW += font.width(morale) + gap;
        statusW += orderItemW + gap;
        statusW += formItemW + gap;
        if (hasGarrison) statusW += garrisonItemW + gap;

        int nameMax = l.infoWidth() - statusW - gap;
        if (nameMax < 0) nameMax = 0;

        if (nameMax >= 30) {
            String name = font.plainSubstrByWidth(mirror.armyName(selectedIndex), nameMax);
            graphics.drawString(font, name, l.infoX(), row1TextY, MilitaryUi.GOLD, true);
        }

        int itemX = rightX;

        itemX -= orderItemW;
        MilitaryUi.orderGlyph(graphics, itemX, l.infoY() + 3, orderCode, MilitaryUi.GOLD);
        if (wide) {
            String oLabel = font.plainSubstrByWidth(orderName(orderCode), 36);
            graphics.drawString(font, oLabel, itemX + 18, row1TextY, MilitaryUi.MUTED, false);
        }
        itemX -= gap;

        itemX -= formItemW;
        MilitaryUi.formationMark(graphics, itemX, l.infoY() + 2, formation, MilitaryUi.GOLD);
        if (wide) {
            String fLabel = font.plainSubstrByWidth(formationName(formation), 36);
            graphics.drawString(font, fLabel, itemX + 18, row1TextY, MilitaryUi.MUTED, false);
        }
        itemX -= gap;

        if (hasGarrison) {
            itemX -= garrisonItemW;
            MilitaryUi.orderGlyph(graphics, itemX, l.infoY() + 3, 5, MilitaryUi.GOLD);
            itemX -= gap;
        }

        itemX -= font.width(morale) + gap;
        graphics.drawString(font, morale, itemX, row1TextY,
                MilitaryUi.statusColor(mirror.armyMoralePercent(selectedIndex)), false);
        itemX -= font.width(supply) + gap;
        graphics.drawString(font, supply, itemX, row1TextY,
                MilitaryUi.statusColor(mirror.armySupplyPercent(selectedIndex)), false);
        itemX -= font.width(ready) + gap;
        graphics.drawString(font, ready, itemX, row1TextY, MilitaryUi.TEXT, false);

        String altPrefix = "Alt:";
        int prefixW = font.width(altPrefix) + gap;
        int hintX = l.hintsX();
        int remainingW = l.hintsWidth() - prefixW;
        graphics.drawString(font, altPrefix, hintX, l.hintsY(), MilitaryUi.GOLD, true);
        hintX += prefixW;

        for (int i = 0; i < HINT_COUNT; i++) {
            String label;
            if (HINT_LABEL_KEYS[i] == null) {
                label = formationName(mirror.armyFormationCode(selectedIndex));
            } else {
                label = Component.translatable(HINT_LABEL_KEYS[i]).getString();
            }
            String entry = String.valueOf(HINT_KEYS[i]) + " ";
            int entryW = font.width(entry);
            int labelW = font.width(label);
            int totalW = entryW + labelW + gap;
            if (totalW > remainingW) continue;
            remainingW -= totalW;
            graphics.drawString(font, entry, hintX, l.hintsY(), MilitaryUi.GOLD, true);
            hintX += entryW;
            graphics.drawString(font, label, hintX, l.hintsY(), MilitaryUi.MUTED, false);
            hintX += labelW + gap;
        }
    }

    private static void drawFeedback(
            GuiGraphics graphics, Font font, int screenWidth, HudLayout l) {
        if (feedback == null || System.currentTimeMillis() >= feedbackUntil) return;
        String fbText = font.plainSubstrByWidth(feedback.getString(), Math.max(20, screenWidth - 20));
        int fbWidth = font.width(fbText);
        int fbX = Math.max(4, (screenWidth - fbWidth) / 2);
        int fbY = l.feedbackY();
        graphics.fill(fbX - 4, fbY, fbX + fbWidth + 4, fbY + 14, MilitaryUi.PANEL);
        MilitaryUi.outline(graphics, fbX - 4, fbY, fbWidth + 8, 14, MilitaryUi.BORDER_DARK);
        graphics.drawString(font, fbText, fbX, fbY + 3, MilitaryUi.WARNING, true);
    }

    private static boolean issueOrder(ArmyClientMirror mirror, int orderCode) {
        boolean accepted = hasSelectedArmy && mirror.requestIssueOrder(selectedArmyId, orderCode);
        showFeedback(accepted ? COMMAND_SENT : COMMAND_UNAVAILABLE);
        return true;
    }

    private static boolean assignGarrison(ArmyClientMirror mirror) {
        int index = selectedArmyIndex(mirror);
        int radius = index >= 0 && mirror.armyHasGarrison(index)
                ? mirror.armyGarrisonRadius(index)
                : ArmiesConfig.GARRISON_DEFAULT_RADIUS;
        boolean accepted = hasSelectedArmy && mirror.requestSetGarrison(
                selectedArmyId, 0L, 0L, radius);
        showFeedback(accepted ? COMMAND_SENT : COMMAND_UNAVAILABLE);
        return true;
    }

    private static boolean cycleFormation(ArmyClientMirror mirror) {
        int index = selectedArmyIndex(mirror);
        int current = index < 0 ? -1 : mirror.armyFormationCode(index);
        int next = current < 0 || current >= FORMATION_KEYS.length - 1 ? 0 : current + 1;
        boolean accepted = index >= 0 && mirror.requestSetFormation(selectedArmyId, next);
        showFeedback(accepted ? COMMAND_SENT : COMMAND_UNAVAILABLE);
        return true;
    }

    private static void selectArmy(ArmyClientMirror mirror, int index) {
        if (index < 0 || index >= mirror.armyCount()) {
            showFeedback(COMMAND_UNAVAILABLE);
            return;
        }
        selectedArmyId = mirror.armyId(index);
        hasSelectedArmy = true;
        showFeedback(Component.translatable("gui.millenaire_armies.hud.selected", mirror.armyName(index)));
    }

    private static void validateSelection(ArmyClientMirror mirror) {
        if (!mirror.isReady() || mirror.armyCount() == 0) {
            hasSelectedArmy = false;
            return;
        }
        if (selectedArmyIndex(mirror) >= 0) {
            return;
        }
        selectedArmyId = mirror.armyId(0);
        hasSelectedArmy = true;
    }

    private static int selectedArmyIndex(ArmyClientMirror mirror) {
        if (!hasSelectedArmy) {
            return -1;
        }
        for (int index = 0; index < mirror.armyCount(); index++) {
            if (mirror.armyId(index) == selectedArmyId) {
                return index;
            }
        }
        return -1;
    }

    private static String formationName(int code) {
        if (code < 0 || code >= FORMATION_KEYS.length) {
            code = 0;
        }
        return Component.translatable(FORMATION_KEYS[code]).getString();
    }

    private static String orderName(int orderCode) {
        if (orderCode < 0 || orderCode >= ORDER_KEYS.length) {
            return Component.translatable("gui.millenaire_armies.order.unknown").getString();
        }
        return Component.translatable(ORDER_KEYS[orderCode]).getString();
    }

    private static void showFeedback(Component message) {
        feedback = message;
        feedbackUntil = System.currentTimeMillis() + 1800L;
    }
}
