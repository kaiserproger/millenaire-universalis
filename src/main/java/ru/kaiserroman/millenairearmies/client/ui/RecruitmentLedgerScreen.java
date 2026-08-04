package ru.kaiserroman.millenairearmies.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import ru.kaiserroman.millenairearmies.client.ArmyClientMirror;
import ru.kaiserroman.millenairearmies.network.ArmiesProtocol;

/**
 * Recruitment ledger: controlled settlements, their real residents, and the create-warband and
 * recruit-into-warband actions. Recruitment never invents a target warband; the player cycles to one
 * or creates it first.
 */
public final class RecruitmentLedgerScreen extends MillenaireCommandScreen {
    private static final Component TITLE = Component.translatable("gui.millenaire_armies.ledger.title.recruitment");
    private static final Component BACK = Component.translatable("gui.millenaire_armies.back");
    private static final Component SECTION_SETTLEMENTS = Component.translatable(
            "gui.millenaire_armies.section.settlements");
    private static final Component SECTION_RECRUITS = Component.translatable(
            "gui.millenaire_armies.section.recruits");
    private static final Component EMPTY_SETTLEMENTS = Component.translatable(
            "gui.millenaire_armies.empty.settlements");
    private static final Component EMPTY_SETTLEMENTS_HINT = Component.translatable(
            "gui.millenaire_armies.ledger.no_settlement_hint");
    private static final Component EMPTY_RECRUITS = Component.translatable(
            "gui.millenaire_armies.empty.recruits");
    private static final Component SELECT_SETTLEMENT = Component.translatable(
            "gui.millenaire_armies.ledger.select_settlement");
    private static final Component NO_TARGET_ARMY = Component.translatable(
            "gui.millenaire_armies.ledger.no_target_army");
    private static final Component SELECT_RECRUITS = Component.translatable(
            "gui.millenaire_armies.recruit.select_recruits");
    private static final Component CYCLE = Component.translatable("gui.millenaire_armies.ledger.cycle");

    private ParchmentButton createArmyButton;
    private ParchmentButton recruitButton;
    private ParchmentButton cycleButton;
    private MilitaryUi.RecruitmentPlacement recruitment;
    private int rosterColumn;
    private int settlementScroll;
    private int recruitScroll;

    public RecruitmentLedgerScreen() {
        super();
    }

    @Override
    protected void init() {
        layoutPanel(640, 384, 360, 278, 16);
        addRenderableWidget(MilitaryUi.parchmentButton(BACK, panelX + panelWidth - 58, panelY + 5, 50, 18,
                ignored -> openHub()));
        addLedgerTabs(ENTRY_RECRUITMENT);

        recruitment = MilitaryUi.computeRecruitmentLayout(panelX, panelY, panelWidth, panelHeight);

        int gap = 6;
        int createWidth = Math.max(104, Math.min(140, (panelWidth - 26) / 3));
        int recruitWidth = panelWidth - 26 - createWidth;
        createArmyButton = addRenderableWidget(
                MilitaryUi.parchmentButton("gui.millenaire_armies.action.create_army",
                        panelX + 10, recruitment.actionY(), createWidth, 22,
                        ignored -> MilitaryLedgerController.createArmy(cachedMirror)));
        recruitButton = addRenderableWidget(
                MilitaryUi.parchmentButton(
                        Component.translatable("gui.millenaire_armies.action.recruit"),
                        panelX + 16 + createWidth, recruitment.actionY(), recruitWidth, 22,
                        ignored -> runPrimaryRecruitAction()));
        cycleButton = addRenderableWidget(
                MilitaryUi.parchmentButton(CYCLE,
                        panelX + panelWidth - 10 - 78, recruitment.statusCardY() + 4, 78, 18,
                        ignored -> {
                            MilitaryLedgerController.cycleTargetArmy(cachedMirror);
                            refreshMirror(true);
                        }));

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
                    width / 2, recruitment.contentTop()
                            + (recruitment.contentBottom() - recruitment.contentTop()) / 2 - 6,
                    MilitaryUi.MUTED);
        } else {
            drawSettlementList(graphics, mouseX, mouseY);
            drawRecruitList(graphics, mouseX, mouseY);
        }
        MilitaryUi.card(graphics, panelX + 10, recruitment.statusCardY(),
                panelWidth - 20, recruitment.statusCardHeight(), false, false);
        drawTargetLine(graphics);
        drawReadyLine(graphics);

        MilitaryUi.drawFeedback(graphics, font,
                MilitaryLedgerController.feedback(), MilitaryLedgerController.feedbackUntil());
        for (var renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    private void drawSettlementList(GuiGraphics graphics, int mouseX, int mouseY) {
        int listX = panelX + 10;
        int count = cachedMirror.settlementCount();
        int listHeight = recruitment.contentBottom() - recruitment.contentTop();
        MilitaryUi.well(graphics, listX, recruitment.contentTop(), recruitment.listWidth(), listHeight);
        if (rosterColumn == 0) {
            MilitaryUi.outline(graphics, listX, recruitment.contentTop(),
                    recruitment.listWidth(), listHeight, MilitaryUi.GOLD);
        }
        MilitaryUi.section(font, graphics, SECTION_SETTLEMENTS, listX + 7,
                recruitment.contentTop() + 6, recruitment.listWidth() - 14);
        int listY = recruitment.contentTop() + 20;
        int scrollHeight = recruitment.contentBottom() - listY - 3;
        int visible = Math.max(1, scrollHeight / MilitaryUi.ROW_HEIGHT);
        if (count == 0) {
            graphics.drawCenteredString(font, EMPTY_SETTLEMENTS,
                    listX + recruitment.listWidth() / 2,
                    recruitment.contentTop() + (recruitment.contentBottom() - recruitment.contentTop()) / 2 - 8,
                    MilitaryUi.MUTED);
            drawWrapped(graphics, EMPTY_SETTLEMENTS_HINT, listX + 6,
                    recruitment.contentTop() + (recruitment.contentBottom() - recruitment.contentTop()) / 2 + 4,
                    recruitment.listWidth() - 12, MilitaryUi.MUTED, 3);
            return;
        }
        settlementScroll = MilitaryUi.clampScroll(settlementScroll, count, visible);
        int end = Math.min(count, settlementScroll + visible);
        graphics.enableScissor(listX + 2, listY, listX + recruitment.listWidth() - 2,
                recruitment.contentBottom() - 1);
        for (int index = settlementScroll; index < end; index++) {
            int y = listY + (index - settlementScroll) * MilitaryUi.ROW_HEIGHT;
            boolean selected = MilitaryLedgerController.findSettlementIndex(cachedMirror) == index;
            boolean hovered = mouseX >= listX + 2 && mouseX < listX + recruitment.listWidth() - 2
                    && mouseY >= y && mouseY < y + MilitaryUi.ROW_HEIGHT;
            String summaryKey = cachedMirror.settlementControlled(index)
                    ? "gui.millenaire_armies.summary.settlement.controlled"
                    : "gui.millenaire_armies.summary.settlement.independent";
            MilitaryUi.row(font, graphics, listX + 2, y, recruitment.listWidth() - 4, selected, hovered,
                    safe(cachedMirror.settlementName(index)),
                    Component.translatable(summaryKey,
                            cachedMirror.settlementPopulation(index),
                            cachedMirror.settlementAvailableRecruitCount(index)).getString(),
                    selected);
        }
        graphics.disableScissor();
        if (count > visible) {
            MilitaryUi.scrollbar(graphics, listX, recruitment.listWidth(),
                    listY, scrollHeight, settlementScroll, count, visible);
        }
    }

    private void drawRecruitList(GuiGraphics graphics, int mouseX, int mouseY) {
        int rightWidth = recruitment.rightWidth();
        MilitaryUi.well(graphics, recruitment.rightX(), recruitment.contentTop(),
                rightWidth, recruitment.contentBottom() - recruitment.contentTop());
        if (rosterColumn == 1) {
            MilitaryUi.outline(graphics, recruitment.rightX(), recruitment.contentTop(),
                    rightWidth, recruitment.contentBottom() - recruitment.contentTop(), MilitaryUi.GOLD);
        }
        MilitaryUi.section(font, graphics, SECTION_RECRUITS, recruitment.rightX() + 7,
                recruitment.contentTop() + 6, rightWidth - 14);
        int listY = recruitment.contentTop() + 20;
        int scrollHeight = recruitment.contentBottom() - listY - 3;
        int visible = Math.max(1, scrollHeight / MilitaryUi.ROW_HEIGHT);
        if (!MilitaryLedgerController.hasSettlement) {
            graphics.drawCenteredString(font, SELECT_SETTLEMENT,
                    recruitment.rightX() + rightWidth / 2,
                    recruitment.contentTop() + (recruitment.contentBottom() - recruitment.contentTop()) / 2 - 6,
                    MilitaryUi.MUTED);
            return;
        }
        int count = MilitaryLedgerController.filteredRecruitCount(cachedMirror);
        if (count == 0) {
            graphics.drawCenteredString(font, EMPTY_RECRUITS,
                    recruitment.rightX() + rightWidth / 2,
                    recruitment.contentTop() + (recruitment.contentBottom() - recruitment.contentTop()) / 2 - 6,
                    MilitaryUi.MUTED);
            return;
        }
        recruitScroll = MilitaryUi.clampScroll(recruitScroll, count, visible);
        int end = Math.min(count, recruitScroll + visible);
        graphics.enableScissor(recruitment.rightX() + 2, listY,
                recruitment.rightX() + rightWidth - 2, recruitment.contentBottom() - 1);
        for (int ordinal = recruitScroll; ordinal < end; ordinal++) {
            int row = MilitaryLedgerController.filteredRecruitRow(cachedMirror, ordinal);
            int y = listY + (ordinal - recruitScroll) * MilitaryUi.ROW_HEIGHT;
            boolean selected = MilitaryLedgerController.recruitSelected(cachedMirror, row);
            boolean focused = MilitaryLedgerController.recruitRow == row && rosterColumn == 1;
            boolean hovered = mouseX >= recruitment.rightX() + 2
                    && mouseX < recruitment.rightX() + rightWidth - 2
                    && mouseY >= y && mouseY < y + MilitaryUi.ROW_HEIGHT;
            String name = (selected ? "\u2713 " : "") + safe(cachedMirror.recruitName(row));
            MilitaryUi.row(font, graphics, recruitment.rightX() + 2, y, rightWidth - 4,
                    selected, hovered || focused,
                    name,
                    recruitSummary(row).getString(),
                    selected);
        }
        graphics.disableScissor();
        if (count > visible) {
            MilitaryUi.scrollbar(graphics, recruitment.rightX(), rightWidth,
                    listY, scrollHeight, recruitScroll, count, visible);
        }
    }

    private Component recruitSummary(int row) {
        String role = safe(cachedMirror.recruitRole(row));
        int strength = cachedMirror.recruitStrength(row);
        return switch (cachedMirror.recruitOptionCode(row)) {
            case ArmiesProtocol.RECRUIT_OPTION_ENLIST -> Component.translatable(
                    "gui.millenaire_armies.summary.recruit.controlled", role, strength);
            case ArmiesProtocol.RECRUIT_OPTION_HIRE -> Component.translatable(
                    "gui.millenaire_armies.summary.recruit.hire",
                    role,
                    strength,
                    cachedMirror.recruitCost(row),
                    cachedMirror.recruitReputation(row));
            case ArmiesProtocol.RECRUIT_OPTION_ASSIGN_HIRED -> Component.translatable(
                    "gui.millenaire_armies.summary.recruit.hired", role, strength);
            case ArmiesProtocol.RECRUIT_OPTION_REPUTATION_LOCKED -> Component.translatable(
                    "gui.millenaire_armies.summary.recruit.reputation_locked",
                    role,
                    cachedMirror.recruitReputation(row),
                    cachedMirror.recruitRequiredReputation(row));
            case ArmiesProtocol.RECRUIT_OPTION_FUNDS_LOCKED -> Component.translatable(
                    "gui.millenaire_armies.summary.recruit.funds_locked",
                    role,
                    cachedMirror.recruitCost(row));
            default -> Component.translatable(
                    "gui.millenaire_armies.summary.recruit.unavailable", role);
        };
    }

    private void drawTargetLine(GuiGraphics graphics) {
        int y = recruitment.statusCardY() + 9;
        ArmyClientMirror mirror = cachedMirror;
        int settlement = MilitaryLedgerController.findSettlementIndex(mirror);
        Component label;
        if (settlement >= 0 && !mirror.settlementControlled(settlement)) {
            label = Component.translatable("gui.millenaire_armies.ledger.retinue_target");
        } else if (mirror.armyCount() == 0) {
            label = NO_TARGET_ARMY;
        } else {
            String name = MilitaryLedgerController.selectedArmyName(mirror);
            label = Component.translatable("gui.millenaire_armies.ledger.target_army", safe(name));
        }
        int maxWidth = Math.max(60, panelWidth - (cycleButton.visible ? 116 : 30));
        String compact = font.plainSubstrByWidth(label.getString(), maxWidth);
        graphics.drawString(font, compact, panelX + 18, y, MilitaryUi.GOLD, true);
    }

    private void drawReadyLine(GuiGraphics graphics) {
        int y = recruitment.statusCardY() + 23;
        int settlement = MilitaryLedgerController.findSettlementIndex(cachedMirror);
        Component state;
        if (settlement < 0) {
            state = SELECT_SETTLEMENT;
        } else if (!cachedMirror.settlementControlled(settlement)) {
            state = primaryActionLabel();
        } else if (!MilitaryLedgerController.hasSelectedArmy) {
            state = NO_TARGET_ARMY;
        } else if (MilitaryLedgerController.selectedRecruitCount == 0) {
            state = SELECT_RECRUITS;
        } else {
            state = Component.translatable("gui.millenaire_armies.recruit.ready",
                    MilitaryLedgerController.selectedRecruitCount,
                    safe(MilitaryLedgerController.selectedArmyName(cachedMirror)));
        }
        String compact = font.plainSubstrByWidth(state.getString(), Math.max(80, panelWidth - 36));
        graphics.drawString(font, compact, panelX + 18, y, MilitaryUi.MUTED, false);
    }

    @Override
    protected void updateButtons() {
        boolean ready = cachedMirror.isReady();
        int settlement = MilitaryLedgerController.findSettlementIndex(cachedMirror);
        boolean controlled = settlement >= 0 && cachedMirror.settlementControlled(settlement);
        int option = focusedRecruitOption();
        createArmyButton.active = ready && controlled;
        recruitButton.setMessage(primaryActionLabel());
        recruitButton.active = ready && (controlled
                ? MilitaryLedgerController.hasSelectedArmy
                        && MilitaryLedgerController.selectedRecruitCount > 0
                : option == ArmiesProtocol.RECRUIT_OPTION_HIRE
                        || option == ArmiesProtocol.RECRUIT_OPTION_ASSIGN_HIRED);
        cycleButton.active = ready && controlled && cachedMirror.armyCount() > 0;
        cycleButton.visible = controlled;
    }

    private int focusedRecruitOption() {
        int row = MilitaryLedgerController.recruitRow;
        return row >= 0 && row < cachedMirror.recruitCount()
                ? cachedMirror.recruitOptionCode(row)
                : -1;
    }

    private Component primaryActionLabel() {
        int option = focusedRecruitOption();
        return switch (option) {
            case ArmiesProtocol.RECRUIT_OPTION_HIRE -> Component.translatable(
                    "gui.millenaire_armies.action.hire",
                    cachedMirror.recruitCost(MilitaryLedgerController.recruitRow));
            case ArmiesProtocol.RECRUIT_OPTION_ASSIGN_HIRED -> Component.translatable(
                    "gui.millenaire_armies.action.assign_hired");
            case ArmiesProtocol.RECRUIT_OPTION_REPUTATION_LOCKED -> Component.translatable(
                    "gui.millenaire_armies.action.reputation_required",
                    cachedMirror.recruitRequiredReputation(MilitaryLedgerController.recruitRow));
            case ArmiesProtocol.RECRUIT_OPTION_FUNDS_LOCKED -> Component.translatable(
                    "gui.millenaire_armies.action.deniers_required",
                    cachedMirror.recruitCost(MilitaryLedgerController.recruitRow));
            case ArmiesProtocol.RECRUIT_OPTION_ENLIST -> Component.translatable(
                    "gui.millenaire_armies.action.recruit");
            default -> Component.translatable("gui.millenaire_armies.action.recruit_unavailable");
        };
    }

    private void runPrimaryRecruitAction() {
        int option = focusedRecruitOption();
        if (option == ArmiesProtocol.RECRUIT_OPTION_HIRE
                || option == ArmiesProtocol.RECRUIT_OPTION_ASSIGN_HIRED) {
            MilitaryLedgerController.hireFocused(cachedMirror);
        } else {
            MilitaryLedgerController.recruitSelected(cachedMirror);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && cachedMirror.isReady()) {
            int listY = recruitment.contentTop() + 20;
            int scrollHeight = recruitment.contentBottom() - listY - 3;
            int visible = Math.max(1, scrollHeight / MilitaryUi.ROW_HEIGHT);
            if (mouseX >= panelX + 10 && mouseX < panelX + 10 + recruitment.listWidth()
                    && mouseY >= listY && mouseY < recruitment.contentBottom() - 1) {
                int row = settlementScroll + ((int) mouseY - listY) / MilitaryUi.ROW_HEIGHT;
                if (row >= 0 && row < cachedMirror.settlementCount() && row < settlementScroll + visible) {
                    MilitaryLedgerController.selectSettlement(cachedMirror, row);
                    rosterColumn = 0;
                    refreshMirror(true);
                    return true;
                }
            }
            int rightWidth = recruitment.rightWidth();
            if (MilitaryLedgerController.hasSettlement
                    && mouseX >= recruitment.rightX() && mouseX < recruitment.rightX() + rightWidth
                    && mouseY >= listY && mouseY < recruitment.contentBottom() - 1) {
                int ordinal = recruitScroll + ((int) mouseY - listY) / MilitaryUi.ROW_HEIGHT;
                if (ordinal >= 0 && ordinal < MilitaryLedgerController.filteredRecruitCount(cachedMirror)
                        && ordinal < recruitScroll + visible) {
                    int row = MilitaryLedgerController.filteredRecruitRow(cachedMirror, ordinal);
                    MilitaryLedgerController.recruitRow = row;
                    rosterColumn = 1;
                    if (cachedMirror.recruitOptionCode(row) == ArmiesProtocol.RECRUIT_OPTION_ENLIST) {
                        Component limit = MilitaryLedgerController.toggleRecruit(cachedMirror, row);
                        if (limit != null) {
                            MilitaryLedgerController.showFeedback(limit);
                        }
                    }
                    refreshMirror(true);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int direction = scrollY > 0.0D ? -1 : scrollY < 0.0D ? 1 : 0;
        int listY = recruitment.contentTop() + 20;
        int scrollHeight = recruitment.contentBottom() - listY - 3;
        int visible = Math.max(1, scrollHeight / MilitaryUi.ROW_HEIGHT);
        if (mouseX >= panelX + 10 && mouseX < panelX + 10 + recruitment.listWidth()
                && mouseY >= listY && mouseY < recruitment.contentBottom() - 1) {
            settlementScroll = MilitaryUi.clampScroll(
                    settlementScroll + direction, cachedMirror.settlementCount(), visible);
            return direction != 0;
        }
        int rightWidth = recruitment.rightWidth();
        if (mouseX >= recruitment.rightX() && mouseX < recruitment.rightX() + rightWidth
                && mouseY >= listY && mouseY < recruitment.contentBottom() - 1) {
            recruitScroll = MilitaryUi.clampScroll(recruitScroll + direction,
                    MilitaryLedgerController.filteredRecruitCount(cachedMirror), visible);
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
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
            rosterColumn = keyCode == GLFW.GLFW_KEY_LEFT ? 0 : 1;
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
            int direction = keyCode == GLFW.GLFW_KEY_UP ? -1 : 1;
            int listY = recruitment.contentTop() + 20;
            int scrollHeight = recruitment.contentBottom() - listY - 3;
            int visible = Math.max(1, scrollHeight / MilitaryUi.ROW_HEIGHT);
            if (rosterColumn == 0 && cachedMirror.settlementCount() > 0) {
                int current = Math.max(0, MilitaryLedgerController.findSettlementIndex(cachedMirror));
                int next = Math.max(0, Math.min(cachedMirror.settlementCount() - 1, current + direction));
                MilitaryLedgerController.selectSettlement(cachedMirror, next);
                settlementScroll = MilitaryUi.keepVisible(settlementScroll, next, visible);
                refreshMirror(true);
                return true;
            }
            int count = MilitaryLedgerController.filteredRecruitCount(cachedMirror);
            if (rosterColumn == 1 && count > 0) {
                int current = Math.max(0, MilitaryLedgerController.filteredRecruitOrdinal(
                        cachedMirror, MilitaryLedgerController.recruitRow));
                int next = Math.max(0, Math.min(count - 1, current + direction));
                MilitaryLedgerController.recruitRow =
                        MilitaryLedgerController.filteredRecruitRow(cachedMirror, next);
                recruitScroll = MilitaryUi.keepVisible(recruitScroll, next, visible);
                refreshMirror(true);
                return true;
            }
        }
        if ((keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER)
                && rosterColumn == 1
                && MilitaryLedgerController.recruitRow >= 0) {
            int row = MilitaryLedgerController.recruitRow;
            int option = cachedMirror.recruitOptionCode(row);
            if (option == ArmiesProtocol.RECRUIT_OPTION_ENLIST) {
                Component limit = MilitaryLedgerController.toggleRecruit(cachedMirror, row);
                if (limit != null) {
                    MilitaryLedgerController.showFeedback(limit);
                }
            } else if (option == ArmiesProtocol.RECRUIT_OPTION_HIRE
                    || option == ArmiesProtocol.RECRUIT_OPTION_ASSIGN_HIRED) {
                runPrimaryRecruitAction();
            }
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
