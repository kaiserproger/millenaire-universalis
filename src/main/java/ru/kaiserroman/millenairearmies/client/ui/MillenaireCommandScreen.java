package ru.kaiserroman.millenairearmies.client.ui;

import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;
import ru.kaiserroman.millenairearmies.client.ArmyClientMirror;
import ru.kaiserroman.millenairearmies.client.ArmyClientState;

/**
 * Compact war council hub and shared base for the focused military ledgers.
 *
 * <p>Opening the hub is the J-key entry point. From here the player steps into the warband ledger,
 * the recruitment ledger or the realm council; every ledger's Back button returns here. The
 * constructor accepting a preferred warband still lands the player in a useful army-focused view by
 * immediately opening the warband ledger for that warband.
 */
public class MillenaireCommandScreen extends Screen {
    private static final Component TITLE = Component.translatable("gui.millenaire_armies.title");
    private static final Component WAITING = Component.translatable("gui.millenaire_armies.waiting");
    private static final Component DONE = Component.translatable("gui.done");
    private static final Component NO_ARMIES = Component.translatable("gui.millenaire_armies.empty.armies");

    protected static final int ENTRY_ARMIES = 0;
    protected static final int ENTRY_RECRUITMENT = 1;
    protected static final int ENTRY_REALM = 2;
    private static final int ENTRY_COUNT = 3;

    protected static final Component UNKNOWN = Component.literal("?");
    protected static final Component[] ORDER_TYPES = translatedArray("gui.millenaire_armies.order.",
            "hold", "move", "rally", "logistics", "attack", "garrison", "siege", "follow", "guard");
    protected static final Component[] FORMATIONS = translatedArray("gui.millenaire_armies.formation.",
            "line", "column", "wedge", "square", "skirmish");
    protected static final Component[] REALM_ROLES = translatedArray("gui.millenaire_armies.realm.role.",
            "none", "head", "feudal", "governor");
    protected static final Component[] REALM_GOVERNMENTS = translatedArray(
            "gui.millenaire_armies.realm.government.",
            "none",
            "clan_confederation",
            "feudal_monarchy",
            "estate_monarchy",
            "bureaucratic_monarchy",
            "commercial_monarchy",
            "merchant_republic",
            "city_league",
            "citizen_polity",
            "oligarchic_polity",
            "military_autocracy");
    protected static final Component[] RELATIONS = translatedArray("gui.millenaire_armies.relation.",
            "hostile", "neutral", "friendly", "allied", "vassal");

    protected ArmyClientMirror cachedMirror = ArmyClientMirror.EMPTY;
    protected long cachedRevision = Long.MIN_VALUE;
    protected int panelX;
    protected int panelY;
    protected int panelWidth;
    protected int panelHeight;
    protected int syncTicks;
    protected int waitingTicks;

    private final int preferredArmyId;
    private boolean ledgerOpened;
    private int selectedEntry = ENTRY_ARMIES;
    private String hubArmiesDetail = NO_ARMIES.getString();
    private String hubSettlementsDetail = "0";
    private String hubRealmDetail = "";
    private Component hubHint = Component.empty();

    private int lastHintPanelWidth = -1;
    private final java.util.List<FormattedCharSequence>[] cachedHintLines = newArray();

    public MillenaireCommandScreen() {
        super(TITLE);
        preferredArmyId = -1;
    }

    public MillenaireCommandScreen(int preferredArmyId) {
        super(TITLE);
        this.preferredArmyId = preferredArmyId;
        MilitaryLedgerController.seedArmy(preferredArmyId);
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<FormattedCharSequence>[] newArray() {
        return new java.util.List[ENTRY_COUNT];
    }

    protected final void layoutPanel(int maxWidth, int maxHeight, int minWidth, int minHeight, int padding) {
        int availableWidth = Math.max(160, width - padding);
        int availableHeight = Math.max(140, height - padding);
        panelWidth = Math.min(maxWidth, availableWidth);
        panelHeight = Math.min(maxHeight, availableHeight);
        if (availableWidth >= minWidth) {
            panelWidth = Math.max(minWidth, panelWidth);
        }
        if (availableHeight >= minHeight) {
            panelHeight = Math.max(minHeight, panelHeight);
        }
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
    }

    @Override
    protected void init() {
        layoutPanel(560, 280, 330, 220, 16);
        addRenderableWidget(MilitaryUi.parchmentButton(DONE, panelX + panelWidth - 58, panelY + 5, 50, 18,
                ignored -> onClose()));
        invalidateHintCache();
        refreshMirror(true);
    }

    @Override
    public void added() {
        ArmyClientState.current().requestFullSync();
    }

    @Override
    public void tick() {
        super.tick();
        if (preferredArmyId >= 0 && !ledgerOpened) {
            ledgerOpened = true;
            Minecraft.getInstance().setScreen(new ArmyLedgerScreen(preferredArmyId));
        }
        syncTick();
    }

    protected final void syncTick() {
        if (++syncTicks >= 20) {
            syncTicks = 0;
            ArmyClientState.current().requestFullSync();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        refreshMirror(false);
        MilitaryUi.frame(graphics, panelX, panelY, panelWidth, panelHeight);

        MilitaryUi.seal(graphics, panelX + 9, panelY + 9);
        graphics.drawString(font, TITLE, panelX + 18, panelY + 8, MilitaryUi.GOLD, true);
        String context = optional(cachedMirror.realmName());
        if (context.isEmpty()) {
            context = optional(cachedMirror.playerFactionName());
        }
        if (!context.isEmpty()) {
            int doneLeft = panelX + panelWidth - 58;
            int contextMaxRight = doneLeft - 6;
            int contextMaxWidth = contextMaxRight - (panelX + 18 + font.width(TITLE) + 8);
            contextMaxWidth = Math.max(40, contextMaxWidth);
            String compact = font.plainSubstrByWidth(context, contextMaxWidth);
            int contextX = contextMaxRight - font.width(compact);
            if (contextX < panelX + 18 + font.width(TITLE) + 8) {
                contextX = panelX + 18 + font.width(TITLE) + 8;
                compact = font.plainSubstrByWidth(context, contextMaxRight - contextX);
            }
            graphics.drawString(font, compact, contextX, panelY + 9, MilitaryUi.TEXT, true);
        }
        MilitaryUi.separator(graphics, panelX + 9, panelY + 21, panelWidth - 18);

        if (!cachedMirror.isReady()) {
            graphics.drawCenteredString(font, WAITING, width / 2, panelY + panelHeight / 2 - 6, MilitaryUi.MUTED);
            String status = safe(cachedMirror.statusText());
            if (!status.isEmpty()) {
                graphics.drawCenteredString(font, status, width / 2, panelY + panelHeight / 2 + 8, MilitaryUi.MUTED);
            }
        } else {
            graphics.drawString(font, hubHint, panelX + 10, panelY + 31, MilitaryUi.MUTED, false);
            drawMenuEntries(graphics, mouseX, mouseY);
        }

        MilitaryUi.drawFeedback(graphics, font,
                MilitaryLedgerController.feedback(), MilitaryLedgerController.feedbackUntil());
        for (var renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    private void invalidateHintCache() {
        lastHintPanelWidth = -1;
        Arrays.fill(cachedHintLines, null);
    }

    protected void refreshCachedDetail() {
    }

    protected void updateButtons() {
    }

    private void drawMenuEntries(GuiGraphics graphics, int mouseX, int mouseY) {
        boolean horizontal = horizontalEntries();
        int cardWidth = entryWidth();
        int cardHeight = entryHeight();
        int gap = horizontal ? 6 : 4;

        if (lastHintPanelWidth != panelWidth) {
            invalidateHintCache();
            lastHintPanelWidth = panelWidth;
        }

        for (int index = 0; index < ENTRY_COUNT; index++) {
            int x = entryX(index, cardWidth, gap);
            int y = entryY(index, cardHeight, gap);
            boolean selected = index == selectedEntry;
            boolean hovered = inside(mouseX, mouseY, x, y, cardWidth, cardHeight);
            MilitaryUi.card(graphics, x, y, cardWidth, cardHeight, selected, hovered);

            int glyphX = x + cardWidth / 2 - 8;
            int glyphY = y + 12;
            int glyphColor = selected ? MilitaryUi.GOLD : MilitaryUi.TEXT;
            MilitaryUi.entryGlyph(graphics, glyphX, glyphY, index, glyphColor);

            Component title = entryTitle(index);
            int titleColor = selected ? MilitaryUi.GOLD : MilitaryUi.TEXT;
            int titleY = Math.min(y + 33, y + cardHeight - 42);
            int titleXCenter = x + cardWidth / 2;
            graphics.drawCenteredString(font, title, titleXCenter, titleY, titleColor);

            String detail = entryDetail(index);
            int detailY = titleY + (cardHeight >= 100 ? 16 : 10);
            if (detail != null && !detail.isEmpty() && detailY + 8 < y + cardHeight - 10) {
                String compact = font.plainSubstrByWidth(detail, Math.max(40, cardWidth - 16));
                graphics.drawCenteredString(font, compact, titleXCenter, detailY, MilitaryUi.MUTED);
            }

            if (cardHeight >= 84) {
                int hintMaxWidth = Math.max(20, cardWidth - 16);
                var lines = getHintLines(index, hintMaxWidth);
                if (lines != null) {
                    int hintY = detailY + (cardHeight >= 100 ? 22 : 14);
                    int maxHintLines = cardHeight >= 105 ? 3 : 2;
                    for (int lineIdx = 0; lineIdx < lines.size() && lineIdx < maxHintLines; lineIdx++) {
                        if (hintY + lineIdx * 10 + 8 <= y + cardHeight - 10) {
                            graphics.drawCenteredString(font, lines.get(lineIdx), titleXCenter,
                                    hintY + lineIdx * 10, MilitaryUi.MUTED);
                        }
                    }
                }
            }

            if (selected) {
                MilitaryUi.chevron(graphics, x + cardWidth - 13, y + cardHeight - 14, MilitaryUi.GOLD);
            }
        }
        graphics.drawString(font,
                Component.translatable("gui.millenaire_armies.hub.banner"),
                panelX + 10, panelY + panelHeight - 17, MilitaryUi.MUTED, false);
        String controls = Component.translatable("gui.millenaire_armies.hub.controls").getString();
        graphics.drawString(font, controls,
                panelX + panelWidth - 10 - font.width(controls), panelY + panelHeight - 17,
                MilitaryUi.MUTED, false);
    }

    private java.util.List<FormattedCharSequence> getHintLines(int entry, int maxWidth) {
        if (cachedHintLines[entry] == null) {
            cachedHintLines[entry] = font.split(entryHint(entry), maxWidth);
        }
        return cachedHintLines[entry];
    }

    private Component entryTitle(int index) {
        return switch (index) {
            case ENTRY_ARMIES -> Component.translatable("gui.millenaire_armies.hub.armies");
            case ENTRY_RECRUITMENT -> Component.translatable("gui.millenaire_armies.hub.recruitment");
            default -> Component.translatable("gui.millenaire_armies.hub.realm");
        };
    }

    private Component entryHint(int index) {
        return Component.translatable(switch (index) {
            case ENTRY_ARMIES -> "gui.millenaire_armies.hub.armies_hint";
            case ENTRY_RECRUITMENT -> "gui.millenaire_armies.hub.recruitment_hint";
            default -> "gui.millenaire_armies.hub.realm_hint";
        });
    }

    private String entryDetail(int index) {
        return switch (index) {
            case ENTRY_ARMIES -> hubArmiesDetail;
            case ENTRY_RECRUITMENT -> hubSettlementsDetail;
            default -> hubRealmDetail;
        };
    }

    private boolean horizontalEntries() {
        return panelWidth >= 430;
    }

    private int entryWidth() {
        return horizontalEntries() ? (panelWidth - 20 - 12) / 3 : panelWidth - 20;
    }

    private int entryHeight() {
        int available = panelHeight - 84;
        return horizontalEntries() ? available : Math.max(56, available / 3);
    }

    private int entryX(int index, int cardWidth, int gap) {
        return horizontalEntries() ? panelX + 10 + index * (cardWidth + gap) : panelX + 10;
    }

    private int entryY(int index, int cardHeight, int gap) {
        return horizontalEntries() ? panelY + 49 : panelY + 43 + index * (cardHeight + gap);
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private void activateEntry() {
        switch (selectedEntry) {
            case ENTRY_ARMIES -> openArmyLedger();
            case ENTRY_RECRUITMENT -> openRecruitment();
            default -> openRealm();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && cachedMirror.isReady()) {
            int cardWidth = entryWidth();
            int cardHeight = entryHeight();
            int gap = horizontalEntries() ? 6 : 4;
            for (int index = 0; index < ENTRY_COUNT; index++) {
                if (inside(mouseX, mouseY,
                        entryX(index, cardWidth, gap), entryY(index, cardHeight, gap),
                        cardWidth, cardHeight)) {
                    selectedEntry = index;
                    activateEntry();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER)
                && getFocused() != null
                && super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
            int direction = keyCode == GLFW.GLFW_KEY_UP ? -1 : 1;
            selectedEntry = Math.floorMod(selectedEntry + direction, ENTRY_COUNT);
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_SPACE)
                && getFocused() == null) {
            activateEntry();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    protected final void refreshMirror(boolean force) {
        ArmyClientMirror mirror = ArmyClientState.current();
        long revision = mirror.viewVersion();
        if (!force && mirror == cachedMirror && revision == cachedRevision) {
            return;
        }
        cachedMirror = mirror;
        cachedRevision = revision;
        waitingTicks = mirror.isReady() ? 0 : waitingTicks + 1;
        MilitaryLedgerController.validate(mirror);
        if (mirror.isReady()) {
            hubArmiesDetail = mirror.armyCount() == 0
                    ? NO_ARMIES.getString()
                    : Component.translatable("gui.millenaire_armies.hub.armies_detail",
                            MilitaryUi.integer(mirror.armyCount()),
                            MilitaryUi.integer(mirror.totalUnitCount())).getString();
            hubSettlementsDetail = Component.translatable(
                    mirror.settlementCount() == 1
                            ? "gui.millenaire_armies.hub.settlement_one"
                            : "gui.millenaire_armies.hub.settlement_many",
                    MilitaryUi.integer(mirror.settlementCount())).getString();
            hubRealmDetail = mirror.realmFounded()
                    ? safe(mirror.realmName())
                    : Component.translatable("gui.millenaire_armies.hub.realm_not_founded").getString();
            if (mirror.settlementCount() == 0) {
                hubHint = Component.translatable("gui.millenaire_armies.hub.no_settlement");
            } else if (!mirror.realmFounded()) {
                hubHint = Component.translatable("gui.millenaire_armies.hub.no_realm");
            } else {
                hubHint = Component.translatable("gui.millenaire_armies.hub.realm_ready",
                        safe(mirror.realmName()), MilitaryUi.integer(mirror.realmTaxRate()));
            }
        }
        Component ack = MilitaryLedgerController.pollAcknowledgement(mirror);
        if (ack != null) {
            hubHint = ack;
        }
        refreshCachedDetail();
        updateButtons();
    }

    protected final void addLedgerTabs(int activeEntry) {
        int gap = 4;
        int x = panelX + 10;
        int y = panelY + 28;
        int tabWidth = Math.max(68, (panelWidth - 20 - gap * 2) / 3);
        var armies = addRenderableWidget(MilitaryUi.parchmentButton(entryTitle(ENTRY_ARMIES),
                x, y, tabWidth, 18, ignored -> openArmyLedger()));
        var recruitment = addRenderableWidget(MilitaryUi.parchmentButton(entryTitle(ENTRY_RECRUITMENT),
                x + tabWidth + gap, y, tabWidth, 18, ignored -> openRecruitment()));
        var realm = addRenderableWidget(MilitaryUi.parchmentButton(entryTitle(ENTRY_REALM),
                x + (tabWidth + gap) * 2, y, tabWidth, 18, ignored -> openRealm()));
        armies.active = activeEntry != ENTRY_ARMIES;
        recruitment.active = activeEntry != ENTRY_RECRUITMENT;
        realm.active = activeEntry != ENTRY_REALM;
    }

    protected final void openHub() {
        Minecraft.getInstance().setScreen(new MillenaireCommandScreen());
    }

    protected final void openArmyLedger() {
        Minecraft.getInstance().setScreen(new ArmyLedgerScreen(
                MilitaryLedgerController.hasSelectedArmy ? MilitaryLedgerController.selectedArmyId : -1));
    }

    protected final void openRecruitment() {
        Minecraft.getInstance().setScreen(new RecruitmentLedgerScreen());
    }

    protected final void openRealm() {
        Minecraft.getInstance().setScreen(new RealmCouncilScreen());
    }

    public static void resetNavigationState() {
        MilitaryLedgerController.reset();
    }

    protected void drawLedgerTitle(
            GuiGraphics graphics, int x, int y, int width, Component title) {
        MilitaryUi.seal(graphics, x, y + 1);
        graphics.drawString(font, title, x + 9, y, MilitaryUi.GOLD, true);
        graphics.fill(x, y + 12, x + width, y + 13, MilitaryUi.BORDER_DARK);
    }

    protected void drawPair(
            GuiGraphics graphics, int x, int y, Component label, String value, int valueColor) {
        drawPair(graphics, x, y, label, value, valueColor, panelX + panelWidth - 10 - x);
    }

    protected void drawPair(
            GuiGraphics graphics,
            int x,
            int y,
            Component label,
            String value,
            int valueColor,
            int maxWidth) {
        graphics.drawString(font, label, x, y, MilitaryUi.MUTED, false);
        int valueX = x + Math.min(96, Math.max(52, font.width(label) + 8));
        int valueWidth = Math.max(16, maxWidth - (valueX - x));
        graphics.drawString(font, font.plainSubstrByWidth(value, valueWidth), valueX, y, valueColor, false);
    }

    protected void drawPair(GuiGraphics graphics, int x, int y, Component label, String value) {
        drawPair(graphics, x, y, label, value, MilitaryUi.TEXT);
    }

    protected int drawWrapped(
            GuiGraphics graphics,
            Component text,
            int x,
            int y,
            int maxWidth,
            int color,
            int maxLines) {
        int lineY = y;
        int lines = 0;
        for (var line : font.split(text, Math.max(20, maxWidth))) {
            if (lines++ >= maxLines) {
                break;
            }
            graphics.drawString(font, line, x, lineY, color, false);
            lineY += 10;
        }
        return lineY;
    }

    protected static Component orderText(int code) {
        return arrayText(ORDER_TYPES, code);
    }

    protected static Component formationText(int code) {
        return arrayText(FORMATIONS, code);
    }

    protected static Component realmRoleText(int code) {
        return arrayText(REALM_ROLES, code);
    }

    protected static Component realmGovernmentText(int code) {
        return arrayText(REALM_GOVERNMENTS, code);
    }

    protected static Component relationText(byte code) {
        return arrayText(RELATIONS, code);
    }

    protected static Component garrisonStatus(int code) {
        return Component.translatable(switch (code) {
            case 0 -> "gui.millenaire_armies.garrison.supplied";
            case 1 -> "gui.millenaire_armies.garrison.low";
            case 2 -> "gui.millenaire_armies.garrison.starving";
            default -> "gui.millenaire_armies.garrison.unknown";
        });
    }

    protected static String blockPosition(long packed) {
        BlockPos position = BlockPos.of(packed);
        return position.getX() + ", " + position.getY() + ", " + position.getZ();
    }

    protected static Component arrayText(Component[] values, int code) {
        return code >= 0 && code < values.length ? values[code] : UNKNOWN;
    }

    protected static Component[] translatedArray(String prefix, String... suffixes) {
        Component[] result = new Component[suffixes.length];
        for (int i = 0; i < suffixes.length; i++) {
            result[i] = Component.translatable(prefix + suffixes[i]);
        }
        return result;
    }

    protected static String safe(String value) {
        return MilitaryUi.safe(value);
    }

    protected static String optional(String value) {
        return MilitaryUi.optional(value);
    }
}
