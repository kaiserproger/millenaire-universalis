package ru.kaiserroman.millenairearmies.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import ru.kaiserroman.millenaire.realm.DiplomaticStatus;
import ru.kaiserroman.millenaire.realm.WarGoal;
import ru.kaiserroman.millenairearmies.client.ArmyClientMirror;

/**
 * Realm council: controlled settlements, realm foundation, role/capital/government, tax, treasury,
 * resources and a compact diplomacy summary. Council actions appear only where the player has the
 * authority for them.
 */
public final class RealmCouncilScreen extends MillenaireCommandScreen {
    private static final Component TITLE = Component.translatable("gui.millenaire_armies.ledger.title.realm");
    private static final Component BACK = Component.translatable("gui.millenaire_armies.back");
    private static final Component SECTION_SETTLEMENTS = Component.translatable(
            "gui.millenaire_armies.section.settlements");
    private static final Component SECTION_GOVERNANCE = Component.translatable(
            "gui.millenaire_armies.section.governance");
    private static final Component SECTION_ECONOMY = Component.translatable(
            "gui.millenaire_armies.section.economy");
    private static final Component LABEL_ROLE = Component.translatable("gui.millenaire_armies.label.role");
    private static final Component LABEL_GOVERNMENT = Component.translatable("gui.millenaire_armies.label.government");
    private static final Component LABEL_CAPITAL = Component.translatable("gui.millenaire_armies.label.capital");
    private static final Component LABEL_CONTROLLED = Component.translatable(
            "gui.millenaire_armies.label.controlled_settlement");
    private static final Component LABEL_SETTLEMENTS = Component.translatable(
            "gui.millenaire_armies.label.settlements");
    private static final Component LABEL_POPULATION = Component.translatable(
            "gui.millenaire_armies.label.population");
    private static final Component LABEL_TREASURY = Component.translatable("gui.millenaire_armies.label.treasury");
    private static final Component LABEL_TAX_RATE = Component.translatable("gui.millenaire_armies.label.tax_rate");
    private static final Component LABEL_CAPTURES = Component.translatable("gui.millenaire_armies.label.captures");
    private static final Component LABEL_RESOURCES = Component.translatable("gui.millenaire_armies.label.resources");
    private static final Component REALM_NOT_FOUNDED = Component.translatable(
            "gui.millenaire_armies.realm.not_founded");
    private static final Component REALM_FOUND_HINT = Component.translatable(
            "gui.millenaire_armies.realm.found_hint");
    private static final Component REALM_NO_CAPITAL = Component.translatable(
            "gui.millenaire_armies.realm.no_capital");
    private static final Component REALM_CAPTURE_LOCKED = Component.translatable(
            "gui.millenaire_armies.realm.capture_locked");
    private static final Component EMPTY_SETTLEMENTS = Component.translatable(
            "gui.millenaire_armies.empty.settlements");
    private static final Component EMPTY_SETTLEMENTS_HINT = Component.translatable(
            "gui.millenaire_armies.ledger.no_settlement_hint");

    private ParchmentButton foundRealmButton;
    private ParchmentButton taxLessButton;
    private ParchmentButton taxMoreButton;
    private MilitaryUi.RealmPlacement realm;
    private int settlementScroll;
    private final String[] diplomacyLines = new String[3];
    private int diplomacyLineCount;

    public RealmCouncilScreen() {
        super();
    }

    @Override
    protected void init() {
        layoutPanel(640, 384, 360, 278, 16);
        addRenderableWidget(MilitaryUi.parchmentButton(BACK, panelX + panelWidth - 58, panelY + 5, 50, 18,
                ignored -> openHub()));
        addLedgerTabs(ENTRY_REALM);

        realm = MilitaryUi.computeRealmLayout(panelX, panelY, panelWidth, panelHeight);

        foundRealmButton = addRenderableWidget(
                MilitaryUi.parchmentButton("gui.millenaire_armies.action.found_realm",
                        panelX + 10, realm.actionY(), panelWidth - 20, 22,
                        ignored -> MilitaryLedgerController.foundRealm(cachedMirror)));
        int taxLeftWidth = Math.max(120, (panelWidth - 26) / 2);
        int taxRightWidth = panelWidth - 26 - taxLeftWidth;
        taxLessButton = addRenderableWidget(
                MilitaryUi.parchmentButton("gui.millenaire_armies.action.tax_down",
                        panelX + 10, realm.actionY(), taxLeftWidth, 22,
                        ignored -> MilitaryLedgerController.adjustTax(cachedMirror, -5)));
        taxMoreButton = addRenderableWidget(
                MilitaryUi.parchmentButton("gui.millenaire_armies.action.tax_up",
                        panelX + 16 + taxLeftWidth, realm.actionY(), taxRightWidth, 22,
                        ignored -> MilitaryLedgerController.adjustTax(cachedMirror, 5)));

        refreshMirror(true);
        updateButtons();
    }

    @Override
    public void tick() {
        syncTick();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        refreshMirror(false);
        MilitaryUi.frame(graphics, panelX, panelY, panelWidth, panelHeight);
        drawLedgerTitle(graphics, panelX + 10, panelY + 8, panelWidth - 20, TITLE);

        if (!cachedMirror.isReady()) {
            graphics.drawCenteredString(font,
                    Component.translatable("gui.millenaire_armies.waiting"),
                    width / 2, realm.contentTop()
                            + (realm.contentBottom() - realm.contentTop()) / 2 - 6,
                    MilitaryUi.MUTED);
        } else {
            drawSettlementList(graphics, mouseX, mouseY);
            if (cachedMirror.realmFounded()) {
                drawRealmDetail(graphics);
            } else {
                drawRealmFoundation(graphics);
            }
        }

        MilitaryUi.drawFeedback(graphics, font,
                MilitaryLedgerController.feedback(), MilitaryLedgerController.feedbackUntil());
        for (var renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    private void drawSettlementList(GuiGraphics graphics, int mouseX, int mouseY) {
        int listX = panelX + 10;
        int count = cachedMirror.settlementCount();
        int listHeight = realm.contentBottom() - realm.contentTop();
        MilitaryUi.well(graphics, listX, realm.contentTop(), realm.listWidth(), listHeight);
        MilitaryUi.section(font, graphics, SECTION_SETTLEMENTS, listX + 7,
                realm.contentTop() + 6, realm.listWidth() - 14);
        int listY = realm.contentTop() + 20;
        int scrollHeight = realm.contentBottom() - listY - 3;
        int visible = Math.max(1, scrollHeight / MilitaryUi.ROW_HEIGHT);
        if (count == 0) {
            graphics.drawCenteredString(font, EMPTY_SETTLEMENTS,
                    listX + realm.listWidth() / 2,
                    realm.contentTop() + (realm.contentBottom() - realm.contentTop()) / 2 - 8,
                    MilitaryUi.MUTED);
            drawWrapped(graphics, EMPTY_SETTLEMENTS_HINT, listX + 6,
                    realm.contentTop() + (realm.contentBottom() - realm.contentTop()) / 2 + 4,
                    realm.listWidth() - 12, MilitaryUi.MUTED, 3);
            return;
        }
        settlementScroll = MilitaryUi.clampScroll(settlementScroll, count, visible);
        int end = Math.min(count, settlementScroll + visible);
        graphics.enableScissor(listX + 2, listY, listX + realm.listWidth() - 2,
                realm.contentBottom() - 1);
        for (int index = settlementScroll; index < end; index++) {
            int y = listY + (index - settlementScroll) * MilitaryUi.ROW_HEIGHT;
            boolean selected = MilitaryLedgerController.findSettlementIndex(cachedMirror) == index;
            boolean hovered = mouseX >= listX + 2 && mouseX < listX + realm.listWidth() - 2
                    && mouseY >= y && mouseY < y + MilitaryUi.ROW_HEIGHT;
            MilitaryUi.row(font, graphics, listX + 2, y, realm.listWidth() - 4, selected, hovered,
                    safe(cachedMirror.settlementName(index)),
                    Component.translatable("gui.millenaire_armies.summary.settlement",
                            cachedMirror.settlementPopulation(index),
                            cachedMirror.settlementAvailableRecruitCount(index)).getString(),
                    selected);
        }
        graphics.disableScissor();
        if (count > visible) {
            MilitaryUi.scrollbar(graphics, listX, realm.listWidth(),
                    listY, scrollHeight, settlementScroll, count, visible);
        }
    }

    private void drawRealmFoundation(GuiGraphics graphics) {
        int cardHeight = realm.contentBottom() - realm.contentTop();
        MilitaryUi.card(graphics, realm.detailX(), realm.contentTop(),
                realm.detailWidth(), cardHeight, false, false);
        int x = realm.detailX() + 12;
        int available = realm.detailWidth() - 24;
        int y = realm.contentTop() + 12;
        int bottom = realm.contentBottom();
        boolean hasSettlement = MilitaryLedgerController.hasSettlement;

        graphics.drawString(font,
                font.plainSubstrByWidth(REALM_NOT_FOUNDED.getString(), available),
                x, y, MilitaryUi.GOLD, true);

        int reservedBottom = hasSettlement ? 49 : 32;
        int maxTop = bottom - reservedBottom;

        int hintStartY = y + 16;
        int availableForHint = Math.max(20, maxTop - hintStartY);
        int maxHintLines = Math.min(4, availableForHint / 10);
        y = drawWrapped(graphics, REALM_FOUND_HINT, x, hintStartY, available,
                MilitaryUi.MUTED, maxHintLines) + 8;

        if (y + 30 < maxTop) {
            MilitaryUi.separator(graphics, x, y, available);
            y += 6;
            int selected = MilitaryLedgerController.findSettlementIndex(cachedMirror);
            int maxCandidateLines = Math.min(3, (maxTop - y) / 10);
            if (selected < 0) {
                y = drawWrapped(graphics, REALM_NO_CAPITAL, x, y, available,
                        MilitaryUi.TEXT, maxCandidateLines) + 6;
            } else {
                Component candidate = Component.translatable(
                        "gui.millenaire_armies.realm.capital_candidate",
                        safe(cachedMirror.settlementName(selected)),
                        cachedMirror.settlementPopulation(selected));
                y = drawWrapped(graphics, candidate, x, y, available,
                        MilitaryUi.TEXT, maxCandidateLines) + 6;
            }
        }

        int captureY = bottom - (hasSettlement ? 41 : 24);
        MilitaryUi.separator(graphics, x, captureY - 8, available);
        drawWrapped(graphics, REALM_CAPTURE_LOCKED, x, captureY, available, MilitaryUi.MUTED, 2);

        if (hasSettlement) {
            String hint = font.plainSubstrByWidth(
                    Component.translatable("gui.millenaire_armies.ledger.no_realm_hint").getString(),
                    available);
            graphics.drawString(font, hint, x, bottom - 15, MilitaryUi.WARNING, false);
        }
    }

    private void drawRealmDetail(GuiGraphics graphics) {
        ArmyClientMirror mirror = cachedMirror;
        int cardHeight = realm.contentBottom() - realm.contentTop();
        MilitaryUi.card(graphics, realm.detailX(), realm.contentTop(),
                realm.detailWidth(), cardHeight, false, false);
        int x = realm.detailX() + 10;
        int innerWidth = realm.detailWidth() - 20;
        graphics.drawString(font,
                font.plainSubstrByWidth(safe(mirror.realmName()), innerWidth),
                x, realm.contentTop() + 8, MilitaryUi.GOLD, true);
        MilitaryUi.separator(graphics, x, realm.contentTop() + 21, innerWidth);

        if (!realm.compact()) {
            drawRealmDetailWide(graphics, mirror, x, innerWidth, cardHeight);
        } else {
            drawRealmDetailCompact(graphics, mirror, x, innerWidth, cardHeight);
        }
    }

    private void drawRealmDetailWide(
            GuiGraphics graphics, ArmyClientMirror mirror, int x, int innerWidth, int cardHeight) {
        int gap = 12;
        int columnWidth = (innerWidth - gap) / 2;
        int rightX = x + columnWidth + gap;
        int governanceY = realm.contentTop() + 27;
        MilitaryUi.section(font, graphics, SECTION_GOVERNANCE, x, governanceY, columnWidth);
        int y = governanceY + 15;
        int step = 12;
        drawPair(graphics, x, y, LABEL_ROLE,
                realmRoleText(mirror.realmRoleCode()).getString(), MilitaryUi.TEXT, columnWidth);
        y += step;
        drawPair(graphics, x, y, LABEL_GOVERNMENT,
                realmGovernmentText(mirror.realmGovernmentCode()).getString(), MilitaryUi.TEXT, columnWidth);
        y += step;
        drawPair(graphics, x, y, LABEL_CAPITAL,
                safe(mirror.realmCapitalName()), MilitaryUi.TEXT, columnWidth);
        y += step;
        drawPair(graphics, x, y, LABEL_CONTROLLED,
                safe(mirror.realmControlledSettlementName()), MilitaryUi.TEXT, columnWidth);
        y += step;
        drawPair(graphics, x, y, LABEL_SETTLEMENTS,
                MilitaryUi.integer(mirror.realmSettlementCount()), MilitaryUi.TEXT, columnWidth);
        y += step;
        drawPair(graphics, x, y, LABEL_POPULATION,
                MilitaryUi.integer(mirror.realmPopulation()), MilitaryUi.TEXT, columnWidth);

        int economyY = governanceY;
        MilitaryUi.section(font, graphics, SECTION_ECONOMY, rightX, economyY, columnWidth);
        int ey = economyY + 15;
        drawPair(graphics, rightX, ey, LABEL_TREASURY,
                MilitaryUi.longInteger(mirror.realmTreasury()), MilitaryUi.TEXT, columnWidth);
        ey += step;
        drawPair(graphics, rightX, ey, LABEL_TAX_RATE,
                MilitaryUi.percent(mirror.realmTaxRate()), MilitaryUi.TEXT, columnWidth);
        ey += step;
        drawPair(graphics, rightX, ey, LABEL_CAPTURES,
                MilitaryUi.integer(mirror.realmCapturedSettlementCount()), MilitaryUi.TEXT, columnWidth);
        ey += step;
        drawPair(graphics, rightX, ey, LABEL_RESOURCES,
                Component.translatable("gui.millenaire_armies.realm.resources_compact",
                        mirror.realmFood(), mirror.realmIron(),
                        mirror.realmLeather(), mirror.realmArrows()).getString(),
                MilitaryUi.MUTED, columnWidth);

        if (diplomacyLineCount > 0 && cardHeight >= 170) {
            int diplomacyY = realm.contentBottom() - diplomacyLineCount * 11 - 18;
            MilitaryUi.separator(graphics, x, diplomacyY - 6, innerWidth);
            MilitaryUi.section(font, graphics,
                    Component.translatable("gui.millenaire_armies.section.diplomacy"),
                    x, diplomacyY, innerWidth);
            for (int line = 0; line < diplomacyLineCount; line++) {
                String compact = font.plainSubstrByWidth(diplomacyLines[line], innerWidth);
                graphics.drawString(font, compact, x,
                        diplomacyY + 13 + line * 11, MilitaryUi.MUTED, false);
            }
        }
    }

    private void drawRealmDetailCompact(
            GuiGraphics graphics, ArmyClientMirror mirror, int x, int innerWidth, int cardHeight) {
        int y = realm.contentTop() + 27;
        MilitaryUi.section(font, graphics, SECTION_GOVERNANCE, x, y, innerWidth);
        String government = realmRoleText(mirror.realmRoleCode()).getString()
                + " · " + realmGovernmentText(mirror.realmGovernmentCode()).getString();
        graphics.drawString(font, font.plainSubstrByWidth(government, innerWidth),
                x, y + 15, MilitaryUi.TEXT, false);
        String capital = LABEL_CAPITAL.getString() + ": " + safe(mirror.realmCapitalName())
                + " · " + LABEL_POPULATION.getString() + ' ' + mirror.realmPopulation();
        graphics.drawString(font, font.plainSubstrByWidth(capital, innerWidth),
                x, y + 28, MilitaryUi.MUTED, false);

        int economyY = y + 47;
        MilitaryUi.separator(graphics, x, economyY - 6, innerWidth);
        MilitaryUi.section(font, graphics, SECTION_ECONOMY, x, economyY, innerWidth);
        String economy = LABEL_TREASURY.getString() + ' ' + mirror.realmTreasury()
                + " · " + LABEL_TAX_RATE.getString() + ' ' + mirror.realmTaxRate() + "%";
        graphics.drawString(font, font.plainSubstrByWidth(economy, innerWidth),
                x, economyY + 15, MilitaryUi.TEXT, false);
        String resources = Component.translatable("gui.millenaire_armies.realm.resources_compact",
                mirror.realmFood(), mirror.realmIron(),
                mirror.realmLeather(), mirror.realmArrows()).getString();
        graphics.drawString(font, font.plainSubstrByWidth(resources, innerWidth),
                x, economyY + 28, MilitaryUi.MUTED, false);
        if (diplomacyLineCount > 0 && cardHeight >= 145) {
            String diplomacy = font.plainSubstrByWidth(diplomacyLines[0], innerWidth);
            graphics.drawString(font, diplomacy, x, realm.contentBottom() - 15, MilitaryUi.MUTED, false);
        }
    }

    @Override
    protected void updateButtons() {
        boolean ready = cachedMirror.isReady();
        boolean realmFounded = cachedMirror.realmFounded();
        boolean realmHead = MilitaryLedgerController.realmHead(cachedMirror);
        foundRealmButton.active = ready && !realmFounded && MilitaryLedgerController.hasSettlement;
        foundRealmButton.visible = !realmFounded;
        taxLessButton.active = ready && realmFounded && realmHead && cachedMirror.realmTaxRate() > 0;
        taxLessButton.visible = realmFounded && realmHead;
        taxMoreButton.active = ready && realmFounded && realmHead && cachedMirror.realmTaxRate() < 25;
        taxMoreButton.visible = realmFounded && realmHead;
    }

    @Override
    protected void refreshCachedDetail() {
        diplomacyLineCount = Math.min(diplomacyLines.length, cachedMirror.realmRelationCount());
        for (int line = 0; line < diplomacyLines.length; line++) {
            diplomacyLines[line] = "";
        }
        for (int index = 0; index < diplomacyLineCount; index++) {
            DiplomaticStatus status = diplomaticStatus(cachedMirror.realmRelationStatusCode(index));
            WarGoal goal = warGoal(cachedMirror.realmRelationWarGoalCode(index));
            diplomacyLines[index] = Component.translatable(
                            "gui.millenaire_armies.realm.diplomacy.line",
                            safe(cachedMirror.realmRelationName(index)),
                            Component.translatable(
                                    "gui.millenaire_armies.realm.status."
                                            + status.name().toLowerCase(java.util.Locale.ROOT)),
                            Component.translatable(
                                    "gui.millenaire_armies.realm.war_goal."
                                            + goal.name().toLowerCase(java.util.Locale.ROOT)),
                            signed(cachedMirror.realmRelationWarScore(index)),
                            cachedMirror.realmRelationExhaustion(index),
                            cachedMirror.realmRelationGrievances(index),
                            cachedMirror.realmRelationTrust(index))
                    .getString();
        }
    }

    private static DiplomaticStatus diplomaticStatus(byte code) {
        int value = Byte.toUnsignedInt(code);
        return value < DiplomaticStatus.values().length
                ? DiplomaticStatus.values()[value]
                : DiplomaticStatus.PEACE;
    }

    private static WarGoal warGoal(byte code) {
        int value = Byte.toUnsignedInt(code);
        return value < WarGoal.values().length ? WarGoal.values()[value] : WarGoal.NONE;
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && cachedMirror.isReady() && cachedMirror.settlementCount() > 0
                && mouseX >= panelX + 10 && mouseX < panelX + 10 + realm.listWidth()
                && mouseY >= realm.contentTop() + 20 && mouseY < realm.contentBottom() - 1) {
            int listY = realm.contentTop() + 20;
            int scrollHeight = realm.contentBottom() - listY - 3;
            int visible = Math.max(1, scrollHeight / MilitaryUi.ROW_HEIGHT);
            int row = settlementScroll + ((int) mouseY - listY) / MilitaryUi.ROW_HEIGHT;
            if (row >= 0 && row < cachedMirror.settlementCount() && row < settlementScroll + visible) {
                MilitaryLedgerController.selectSettlement(cachedMirror, row);
                refreshMirror(true);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= panelX + 10 && mouseX < panelX + 10 + realm.listWidth()
                && mouseY >= realm.contentTop() && mouseY < realm.contentBottom()) {
            int direction = scrollY > 0.0D ? -1 : scrollY < 0.0D ? 1 : 0;
            int listY = realm.contentTop() + 20;
            int scrollHeight = realm.contentBottom() - listY - 3;
            int visible = Math.max(1, scrollHeight / MilitaryUi.ROW_HEIGHT);
            settlementScroll = MilitaryUi.clampScroll(
                    settlementScroll + direction, cachedMirror.settlementCount(), visible);
            return direction != 0;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if ((keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER)
                && getFocused() != null
                && super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if ((keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN)
                && cachedMirror.settlementCount() > 0) {
            int current = Math.max(0, MilitaryLedgerController.findSettlementIndex(cachedMirror));
            int direction = keyCode == GLFW.GLFW_KEY_UP ? -1 : 1;
            int next = Math.max(0, Math.min(cachedMirror.settlementCount() - 1, current + direction));
            MilitaryLedgerController.selectSettlement(cachedMirror, next);
            int listY = realm.contentTop() + 20;
            int scrollHeight = realm.contentBottom() - listY - 3;
            int visible = Math.max(1, scrollHeight / MilitaryUi.ROW_HEIGHT);
            settlementScroll = MilitaryUi.keepVisible(settlementScroll, next, visible);
            refreshMirror(true);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        openHub();
    }
}
