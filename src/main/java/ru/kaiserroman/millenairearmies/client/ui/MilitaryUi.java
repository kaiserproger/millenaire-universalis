package ru.kaiserroman.millenairearmies.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

/** Shared parchment/wood/ink/gold drawing helpers for the player-facing military screens. */
final class MilitaryUi {
    static final int PANEL = 0xF02A2117;
    static final int PANEL_INNER = 0xED17130F;
    static final int PANEL_ALT = 0xEA382B1C;
    static final int CARD = 0xE63B2E20;
    static final int CARD_HOVER = 0xF04B3B29;
    static final int CARD_SELECTED = 0xF05A4428;
    static final int BORDER = 0xFFB28A4E;
    static final int BORDER_DARK = 0xFF5A4329;
    static final int GOLD = 0xFFFFD887;
    static final int TEXT = 0xFFF2E8D3;
    static final int MUTED = 0xFFD2C3A6;
    static final int SELECTED = 0xE06A4D2B;
    static final int HOVER = 0xB05A4630;
    static final int GOOD = 0xFF9FE29A;
    static final int WARNING = 0xFFE9C45C;
    static final int BAD = 0xFFE36A5A;
    static final int ROW_HEIGHT = 28;

    private MilitaryUi() {
    }

    // --- Layout helper (package-private, used by screens + self-test) ---

    record LedgerPlacement(
            int contentTop,
            int contentBottom,
            int listWidth,
            int detailX,
            int detailWidth,
            int formationSectionY,
            int formationY,
            int formationButtonWidth,
            int orderSectionY,
            int orderY0,
            int orderY1,
            int orderTileHeight,
            int orderTileWidth,
            int orderCols,
            boolean compact) {
    }

    static LedgerPlacement computeLedgerLayout(int panelX, int panelY, int panelWidth, int panelHeight) {
        int pad = 20;
        int gap = 4;
        int bottom = panelY + panelHeight;

        boolean compact = panelWidth < 520;
        int contentTop = panelY + 54;

        int orderTileHeight = compact ? 28 : 34;
        int orderCols = 4;
        int orderTileWidth = Math.max(40, (panelWidth - pad - gap * (orderCols - 1)) / orderCols);

        int orderY1 = bottom - 4 - orderTileHeight;
        int orderY0 = orderY1 - orderTileHeight - gap;
        int orderSectionY = orderY0 - 2;

        int formationButtonHeight = 22;
        int formationY = orderSectionY - formationButtonHeight - 6;
        int formationSectionY = formationY - 12;
        int formationButtonWidth = Math.max(40, (panelWidth - pad - gap * 4) / 5);

        int contentBottom = formationSectionY - 6;

        int listWidth = panelWidth < 430
                ? 116
                : Math.min(204, Math.max(132, (panelWidth - 30) * 36 / 100));
        int detailX = panelX + 10 + listWidth + 8;
        int detailWidth = panelX + panelWidth - 10 - detailX;

        return new LedgerPlacement(
                contentTop, contentBottom,
                listWidth, detailX, detailWidth,
                formationSectionY, formationY, formationButtonWidth,
                orderSectionY, orderY0, orderY1,
                orderTileHeight, orderTileWidth,
                orderCols, compact);
    }

    record RecruitmentPlacement(
            int contentTop,
            int contentBottom,
            int listWidth,
            int rightX,
            int rightWidth,
            int statusCardY,
            int statusCardHeight,
            int actionY,
            boolean compact) {
    }

    static RecruitmentPlacement computeRecruitmentLayout(int panelX, int panelY, int panelWidth, int panelHeight) {
        int bottom = panelY + panelHeight;
        int actionY = bottom - 30;
        boolean compact = panelWidth < 430;
        int statusH = compact ? 38 : 52;
        int statusY = actionY - 4 - statusH;
        int contentTop = panelY + 54;
        int contentBottom = statusY - 4;
        int pad = panelWidth - 30;
        int listWidth = Math.min(220, Math.max(132, pad * 38 / 100));
        int rightX = panelX + 10 + listWidth + 10;
        int rightWidth = panelX + panelWidth - 10 - rightX;
        return new RecruitmentPlacement(
                contentTop, contentBottom,
                listWidth, rightX, rightWidth,
                statusY, statusH,
                actionY, compact);
    }

    record RealmPlacement(
            int contentTop,
            int contentBottom,
            int listWidth,
            int detailX,
            int detailWidth,
            int actionY,
            boolean compact) {
    }

    static RealmPlacement computeRealmLayout(int panelX, int panelY, int panelWidth, int panelHeight) {
        int bottom = panelY + panelHeight;
        int actionY = bottom - 30;
        int contentTop = panelY + 54;
        int contentBottom = actionY - 4;
        int listWidth = panelWidth < 430
                ? 116
                : Math.min(220, Math.max(132, (panelWidth - 30) * 36 / 100));
        int detailX = panelX + 10 + listWidth + 10;
        int detailWidth = panelX + panelWidth - 10 - detailX;
        boolean compact = detailWidth < 250;
        return new RealmPlacement(
                contentTop, contentBottom,
                listWidth, detailX, detailWidth,
                actionY, compact);
    }

    static int ledgerMinPanelHeight() {
        return 208;
    }

    // --- Vanilla Button factories (backward compat for Recruitment / Realm) ---

    static Button button(String translationKey, int x, int y, int width, int height, Button.OnPress onPress) {
        return Button.builder(Component.translatable(translationKey), onPress)
                .bounds(x, y, width, height)
                .build();
    }

    static Button button(Component label, int x, int y, int width, int height, Button.OnPress onPress) {
        return Button.builder(label, onPress)
                .bounds(x, y, width, height)
                .build();
    }

    // --- ParchmentButton factories ---

    static ParchmentButton parchmentButton(
            Component label, int x, int y, int width, int height, ParchmentButton.OnPress onPress) {
        return new ParchmentButton(x, y, width, height, label, onPress);
    }

    static ParchmentButton parchmentButton(
            String translationKey, int x, int y, int width, int height, ParchmentButton.OnPress onPress) {
        return new ParchmentButton(x, y, width, height, Component.translatable(translationKey), onPress);
    }

    static ParchmentButton parchmentButton(
            Component label, int x, int y, int width, int height,
            int glyphInsetLeft, int glyphTopHeight,
            ParchmentButton.GlyphRenderer glyphRenderer,
            ParchmentButton.OnPress onPress) {
        return new ParchmentButton(x, y, width, height, label, glyphInsetLeft, glyphTopHeight, glyphRenderer, onPress);
    }

    static ParchmentButton parchmentButton(
            String translationKey, int x, int y, int width, int height,
            int glyphInsetLeft, int glyphTopHeight,
            ParchmentButton.GlyphRenderer glyphRenderer,
            ParchmentButton.OnPress onPress) {
        return new ParchmentButton(x, y, width, height, Component.translatable(translationKey),
                glyphInsetLeft, glyphTopHeight, glyphRenderer, onPress);
    }

    // --- Drawing primitives ---

    /** Parchment background with the double military border. */
    static void frame(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, PANEL);
        outline(graphics, x, y, width, height, BORDER);
        outline(graphics, x + 3, y + 3, width - 6, height - 6, BORDER_DARK);
    }

    /** Recessed content well with a thin inner border. */
    static void well(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, PANEL_INNER);
        outline(graphics, x, y, width, height, BORDER_DARK);
    }

    /** Raised, selectable card used by the hub and the primary content columns. */
    static void card(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            int height,
            boolean selected,
            boolean hovered) {
        int color = selected ? CARD_SELECTED : hovered ? CARD_HOVER : CARD;
        graphics.fill(x, y, x + width, y + height, color);
        outline(graphics, x, y, width, height, selected ? GOLD : BORDER_DARK);
        if (selected) {
            graphics.fill(x, y, x + 4, y + height, GOLD);
        }
    }

    /** Small section label and divider. */
    static void section(Font font, GuiGraphics graphics, Component title, int x, int y, int width) {
        graphics.drawString(font, title, x, y, GOLD, true);
        int dividerX = x + Math.min(width - 4, font.width(title) + 8);
        if (dividerX < x + width) {
            graphics.fill(dividerX, y + 5, x + width, y + 6, BORDER_DARK);
        }
    }

    /** Left-aligned small seal square drawn before a title. */
    static void seal(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 5, y + 5, GOLD);
        graphics.fill(x + 2, y + 2, x + 3, y + 3, PANEL);
    }

    /** Readable list card: primary line and an optional muted secondary line. */
    static void row(
            Font font,
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            boolean selected,
            boolean hovered,
            String primary,
            String secondary,
            boolean selectedText) {
        graphics.fill(x, y, x + width, y + ROW_HEIGHT - 2,
                selected ? SELECTED : hovered ? HOVER : PANEL_ALT);
        outline(graphics, x, y, width, ROW_HEIGHT - 2, selected ? GOLD : BORDER_DARK);
        if (selected) {
            graphics.fill(x, y, x + 3, y + ROW_HEIGHT - 2, GOLD);
        }
        String compactPrimary = font.plainSubstrByWidth(primary, Math.max(16, width - 14));
        graphics.drawString(font, compactPrimary, x + 7, y + 4, selectedText ? GOLD : TEXT, true);
        if (secondary != null && !secondary.isEmpty()) {
            String compactSecondary = font.plainSubstrByWidth(secondary, Math.max(16, width - 14));
            graphics.drawString(font, compactSecondary, x + 7, y + 15, MUTED, false);
        }
    }

    /** Label/value plus a compact status bar. Percent is clamped for rendering only. */
    static void meter(
            Font font,
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            Component label,
            String value,
            int percent) {
        graphics.drawString(font, label, x, y, MUTED, false);
        String compactValue = font.plainSubstrByWidth(value, Math.max(20, width / 2));
        graphics.drawString(font, compactValue, x + width - font.width(compactValue), y, TEXT, false);
        int barY = y + 11;
        int clamped = Math.max(0, Math.min(100, percent));
        graphics.fill(x, barY, x + width, barY + 5, PANEL_INNER);
        outline(graphics, x, barY, width, 5, BORDER_DARK);
        int filled = Math.max(0, (width - 2) * clamped / 100);
        if (filled > 0) {
            graphics.fill(x + 1, barY + 1, x + 1 + filled, barY + 4, statusColor(clamped));
        }
    }

    /** Tiny formation diagram drawn entirely from GUI primitives. */
    static void formationMark(GuiGraphics graphics, int x, int y, int formation, int color) {
        switch (formation) {
            case 1 -> {
                dot(graphics, x + 4, y, color); dot(graphics, x + 8, y, color);
                dot(graphics, x + 4, y + 4, color); dot(graphics, x + 8, y + 4, color);
                dot(graphics, x + 4, y + 8, color); dot(graphics, x + 8, y + 8, color);
            }
            case 2 -> {
                dot(graphics, x + 6, y, color);
                dot(graphics, x + 3, y + 4, color); dot(graphics, x + 9, y + 4, color);
                dot(graphics, x, y + 8, color); dot(graphics, x + 12, y + 8, color);
            }
            case 3 -> {
                dot(graphics, x, y, color); dot(graphics, x + 5, y, color); dot(graphics, x + 10, y, color);
                dot(graphics, x, y + 5, color); dot(graphics, x + 10, y + 5, color);
                dot(graphics, x, y + 10, color); dot(graphics, x + 5, y + 10, color); dot(graphics, x + 10, y + 10, color);
            }
            case 4 -> {
                dot(graphics, x, y + 1, color); dot(graphics, x + 8, y, color);
                dot(graphics, x + 4, y + 6, color); dot(graphics, x + 13, y + 5, color);
                dot(graphics, x + 1, y + 11, color); dot(graphics, x + 10, y + 10, color);
            }
            default -> {
                dot(graphics, x, y + 5, color); dot(graphics, x + 3, y + 5, color);
                dot(graphics, x + 6, y + 5, color); dot(graphics, x + 9, y + 5, color);
                dot(graphics, x + 12, y + 5, color);
            }
        }
    }

    /** Order-type glyph drawn from primitives (16×12 area). */
    static void orderGlyph(GuiGraphics graphics, int x, int y, int orderCode, int color) {
        switch (orderCode) {
            case 0 -> {
                graphics.fill(x + 4, y, x + 11, y + 1, color);
                graphics.fill(x + 2, y + 1, x + 13, y + 2, color);
                graphics.fill(x, y + 2, x + 15, y + 10, color);
                graphics.fill(x + 2, y + 3, x + 13, y + 9, PANEL);
            }
            case 1 -> {
                for (int row = 0; row < 7; row++) {
                    graphics.fill(x + row, y + row, x + 15 - row, y + row + 1, color);
                }
                graphics.fill(x + 8, y + 7, x + 15, y + 12, color);
                graphics.fill(x, y + 9, x + 8, y + 12, color);
            }
            case 2 -> {
                graphics.fill(x + 13, y, x + 15, y + 12, color);
                graphics.fill(x, y + 2, x + 13, y + 3, color);
                graphics.fill(x, y + 5, x + 11, y + 6, color);
                graphics.fill(x, y + 8, x + 8, y + 9, color);
            }
            case 3 -> {
                graphics.fill(x, y, x + 15, y + 12, color);
                graphics.fill(x + 2, y + 2, x + 13, y + 10, PANEL);
                graphics.fill(x + 2, y + 2, x + 13, y + 3, color);
                graphics.fill(x + 6, y + 5, x + 9, y + 6, color);
                graphics.fill(x + 7, y + 4, x + 8, y + 7, color);
            }
            case 4 -> {
                graphics.fill(x, y + 1, x + 4, y + 12, color);
                graphics.fill(x + 11, y + 1, x + 15, y + 12, color);
                graphics.fill(x + 2, y + 2, x + 3, y + 11, PANEL);
                graphics.fill(x + 12, y + 2, x + 13, y + 11, PANEL);
            }
            case 5 -> {
                graphics.fill(x, y + 5, x + 15, y + 12, color);
                graphics.fill(x, y + 1, x + 15, y + 5, color);
                graphics.fill(x + 5, y, x + 10, y + 1, color);
                graphics.fill(x + 4, y, x + 5, y + 5, color);
                graphics.fill(x + 10, y, x + 11, y + 5, color);
            }
            case 6 -> {
                graphics.fill(x + 3, y + 2, x + 12, y + 12, color);
                graphics.fill(x + 1, y, x + 4, y + 2, color);
                graphics.fill(x + 6, y, x + 9, y + 2, color);
                graphics.fill(x + 11, y, x + 14, y + 2, color);
                graphics.fill(x + 5, y + 4, x + 10, y + 5, PANEL);
                graphics.fill(x + 5, y + 7, x + 10, y + 8, PANEL);
                graphics.fill(x + 5, y + 10, x + 10, y + 11, PANEL);
            }
            case -1 -> {
                for (int i = 0; i < 6; i++) {
                    int off = i * 2;
                    graphics.fill(x + 2 + off, y + off, x + 4 + off, y + 2 + off, color);
                    graphics.fill(x + 12 - off, y + off, x + 14 - off, y + 2 + off, color);
                }
            }
            default -> {
                graphics.fill(x + 2, y + 2, x + 13, y + 11, color);
                graphics.fill(x + 3, y + 3, x + 12, y + 10, PANEL);
                graphics.fill(x + 5, y + 4, x + 10, y + 5, color);
                graphics.fill(x + 7, y + 6, x + 8, y + 8, color);
                graphics.fill(x + 7, y + 8, x + 8, y + 9, color);
            }
        }
    }

    /** Hub entry glyph: 16×14 area, larger than the order glyph. */
    static void entryGlyph(GuiGraphics graphics, int x, int y, int entry, int color) {
        switch (entry) {
            case 0 -> {
                graphics.fill(x + 3, y, x + 13, y + 14, color);
                graphics.fill(x + 2, y + 1, x + 14, y + 13, PANEL);
                graphics.fill(x + 6, y + 3, x + 10, y + 4, color);
                graphics.fill(x + 4, y + 5, x + 12, y + 11, color);
                graphics.fill(x + 5, y + 6, x + 11, y + 10, PANEL);
                dot(graphics, x + 7, y + 7, color);
            }
            case 1 -> {
                graphics.fill(x, y + 2, x + 4, y + 6, color);
                graphics.fill(x + 4, y + 1, x + 8, y + 5, color);
                graphics.fill(x + 2, y + 6, x + 8, y + 9, color);
                graphics.fill(x + 2, y + 9, x + 14, y + 12, color);
                graphics.fill(x + 10, y + 6, x + 16, y + 10, color);
                dot(graphics, x + 3, y + 3, PANEL);
            }
            default -> {
                graphics.fill(x, y + 5, x + 15, y + 14, color);
                graphics.fill(x + 2, y + 6, x + 13, y + 13, PANEL);
                graphics.fill(x + 3, y + 1, x + 12, y + 5, color);
                graphics.fill(x + 4, y + 2, x + 11, y + 4, PANEL);
                graphics.fill(x + 6, y - 1, x + 9, y + 1, color);
            }
        }
    }

    static void chevron(GuiGraphics graphics, int x, int y, int color) {
        graphics.fill(x, y, x + 2, y + 2, color);
        graphics.fill(x + 2, y + 2, x + 4, y + 4, color);
        graphics.fill(x, y + 4, x + 2, y + 6, color);
    }

    private static void dot(GuiGraphics graphics, int x, int y, int color) {
        graphics.fill(x, y, x + 2, y + 2, color);
    }

    /** Gold horizontal separator line. */
    static void separator(GuiGraphics graphics, int x, int y, int width) {
        graphics.fill(x, y, x + width, y + 1, BORDER_DARK);
    }

    /** Scrollbar track + thumb for list columns. Uses the column's right edge. */
    static void scrollbar(
            GuiGraphics graphics,
            int columnX,
            int columnWidth,
            int listY,
            int listHeight,
            int scroll,
            int count,
            int visible) {
        int trackHeight = Math.max(8, listHeight - 4);
        int thumbHeight = Math.max(8, trackHeight * visible / Math.max(1, count));
        int travel = trackHeight - thumbHeight;
        int thumbY = listY + 2 + travel * scroll / Math.max(1, count - visible);
        int trackX = columnX + columnWidth - 5;
        graphics.fill(trackX, listY + 2, trackX + 2, listY + 2 + trackHeight, BORDER_DARK);
        graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, MUTED);
    }

    /** Small key-chord hint: "Alt+H Hold" style with gold key and muted label. */
    static void keyHint(Font font, GuiGraphics graphics, int x, int y, String chord, Component label) {
        String chordText = chord + " ";
        int chordWidth = font.width(chordText);
        graphics.drawString(font, chordText, x, y, GOLD, true);
        graphics.drawString(font, label, x + chordWidth, y, MUTED, false);
    }

    /** Transient command acknowledgement, drawn near the bottom of the screen. */
    static void drawFeedback(
            GuiGraphics graphics, Font font, Component feedback, long feedbackUntil) {
        if (feedback == null || System.currentTimeMillis() >= feedbackUntil) {
            return;
        }
        int width = font.width(feedback);
        int x = (graphics.guiWidth() - width) / 2;
        int y = graphics.guiHeight() - 24;
        graphics.fill(x - 5, y - 4, x + width + 5, y + 10, PANEL);
        outline(graphics, x - 5, y - 4, width + 10, 14, BORDER_DARK);
        graphics.drawString(font, feedback, x, y, WARNING, true);
    }

    static void outline(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y, x + 1, y + height, color);
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }

    static String safe(String value) {
        return value == null || value.isBlank()
                ? I18n.get("gui.millenaire_armies.state.unavailable")
                : value;
    }

    static String optional(String value) {
        return value == null || value.isBlank() ? "" : value;
    }

    static String integer(int value) {
        return Integer.toString(value);
    }

    static String longInteger(long value) {
        return Long.toString(value);
    }

    static String signed(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }

    static String percent(int value) {
        return value + "%";
    }

    static String ratio(int first, int second) {
        return first + " / " + second;
    }

    static int clampScroll(int value, int count, int visible) {
        return Math.max(0, Math.min(value, Math.max(0, count - visible)));
    }

    static int keepVisible(int scroll, int row, int visible) {
        if (row < scroll) return row;
        return row >= scroll + visible ? row - visible + 1 : scroll;
    }

    static int statusColor(int value) {
        return value >= 67 ? GOOD : value >= 34 ? WARNING : BAD;
    }
}

/** Drawable button using only GUI primitives in the parchment palette. */
class ParchmentButton extends net.minecraft.client.gui.components.AbstractButton {
    @FunctionalInterface
    interface OnPress {
        void onPress(ParchmentButton button);
    }

    @FunctionalInterface
    interface GlyphRenderer {
        void render(GuiGraphics graphics, Font font, int x, int y, int areaW, int areaH, int color);
    }

    private final OnPress onPress;
    private final GlyphRenderer glyphRenderer;
    private final int glyphInsetLeft;
    private final int glyphTopHeight;
    private boolean selected;

    ParchmentButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        this(x, y, width, height, message, 0, 0, null, onPress);
    }

    ParchmentButton(int x, int y, int width, int height, Component message,
                    int glyphInsetLeft, int glyphTopHeight,
                    GlyphRenderer glyphRenderer, OnPress onPress) {
        super(x, y, width, height, message);
        this.onPress = onPress;
        this.glyphRenderer = glyphRenderer;
        this.glyphInsetLeft = glyphInsetLeft;
        this.glyphTopHeight = glyphTopHeight;
    }

    void setSelected(boolean selected) {
        this.selected = selected;
    }

    /** Returns true when visually highlighted regardless of active state. */
    boolean isSelected() {
        return selected;
    }

    @Override
    public void onPress() {
        onPress.onPress(this);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        Font font = minecraft.font;
        boolean hoveredOrFocused = isHoveredOrFocused();

        int fillColor;
        int borderColor;
        int textColor;

        if (selected) {
            fillColor = MilitaryUi.CARD_SELECTED;
            borderColor = MilitaryUi.GOLD;
            textColor = MilitaryUi.GOLD;
        } else if (!active) {
            fillColor = MilitaryUi.PANEL_INNER;
            borderColor = MilitaryUi.BORDER_DARK;
            textColor = MilitaryUi.MUTED;
        } else if (hoveredOrFocused) {
            fillColor = MilitaryUi.CARD_HOVER;
            borderColor = MilitaryUi.BORDER;
            textColor = MilitaryUi.GOLD;
        } else {
            fillColor = MilitaryUi.CARD;
            borderColor = MilitaryUi.BORDER_DARK;
            textColor = MilitaryUi.TEXT;
        }

        graphics.fill(getX(), getY(), getX() + width, getY() + height, fillColor);
        MilitaryUi.outline(graphics, getX(), getY(), width, height, borderColor);
        if (selected) {
            graphics.fill(getX(), getY(), getX() + 3, getY() + height, MilitaryUi.GOLD);
        }

        if (glyphRenderer != null) {
            int glyphColor = textColor;
            if (glyphInsetLeft > 0) {
                int glyphAreaW = glyphInsetLeft;
                int glyphX = getX() + 2;
                int glyphY = getY() + Math.max(0, (height - (height - 2)) / 2);
                glyphRenderer.render(graphics, font, glyphX, glyphY, glyphAreaW, height, glyphColor);
            } else if (glyphTopHeight > 0) {
                int glyphX = getX();
                int glyphY = getY();
                glyphRenderer.render(graphics, font, glyphX, glyphY, width, glyphTopHeight, glyphColor);
            }
        }

        Component label = getMessage();
        int textAreaX = getX() + glyphInsetLeft;
        int textAreaY = getY() + glyphTopHeight;
        int textAreaW = width - glyphInsetLeft;
        int textAreaH = height - glyphTopHeight;
        if (textAreaW < 4 || textAreaH < 4) return;

        String labelStr = label.getString();
        int maxTextW = Math.max(4, textAreaW - 2);
        String compactLabel = font.plainSubstrByWidth(labelStr, maxTextW);
        int textWidth = font.width(compactLabel);
        int textX = textAreaX + (textAreaW - textWidth) / 2;
        int textY = textAreaY + (textAreaH - 8) / 2;
        graphics.drawString(font, compactLabel, textX, textY, textColor, false);
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
