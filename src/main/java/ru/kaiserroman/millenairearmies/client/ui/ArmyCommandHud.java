package ru.kaiserroman.millenairearmies.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import ru.kaiserroman.millenairearmies.client.ArmyClientMirror;
import ru.kaiserroman.millenairearmies.client.ArmyClientState;

/**
 * Screenless field-command HUD. It deliberately operates only on the immutable client mirror and
 * sends the same validated intents as the strategic screen.
 */
public final class ArmyCommandHud {
    private static final int PANEL = 0xD91B1B1B;
    private static final int PANEL_SELECTED = 0xE04B3A22;
    private static final int BORDER = 0xFFE0B55A;
    private static final int TEXT = 0xFFF2F2F2;
    private static final int MUTED = 0xFFB8B8B8;
    private static final int GOOD = 0xFF8BD37A;
    private static final int WARNING = 0xFFE9C45C;
    private static final int BAD = 0xFFE36A5A;

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

    private static boolean active;
    private static int selectedArmyId;
    private static boolean hasSelectedArmy;
    private static long lastViewVersion = Long.MIN_VALUE;
    private static Component feedback;
    private static long feedbackUntil;

    private ArmyCommandHud() {
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

    /** Handles only Alt-modified command chords so normal Minecraft controls remain untouched. */
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
            case GLFW.GLFW_KEY_F -> cycleFormation(mirror);
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
        renderArmyStrip(graphics, font, mirror, screenWidth);
        renderCommandRail(graphics, font, mirror, screenHeight);
        renderSelectedArmy(graphics, font, mirror, screenWidth, screenHeight);

        if (feedback != null && System.currentTimeMillis() < feedbackUntil) {
            int textWidth = font.width(feedback);
            int x = (screenWidth - textWidth) / 2;
            int y = screenHeight - 54;
            graphics.fill(x - 5, y - 4, x + textWidth + 5, y + 12, PANEL);
            graphics.drawString(font, feedback, x, y, WARNING, true);
        }
    }

    private static void renderArmyStrip(GuiGraphics graphics, Font font, ArmyClientMirror mirror, int screenWidth) {
        int maxVisible = Math.max(1, (screenWidth - 24) / 62);
        int count = Math.min(Math.min(9, maxVisible), mirror.armyCount());
        if (!mirror.isReady() || count == 0) {
            int width = Math.min(screenWidth - 16, Math.max(font.width(TITLE), font.width(NO_ARMIES)) + 18);
            int x = (screenWidth - width) / 2;
            graphics.fill(x, 8, x + width, 39, PANEL);
            outline(graphics, x, 8, width, 31, BORDER);
            graphics.drawCenteredString(font, TITLE, screenWidth / 2, 13, TEXT);
            graphics.drawCenteredString(font, NO_ARMIES, screenWidth / 2, 25, MUTED);
            return;
        }

        int cardWidth = Math.min(104, Math.max(76, (screenWidth - 24) / count));
        int stripWidth = cardWidth * count + Math.max(0, count - 1) * 2;
        int startX = (screenWidth - stripWidth) / 2;
        for (int index = 0; index < count; index++) {
            int x = startX + index * (cardWidth + 2);
            boolean selected = hasSelectedArmy && mirror.armyId(index) == selectedArmyId;
            graphics.fill(x, 8, x + cardWidth, 39, selected ? PANEL_SELECTED : PANEL);
            outline(graphics, x, 8, cardWidth, 31, selected ? BORDER : 0xFF555555);
            String name = font.plainSubstrByWidth(mirror.armyName(index), cardWidth - 22);
            graphics.drawString(font, Integer.toString(index + 1), x + 6, 12, selected ? BORDER : MUTED, true);
            graphics.drawString(font, name, x + 19, 12, TEXT, true);
            String status = mirror.armyReadyUnitCount(index) + "/" + mirror.armyUnitCount(index)
                    + "  " + mirror.armySupplyPercent(index) + "%";
            graphics.drawString(font, status, x + 5, 25,
                    statusColor(mirror.armySupplyPercent(index)), false);
        }
    }

    private static void renderCommandRail(GuiGraphics graphics, Font font, ArmyClientMirror mirror, int screenHeight) {
        int x = 8;
        int railHeight = 125;
        int y = Math.max(42, (screenHeight - railHeight) / 2);
        if (y + railHeight > screenHeight - 45) {
            y = Math.max(42, screenHeight - 45 - railHeight);
        }
        drawTranslatedCommand(graphics, font, x, y, "Alt+H", "gui.millenaire_armies.action.hold", true);
        drawTranslatedCommand(graphics, font, x, y + 21, "Alt+M", "gui.millenaire_armies.action.move", true);
        drawTranslatedCommand(graphics, font, x, y + 42, "Alt+R", "gui.millenaire_armies.action.rally", true);
        drawTranslatedCommand(graphics, font, x, y + 63, "Alt+A", "gui.millenaire_armies.action.attack", true);
        String formation = selectedFormationName(mirror);
        drawCommand(graphics, font, x, y + 84, "Alt+F",
                Component.translatable("gui.millenaire_armies.hud.formation", formation).getString(), true);
        drawTranslatedCommand(graphics, font, x, y + 105, "Alt+L", "gui.millenaire_armies.action.logistics", true);
    }

    private static void renderSelectedArmy(
            GuiGraphics graphics,
            Font font,
            ArmyClientMirror mirror,
            int screenWidth,
            int screenHeight) {
        int index = selectedArmyIndex(mirror);
        if (index < 0) {
            return;
        }
        int width = Math.min(360, screenWidth - 32);
        int x = (screenWidth - width) / 2;
        int y = screenHeight - 39;
        graphics.fill(x, y, x + width, y + 31, PANEL);
        outline(graphics, x, y, width, 31, 0xFF555555);

        String name = font.plainSubstrByWidth(mirror.armyName(index), width / 2 - 10);
        graphics.drawString(font, name, x + 7, y + 5, TEXT, true);
        String order = Component.translatable(orderKey(mirror.armyOrderTypeCode(index))).getString();
        graphics.drawString(font, order, x + width - 7 - font.width(order), y + 5, WARNING, true);

        int barY = y + 19;
        int barWidth = (width - 28) / 2;
        drawBar(graphics, font, x + 7, barY, barWidth,
                Component.translatable("gui.millenaire_armies.label.morale").getString(),
                mirror.armyMoralePercent(index));
        drawBar(graphics, font, x + 14 + barWidth, barY, barWidth,
                Component.translatable("gui.millenaire_armies.label.supplies").getString(),
                mirror.armySupplyPercent(index));
    }

    private static void drawTranslatedCommand(
            GuiGraphics graphics,
            Font font,
            int x,
            int y,
            String key,
            String translationKey,
            boolean armyRequired) {
        drawCommand(graphics, font, x, y, key, Component.translatable(translationKey).getString(), armyRequired);
    }

    private static void drawCommand(
            GuiGraphics graphics,
            Font font,
            int x,
            int y,
            String key,
            String label,
            boolean armyRequired) {
        int width = 145;
        graphics.fill(x, y, x + width, y + 20, PANEL);
        outline(graphics, x, y, width, 20, 0xFF555555);
        graphics.fill(x + 3, y + 3, x + 44, y + 17, 0xE0333333);
        graphics.drawCenteredString(font, key, x + 23, y + 6, BORDER);
        int color = armyRequired && !hasSelectedArmy ? 0xFF777777 : TEXT;
        graphics.drawString(font, font.plainSubstrByWidth(label, width - 53), x + 50, y + 6, color, false);
    }

    private static void drawBar(GuiGraphics graphics, Font font, int x, int y, int width, String label, int value) {
        int clamped = Math.max(0, Math.min(100, value));
        graphics.fill(x, y, x + width, y + 8, 0xFF292929);
        graphics.fill(x, y, x + width * clamped / 100, y + 8, statusColor(clamped));
        String text = label + " " + clamped + "%";
        graphics.drawCenteredString(font, text, x + width / 2, y, 0xFFFFFFFF);
    }

    private static boolean issueOrder(ArmyClientMirror mirror, int orderCode) {
        boolean accepted = hasSelectedArmy && mirror.requestIssueOrder(selectedArmyId, orderCode);
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

    private static String selectedFormationName(ArmyClientMirror mirror) {
        int index = selectedArmyIndex(mirror);
        int code = index < 0 ? 0 : mirror.armyFormationCode(index);
        if (code < 0 || code >= FORMATION_KEYS.length) {
            code = 0;
        }
        return Component.translatable(FORMATION_KEYS[code]).getString();
    }

    private static String orderKey(int orderCode) {
        return switch (orderCode) {
            case 0 -> "gui.millenaire_armies.order.hold";
            case 1 -> "gui.millenaire_armies.order.move";
            case 2 -> "gui.millenaire_armies.order.rally";
            case 3 -> "gui.millenaire_armies.order.logistics";
            case 4 -> "gui.millenaire_armies.order.attack";
            default -> "gui.millenaire_armies.order.unknown";
        };
    }

    private static int statusColor(int value) {
        return value >= 67 ? GOOD : value >= 34 ? WARNING : BAD;
    }

    private static void showFeedback(Component message) {
        feedback = message;
        feedbackUntil = System.currentTimeMillis() + 1800L;
    }

    private static void outline(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y, x + 1, y + height, color);
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }
}
