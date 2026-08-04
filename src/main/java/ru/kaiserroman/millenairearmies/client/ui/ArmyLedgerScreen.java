package ru.kaiserroman.millenairearmies.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import ru.kaiserroman.millenairearmies.ArmiesConfig;
import ru.kaiserroman.millenairearmies.network.ArmiesProtocol;

/** Player-facing army command desk with direct orders, direct formations and readable status cards. */
public final class ArmyLedgerScreen extends MillenaireCommandScreen {
    private static final Component TITLE = Component.translatable("gui.millenaire_armies.ledger.title.army");
    private static final Component BACK = Component.translatable("gui.millenaire_armies.back");
    private static final Component EMPTY = Component.translatable("gui.millenaire_armies.empty.armies");
    private static final Component EMPTY_HINT = Component.translatable("gui.millenaire_armies.ledger.no_army_hint");
    private static final Component FIELD_HINT = Component.translatable("gui.millenaire_armies.ledger.field_hint");
    private static final Component SECTION_ORDERS = Component.translatable("gui.millenaire_armies.section.command");
    private static final Component SECTION_FORMATIONS = Component.translatable("gui.millenaire_armies.section.formations");
    private static final Component LABEL_ARMIES = Component.translatable("gui.millenaire_armies.hub.armies");
    private static final Component LABEL_FACTION = Component.translatable("gui.millenaire_armies.label.faction");
    private static final Component LABEL_LOCATION = Component.translatable("gui.millenaire_armies.label.location");
    private static final Component LABEL_READY = Component.translatable("gui.millenaire_armies.label.ready");
    private static final Component LABEL_MORALE = Component.translatable("gui.millenaire_armies.label.morale");
    private static final Component LABEL_SUPPLIES = Component.translatable("gui.millenaire_armies.label.supplies");
    private static final Component LABEL_ORDER = Component.translatable("gui.millenaire_armies.label.order");
    private static final Component LABEL_FORMATION = Component.translatable("gui.millenaire_armies.label.formation");
    private static final Component LABEL_TARGET = Component.translatable("gui.millenaire_armies.label.target");
    private static final Component LABEL_SPEED = Component.translatable("gui.millenaire_armies.label.speed");
    private static final Component LABEL_COMPOSITION = Component.translatable("gui.millenaire_armies.label.composition");
    private static final Component LABEL_GARRISON = Component.translatable("gui.millenaire_armies.label.garrison");
    private static final Component LABEL_MUSTER = Component.translatable("gui.millenaire_armies.label.muster");
    private static final Component LABEL_GUARD_RADIUS = Component.translatable("gui.millenaire_armies.label.guard_radius");
    private static final Component TACTIC_SHIELDS = Component.translatable("gui.millenaire_armies.action.shield_wall_short");
    private static final Component TACTIC_FIRE = Component.translatable("gui.millenaire_armies.action.fire_at_will_short");
    private static final Component SUPPLY_CHEST = Component.translatable("gui.millenaire_armies.action.supply_chest_short");

    private static final int[] ORDER_TILE_CODES = {
            ArmiesProtocol.ORDER_HOLD, ArmiesProtocol.ORDER_MOVE, ArmiesProtocol.ORDER_FOLLOW,
            ArmiesProtocol.ORDER_ATTACK,
            ArmiesProtocol.ORDER_GUARD, ArmiesProtocol.ORDER_GARRISON,
            ArmiesProtocol.ORDER_SIEGE, ArmiesProtocol.ORDER_LOGISTICS
    };
    private static final Component[] ORDER_TILE_LABELS = {
            Component.translatable("gui.millenaire_armies.action.hold_short"),
            Component.translatable("gui.millenaire_armies.action.move_short"),
            Component.translatable("gui.millenaire_armies.action.follow_short"),
            Component.translatable("gui.millenaire_armies.action.attack_short"),
            Component.translatable("gui.millenaire_armies.action.guard_short"),
            Component.translatable("gui.millenaire_armies.action.garrison_short"),
            Component.translatable("gui.millenaire_armies.action.siege_short"),
            Component.translatable("gui.millenaire_armies.action.logistics_short"),
    };
    private static final int ORDER_TILE_COUNT = ORDER_TILE_CODES.length;

    private final ParchmentButton[] formationButtons = new ParchmentButton[5];
    private final ParchmentButton[] orderTiles = new ParchmentButton[ORDER_TILE_COUNT];
    private ParchmentButton shieldWallButton;
    private ParchmentButton fireAtWillButton;
    private ParchmentButton supplyChestButton;
    private MilitaryUi.LedgerPlacement placement;
    private int armyScroll;

    public ArmyLedgerScreen() {
        super();
    }

    public ArmyLedgerScreen(int preferredArmyId) {
        super(preferredArmyId);
    }

    @Override
    protected void init() {
        layoutPanel(640, 384, 360, MilitaryUi.ledgerMinPanelHeight(), 16);
        addRenderableWidget(MilitaryUi.parchmentButton(BACK, panelX + panelWidth - 58, panelY + 5, 50, 18,
                ignored -> openHub()));
        addLedgerTabs(ENTRY_ARMIES);

        placement = MilitaryUi.computeLedgerLayout(panelX, panelY, panelWidth, panelHeight);

        int gap = 4;

        for (int code = 0; code < formationButtons.length; code++) {
            final int fmCode = code;
            formationButtons[code] = addRenderableWidget(
                    MilitaryUi.parchmentButton(formationText(code),
                            panelX + 10 + code * (placement.formationButtonWidth() + gap),
                            placement.formationY(),
                            placement.formationButtonWidth(), 22,
                            24, 0,
                            (g, f, gx, gy, gw, gh, color) ->
                                    MilitaryUi.formationMark(g, gx + 4, gy + 3, fmCode, color),
                            ignored -> MilitaryLedgerController.setFormation(cachedMirror, fmCode)));
        }

        for (int i = 0; i < ORDER_TILE_COUNT; i++) {
            final int idx = i;
            int col = i % placement.orderCols();
            int row = i / placement.orderCols();
            int tileY = row == 0 ? placement.orderY0() : placement.orderY1();
            int tileX = panelX + 10 + col * (placement.orderTileWidth() + gap);
            int orderCode = ORDER_TILE_CODES[idx];
            ParchmentButton.GlyphRenderer glyph = (g, f, gx, gy, gw, gh, color) ->
                    MilitaryUi.orderGlyph(g, gx + gw / 2 - 8, gy + 2, orderCode, color);
            orderTiles[idx] = addRenderableWidget(
                    MilitaryUi.parchmentButton(ORDER_TILE_LABELS[idx],
                            tileX, tileY,
                            placement.orderTileWidth(), placement.orderTileHeight(),
                            0, 16, glyph,
                            ignored -> activateOrderTile(idx)));
        }
        int tacticY = placement.orderSectionY() - 4;
        shieldWallButton = addRenderableWidget(MilitaryUi.parchmentButton(
                TACTIC_SHIELDS, panelX + panelWidth - 178, tacticY, 54, 16,
                ignored -> toggleTactical(ArmiesProtocol.TACTIC_SHIELD_WALL)));
        fireAtWillButton = addRenderableWidget(MilitaryUi.parchmentButton(
                TACTIC_FIRE, panelX + panelWidth - 120, tacticY, 54, 16,
                ignored -> toggleTactical(ArmiesProtocol.TACTIC_FIRE_AT_WILL)));
        supplyChestButton = addRenderableWidget(MilitaryUi.parchmentButton(
                SUPPLY_CHEST, panelX + panelWidth - 62, tacticY, 52, 16,
                ignored -> selectSupplyChest()));

        refreshMirror(true);
        updateButtons();
    }

    private void activateOrderTile(int index) {
        int code = ORDER_TILE_CODES[index];
        if (code < 0) {
            MilitaryLedgerController.clearGarrison(cachedMirror);
        } else if (code == ArmiesProtocol.ORDER_GARRISON) {
            MilitaryLedgerController.setGarrison(cachedMirror, ArmiesConfig.GARRISON_DEFAULT_RADIUS);
        } else {
            MilitaryLedgerController.issueOrder(cachedMirror, code);
        }
    }

    private void toggleTactical(int tacticalCode) {
        int row = MilitaryLedgerController.findArmyIndex(
                cachedMirror, MilitaryLedgerController.selectedArmyId);
        if (row < 0) return;
        boolean enabled = tacticalCode == ArmiesProtocol.TACTIC_SHIELD_WALL
                ? !cachedMirror.armyShieldWall(row)
                : !cachedMirror.armyFireAtWill(row);
        cachedMirror.requestSetTactical(
                MilitaryLedgerController.selectedArmyId, tacticalCode, enabled);
    }

    private void selectSupplyChest() {
        if (MilitaryLedgerController.hasSelectedArmy) {
            cachedMirror.requestSetSupplyChest(MilitaryLedgerController.selectedArmyId);
        }
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
                    width / 2, placement.contentTop()
                            + Math.max(10, (placement.contentBottom() - placement.contentTop()) / 2 - 6),
                    MilitaryUi.MUTED);
        } else if (cachedMirror.armyCount() == 0) {
            MilitaryUi.card(graphics, panelX + 10, placement.contentTop(), panelWidth - 20,
                    Math.max(54, placement.contentBottom() - placement.contentTop()), false, false);
            graphics.drawCenteredString(font, EMPTY, width / 2, placement.contentTop() + 18, MilitaryUi.GOLD);
            drawWrapped(graphics, EMPTY_HINT, panelX + 36, placement.contentTop() + 34,
                    panelWidth - 72, MilitaryUi.MUTED, 3);
        } else {
            drawArmyList(graphics, mouseX, mouseY);
            drawArmyDetail(graphics);
        }

        MilitaryUi.section(font, graphics, SECTION_FORMATIONS,
                panelX + 10, placement.formationSectionY(), panelWidth - 20);
        MilitaryUi.section(font, graphics, SECTION_ORDERS,
                panelX + 10, placement.orderSectionY(), Math.min(180, panelWidth - 20));

        MilitaryUi.drawFeedback(graphics, font,
                MilitaryLedgerController.feedback(), MilitaryLedgerController.feedbackUntil());
        for (var renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    private void drawArmyList(GuiGraphics graphics, int mouseX, int mouseY) {
        int count = cachedMirror.armyCount();
        int listX = panelX + 10;
        int listHeight = placement.contentBottom() - placement.contentTop();
        MilitaryUi.well(graphics, listX, placement.contentTop(), placement.listWidth(), listHeight);
        MilitaryUi.section(font, graphics, LABEL_ARMIES, listX + 7, placement.contentTop() + 6,
                placement.listWidth() - 14);
        int listY = placement.contentTop() + 20;
        int scrollHeight = placement.contentBottom() - listY - 3;
        int visible = Math.max(1, scrollHeight / MilitaryUi.ROW_HEIGHT);
        armyScroll = MilitaryUi.clampScroll(armyScroll, count, visible);
        int end = Math.min(count, armyScroll + visible);
        graphics.enableScissor(listX + 2, listY, listX + placement.listWidth() - 2,
                placement.contentBottom() - 2);
        for (int index = armyScroll; index < end; index++) {
            int y = listY + (index - armyScroll) * MilitaryUi.ROW_HEIGHT;
            boolean selected = MilitaryLedgerController.selectedArmyId == cachedMirror.armyId(index);
            boolean hovered = mouseX >= listX + 2 && mouseX < listX + placement.listWidth() - 2
                    && mouseY >= y && mouseY < y + MilitaryUi.ROW_HEIGHT;
            MilitaryUi.row(font, graphics, listX + 3, y, placement.listWidth() - 7,
                    selected, hovered,
                    safe(cachedMirror.armyName(index)),
                    safe(cachedMirror.armySummary(index)),
                    selected);
        }
        graphics.disableScissor();
        if (count > visible) {
            MilitaryUi.scrollbar(graphics, listX, placement.listWidth(), listY, scrollHeight,
                    armyScroll, count, visible);
        }
    }

    private void drawArmyDetail(GuiGraphics graphics) {
        int row = MilitaryLedgerController.findArmyIndex(cachedMirror, MilitaryLedgerController.selectedArmyId);
        if (row < 0) return;

        int height = placement.contentBottom() - placement.contentTop();
        MilitaryUi.card(graphics, placement.detailX(), placement.contentTop(),
                placement.detailWidth(), height, false, false);
        String name = safe(cachedMirror.armyName(row));
        int nameMax = Math.max(60, placement.detailWidth() - 90);
        graphics.drawString(font, font.plainSubstrByWidth(name, nameMax),
                placement.detailX() + 10, placement.contentTop() + 8, MilitaryUi.GOLD, true);
        int formation = cachedMirror.armyFormationCode(row);
        MilitaryUi.formationMark(graphics, placement.detailX() + placement.detailWidth() - 28,
                placement.contentTop() + 7, formation, MilitaryUi.GOLD);
        graphics.drawString(font, formationText(formation),
                placement.detailX() + placement.detailWidth() - 34
                        - font.width(formationText(formation)),
                placement.contentTop() + 9, MilitaryUi.MUTED, false);
        MilitaryUi.separator(graphics, placement.detailX() + 9, placement.contentTop() + 22,
                placement.detailWidth() - 18);

        int innerX = placement.detailX() + 10;
        int innerWidth = placement.detailWidth() - 20;
        int total = cachedMirror.armyUnitCount(row);
        int ready = cachedMirror.armyReadyUnitCount(row);
        int readiness = total == 0 ? 0 : ready * 100 / total;
        if (height < 112) {
            String status = orderText(cachedMirror.armyOrderTypeCode(row)).getString()
                    + " · " + ready + '/' + total
                    + " · " + cachedMirror.armyMoralePercent(row) + "%"
                    + " · " + cachedMirror.armySupplyPercent(row) + "%";
            graphics.drawString(font, font.plainSubstrByWidth(status, innerWidth),
                    innerX, placement.contentTop() + 31, MilitaryUi.TEXT, false);
            String position = safe(cachedMirror.armyLocation(row))
                    + " → " + safe(cachedMirror.armyOrderTarget(row));
            graphics.drawString(font, font.plainSubstrByWidth(position, innerWidth),
                    innerX, placement.contentTop() + 44, MilitaryUi.MUTED, false);
            return;
        }

        int y = placement.contentTop() + 30;
        boolean wide = placement.detailWidth() >= 260;
        int gap = 10;
        int columnWidth = wide ? (innerWidth - gap) / 2 : innerWidth;
        int right = innerX + columnWidth + gap;
        drawPair(graphics, innerX, y, LABEL_ORDER,
                orderText(cachedMirror.armyOrderTypeCode(row)).getString(), MilitaryUi.TEXT, columnWidth);
        if (wide) {
            drawPair(graphics, right, y, LABEL_FACTION,
                    safe(cachedMirror.armyFactionName(row)), MilitaryUi.TEXT, columnWidth);
        } else {
            y += 12;
            drawPair(graphics, innerX, y, LABEL_FACTION,
                    safe(cachedMirror.armyFactionName(row)), MilitaryUi.TEXT, columnWidth);
        }
        y += 13;
        drawPair(graphics, innerX, y, LABEL_LOCATION,
                safe(cachedMirror.armyLocation(row)), MilitaryUi.TEXT, columnWidth);
        if (wide) {
            drawPair(graphics, right, y, LABEL_TARGET,
                    safe(cachedMirror.armyOrderTarget(row)), MilitaryUi.TEXT, columnWidth);
        } else {
            y += 12;
            drawPair(graphics, innerX, y, LABEL_TARGET,
                    safe(cachedMirror.armyOrderTarget(row)), MilitaryUi.TEXT, columnWidth);
        }

        int metersY = Math.max(y + 21, placement.contentTop() + (wide ? 66 : 82));
        if (wide) {
            MilitaryUi.meter(font, graphics, innerX, metersY, columnWidth,
                    LABEL_READY, MilitaryUi.ratio(ready, total), readiness);
            MilitaryUi.meter(font, graphics, right, metersY, columnWidth,
                    LABEL_MORALE, MilitaryUi.percent(cachedMirror.armyMoralePercent(row)),
                    cachedMirror.armyMoralePercent(row));
            metersY += 23;
            MilitaryUi.meter(font, graphics, innerX, metersY, columnWidth,
                    LABEL_SUPPLIES, MilitaryUi.percent(cachedMirror.armySupplyPercent(row)),
                    cachedMirror.armySupplyPercent(row));
            MilitaryUi.meter(font, graphics, right, metersY, columnWidth,
                    LABEL_SPEED, MilitaryUi.percent(cachedMirror.armySpeedPercent(row)),
                    cachedMirror.armySpeedPercent(row));
        } else {
            MilitaryUi.meter(font, graphics, innerX, metersY, innerWidth,
                    LABEL_READY, MilitaryUi.ratio(ready, total), readiness);
            metersY += 22;
            MilitaryUi.meter(font, graphics, innerX, metersY, innerWidth,
                    LABEL_MORALE, MilitaryUi.percent(cachedMirror.armyMoralePercent(row)),
                    cachedMirror.armyMoralePercent(row));
            metersY += 22;
            if (metersY + 17 < placement.contentBottom() - 6) {
                MilitaryUi.meter(font, graphics, innerX, metersY, innerWidth,
                        LABEL_SUPPLIES, MilitaryUi.percent(cachedMirror.armySupplyPercent(row)),
                        cachedMirror.armySupplyPercent(row));
            }
        }

        int footerY = placement.contentBottom() - 16;
        if (cachedMirror.armyHasGarrison(row)) {
            String duty = Component.translatable("gui.millenaire_armies.garrison.detail",
                    cachedMirror.armyGarrisonRadius(row),
                    garrisonStatus(cachedMirror.armyGarrisonStatusCode(row)).getString(),
                    cachedMirror.armyGarrisonReadinessPercent(row)).getString();
            drawPair(graphics, innerX, footerY, LABEL_GARRISON,
                    safe(cachedMirror.armyGarrisonSettlement(row)), MilitaryUi.TEXT, innerWidth);
            if (height >= 170) {
                drawPair(graphics, innerX, footerY - 12, LABEL_GUARD_RADIUS,
                        duty, MilitaryUi.MUTED, innerWidth);
                drawPair(graphics, innerX, footerY - 24, LABEL_MUSTER,
                        blockPosition(cachedMirror.armyGarrisonMusterPosition(row)),
                        MilitaryUi.MUTED, innerWidth);
            }
        } else {
            drawPair(graphics, innerX, footerY, LABEL_COMPOSITION,
                    safe(cachedMirror.armyComposition(row)), MilitaryUi.MUTED, innerWidth);
        }
    }

    @Override
    protected void updateButtons() {
        boolean ready = cachedMirror.isReady();
        boolean commandable = ready && MilitaryLedgerController.hasSelectedArmy;
        int row = MilitaryLedgerController.findArmyIndex(cachedMirror, MilitaryLedgerController.selectedArmyId);
        int selectedFormation = row < 0 ? -1 : cachedMirror.armyFormationCode(row);
        int currentOrder = row < 0 ? -1 : cachedMirror.armyOrderTypeCode(row);
        boolean hasGarrison = row >= 0 && cachedMirror.armyHasGarrison(row);

        for (int code = 0; code < formationButtons.length; code++) {
            formationButtons[code].active = commandable && code != selectedFormation;
            formationButtons[code].setSelected(code == selectedFormation);
        }

        for (int i = 0; i < orderTiles.length; i++) {
            int code = ORDER_TILE_CODES[i];
            boolean selected;
            if (code < 0) {
                selected = false;
            } else if (code == ArmiesProtocol.ORDER_GARRISON) {
                selected = hasGarrison;
            } else {
                selected = !hasGarrison && code == currentOrder;
            }
            orderTiles[i].setSelected(selected);
            if (code < 0) {
                orderTiles[i].active = commandable && row >= 0 && cachedMirror.armyHasGarrison(row);
            } else {
                orderTiles[i].active = commandable;
            }
        }
        boolean shields = row >= 0 && cachedMirror.armyShieldWall(row);
        boolean fire = row >= 0 && cachedMirror.armyFireAtWill(row);
        shieldWallButton.active = commandable;
        fireAtWillButton.active = commandable;
        supplyChestButton.active = commandable;
        shieldWallButton.setSelected(shields);
        fireAtWillButton.setSelected(fire);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && cachedMirror.isReady() && cachedMirror.armyCount() > 0
                && mouseX >= panelX + 10 && mouseX < panelX + 10 + placement.listWidth()
                && mouseY >= placement.contentTop() + 20 && mouseY < placement.contentBottom() - 2) {
            int listY = placement.contentTop() + 20;
            int scrollHeight = placement.contentBottom() - listY - 3;
            int visible = Math.max(1, scrollHeight / MilitaryUi.ROW_HEIGHT);
            int row = armyScroll + ((int) mouseY - listY) / MilitaryUi.ROW_HEIGHT;
            if (row >= 0 && row < cachedMirror.armyCount() && row < armyScroll + visible) {
                MilitaryLedgerController.selectArmy(cachedMirror.armyId(row));
                refreshMirror(true);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= panelX + 10 && mouseX < panelX + 10 + placement.listWidth()
                && mouseY >= placement.contentTop() && mouseY < placement.contentBottom()) {
            int direction = scrollY > 0.0D ? -1 : scrollY < 0.0D ? 1 : 0;
            int listY = placement.contentTop() + 20;
            int scrollHeight = placement.contentBottom() - listY - 3;
            int visible = Math.max(1, scrollHeight / MilitaryUi.ROW_HEIGHT);
            armyScroll = MilitaryUi.clampScroll(armyScroll + direction, cachedMirror.armyCount(), visible);
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
                && cachedMirror.armyCount() > 0) {
            int current = MilitaryLedgerController.findArmyIndex(
                    cachedMirror, MilitaryLedgerController.selectedArmyId);
            int direction = keyCode == GLFW.GLFW_KEY_UP ? -1 : 1;
            int next = Math.max(0, Math.min(cachedMirror.armyCount() - 1,
                    (current < 0 ? 0 : current) + direction));
            MilitaryLedgerController.selectArmy(cachedMirror.armyId(next));
            int listY = placement.contentTop() + 20;
            int scrollHeight = placement.contentBottom() - listY - 3;
            int visible = Math.max(1, scrollHeight / MilitaryUi.ROW_HEIGHT);
            armyScroll = MilitaryUi.keepVisible(armyScroll, next, visible);
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
