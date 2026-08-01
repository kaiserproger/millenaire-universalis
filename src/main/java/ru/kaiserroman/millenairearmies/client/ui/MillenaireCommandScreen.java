package ru.kaiserroman.millenairearmies.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import ru.kaiserroman.millenairearmies.client.ArmyClientMirror;
import ru.kaiserroman.millenairearmies.client.ArmyClientState;

/**
 * Asset-independent strategic command screen inspired by grand-strategy information density.
 * All mutable simulation data is obtained through {@link ArmyClientMirror}.
 */
public final class MillenaireCommandScreen extends Screen {
    private static final Component TITLE = Component.translatable("gui.millenaire_armies.title");
    private static final Component CLOSE = Component.translatable("gui.done");
    private static final Component WAITING = Component.translatable("gui.millenaire_armies.waiting");
    private static final Component NO_FACTIONS = Component.translatable("gui.millenaire_armies.empty.factions");
    private static final Component NO_ARMIES = Component.translatable("gui.millenaire_armies.empty.armies");
    private static final Component NO_ORDERS = Component.translatable("gui.millenaire_armies.empty.orders");
    private static final Component NO_LOGISTICS = Component.translatable("gui.millenaire_armies.empty.logistics");
    private static final Component NO_LOGISTICS_HINT = Component.translatable("gui.millenaire_armies.empty.logistics_hint");
    private static final Component COMMAND_UNAVAILABLE = Component.translatable("gui.millenaire_armies.command.unavailable");
    private static final Component COMMAND_SENT = Component.translatable("gui.millenaire_armies.command.sent");
    private static final Component CANCEL = Component.translatable("gui.millenaire_armies.action.cancel");
    private static final Component CANCEL_CONFIRM = Component.translatable("gui.millenaire_armies.action.cancel_confirm");
    private static final Component CANCEL_ARMED = Component.translatable("gui.millenaire_armies.command.cancel_armed");
    private static final Component UNKNOWN = Component.literal("?");
    private static final Component METRIC_FACTIONS = Component.translatable("gui.millenaire_armies.metric.factions");
    private static final Component METRIC_ARMIES = Component.translatable("gui.millenaire_armies.metric.armies");
    private static final Component METRIC_UNITS = Component.translatable("gui.millenaire_armies.metric.units");
    private static final Component METRIC_ROUTES = Component.translatable("gui.millenaire_armies.metric.routes");
    private static final Component PRIORITY_NORMAL = Component.translatable("gui.millenaire_armies.priority.normal");
    private static final Component PRIORITY_HIGH = Component.translatable("gui.millenaire_armies.priority.high");

    private static final Component LABEL_CULTURE = Component.translatable("gui.millenaire_armies.label.culture");
    private static final Component LABEL_RELATION = Component.translatable("gui.millenaire_armies.label.relation");
    private static final Component LABEL_REPUTATION = Component.translatable("gui.millenaire_armies.label.reputation");
    private static final Component LABEL_INFLUENCE = Component.translatable("gui.millenaire_armies.label.influence");
    private static final Component LABEL_CAPITAL = Component.translatable("gui.millenaire_armies.label.capital");
    private static final Component LABEL_SETTLEMENTS = Component.translatable("gui.millenaire_armies.label.settlements");
    private static final Component LABEL_POPULATION = Component.translatable("gui.millenaire_armies.label.population");
    private static final Component LABEL_FACTION = Component.translatable("gui.millenaire_armies.label.faction");
    private static final Component LABEL_LOCATION = Component.translatable("gui.millenaire_armies.label.location");
    private static final Component LABEL_STRENGTH = Component.translatable("gui.millenaire_armies.label.strength");
    private static final Component LABEL_READY = Component.translatable("gui.millenaire_armies.label.ready");
    private static final Component LABEL_MORALE = Component.translatable("gui.millenaire_armies.label.morale");
    private static final Component LABEL_SUPPLIES = Component.translatable("gui.millenaire_armies.label.supplies");
    private static final Component LABEL_SPEED = Component.translatable("gui.millenaire_armies.label.speed");
    private static final Component LABEL_COMPOSITION = Component.translatable("gui.millenaire_armies.label.composition");
    private static final Component LABEL_ORDER = Component.translatable("gui.millenaire_armies.label.order");
    private static final Component LABEL_TARGET = Component.translatable("gui.millenaire_armies.label.target");
    private static final Component LABEL_STATE = Component.translatable("gui.millenaire_armies.label.state");
    private static final Component LABEL_ISSUED = Component.translatable("gui.millenaire_armies.label.issued");
    private static final Component LABEL_CARGO = Component.translatable("gui.millenaire_armies.label.cargo");
    private static final Component LABEL_ROUTE = Component.translatable("gui.millenaire_armies.label.route");
    private static final Component LABEL_ASSIGNED = Component.translatable("gui.millenaire_armies.label.assigned");
    private static final Component LABEL_PROGRESS = Component.translatable("gui.millenaire_armies.label.progress");
    private static final Component LABEL_RISK = Component.translatable("gui.millenaire_armies.label.risk");
    private static final Component LABEL_PRIORITY = Component.translatable("gui.millenaire_armies.label.priority");
    private static final Component LABEL_DIPLOMACY = Component.translatable("gui.millenaire_armies.section.diplomacy");
    private static final Component LABEL_COMMAND = Component.translatable("gui.millenaire_armies.section.command");

    private static final Component[] RELATIONS = translatedArray("gui.millenaire_armies.relation.",
            "hostile", "neutral", "friendly", "allied", "vassal");
    private static final Component[] ORDER_TYPES = translatedArray("gui.millenaire_armies.order.",
            "hold", "move", "rally", "logistics");
    private static final Component[] LOGISTICS_STATES = translatedArray("gui.millenaire_armies.logistics.",
            "pending", "assigned", "in_transit", "fulfilled", "cancelled");

    private static final int PANEL = 0xF01A1713;
    private static final int PANEL_INNER = 0xE826211B;
    private static final int PANEL_ALT = 0xD9322A20;
    private static final int BORDER = 0xFF8D7144;
    private static final int BORDER_DARK = 0xFF4D3D29;
    private static final int GOLD = 0xFFFFD37A;
    private static final int TEXT = 0xFFE7DDCA;
    private static final int MUTED = 0xFFCEC0A6;
    private static final int SELECTED = 0xCC654D2D;
    private static final int HOVER = 0x99604E38;
    private static final int ROW_HEIGHT = 28;
    private static final int ORDER_HOLD = 0;
    private static final int ORDER_MOVE = 1;
    private static final int ORDER_RALLY = 2;
    private static final int ORDER_LOGISTICS = 3;

    private final int[] scrollRows = new int[StrategicTab.VALUES.length];
    private final Button[] tabButtons = new Button[StrategicTab.VALUES.length];
    private StrategicTab tab = StrategicTab.OVERVIEW;

    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int contentX;
    private int contentY;
    private int contentWidth;
    private int contentHeight;
    private int listWidth;
    private int tabsX;
    private int tabWidth;

    private int selectedFactionId = -1;
    private int selectedArmyId;
    private boolean hasSelectedArmy;
    private long selectedOrderId = -1L;
    private int selectedLogisticsId = -1;

    private ArmyClientMirror cachedMirror = ArmyClientMirror.EMPTY;
    private long cachedRevision = Long.MIN_VALUE;
    private String factionCountText = "0";
    private String armyCountText = "0";
    private String unitCountText = "0";
    private String logisticsCountText = "0";
    private String selectedFactionReputationText = "0";
    private String selectedFactionInfluenceText = "0";
    private String selectedFactionSettlementText = "0";
    private String selectedFactionArmyCountText = "0";
    private String selectedFactionPopulationText = "0";
    private String selectedArmyStrengthText = "0";
    private String selectedArmyReadyText = "0 / 0";
    private String selectedArmyMoraleText = "0%";
    private String selectedArmySupplyText = "0%";
    private String selectedArmySpeedText = "0%";
    private String selectedOrderIssuedText = "0";
    private String selectedLogisticsCargoText = "";
    private String selectedLogisticsRouteText = "";
    private String selectedLogisticsProgressText = "0%";
    private String selectedLogisticsRiskText = "0%";
    private Component selectedLogisticsPriorityText = PRIORITY_NORMAL;
    private Component feedback;
    private long feedbackUntilMillis;
    private long cancelConfirmUntilMillis;

    private Button holdButton;
    private Button moveButton;
    private Button rallyButton;
    private Button logisticsButton;
    private Button cancelButton;
    private Button priorityButton;

    public MillenaireCommandScreen() {
        super(TITLE);
    }

    @Override
    protected void init() {
        panelWidth = Math.max(304, Math.min(780, width - 16));
        panelHeight = Math.max(220, Math.min(450, height - 16));
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        contentX = panelX + 9;
        contentY = panelY + 66;
        contentWidth = panelWidth - 18;
        contentHeight = panelHeight - 105;
        listWidth = Math.max(126, Math.min(295, contentWidth * 42 / 100));

        tabWidth = Math.max(48, (panelWidth - 18) / StrategicTab.VALUES.length);
        tabsX = panelX + 9;
        for (int i = 0; i < StrategicTab.VALUES.length; i++) {
            final StrategicTab target = StrategicTab.VALUES[i];
            Button button = Button.builder(target.title, ignored -> selectTab(target))
                    .bounds(tabsX + i * tabWidth, panelY + 39, tabWidth - 2, 20)
                    .build();
            tabButtons[i] = addRenderableWidget(button);
        }

        int actionY = panelY + panelHeight - 30;
        int actionAreaWidth = panelWidth - 86;
        int orderButtonWidth = Math.max(42, (actionAreaWidth - 6) / 4);
        int actionX = panelX + 10;
        holdButton = actionButton("gui.millenaire_armies.action.hold", actionX, actionY, orderButtonWidth, ORDER_HOLD);
        moveButton = actionButton("gui.millenaire_armies.action.move", actionX + orderButtonWidth + 2, actionY, orderButtonWidth, ORDER_MOVE);
        rallyButton = actionButton("gui.millenaire_armies.action.rally", actionX + (orderButtonWidth + 2) * 2, actionY, orderButtonWidth, ORDER_RALLY);
        logisticsButton = actionButton("gui.millenaire_armies.action.logistics", actionX + (orderButtonWidth + 2) * 3, actionY, orderButtonWidth, ORDER_LOGISTICS);

        int secondaryButtonWidth = Math.max(72, (actionAreaWidth - 4) / 2);
        cancelButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.millenaire_armies.action.cancel"), ignored -> cancelSelected())
                .bounds(actionX, actionY, secondaryButtonWidth, 20)
                .build());
        priorityButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.millenaire_armies.action.priority"), ignored -> togglePriority())
                .bounds(actionX + secondaryButtonWidth + 4, actionY, secondaryButtonWidth, 20)
                .build());

        addRenderableWidget(Button.builder(CLOSE, ignored -> onClose())
                .bounds(panelX + panelWidth - 66, actionY, 56, 20)
                .build());
        refreshMirror(true);
        updateButtons();
    }

    private Button actionButton(String translationKey, int x, int y, int buttonWidth, int orderCode) {
        return addRenderableWidget(Button.builder(Component.translatable(translationKey), ignored -> issueOrder(orderCode))
                .bounds(x, y, buttonWidth, 20)
                .build());
    }

    @Override
    public void added() {
        ArmyClientState.current().requestFullSync();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        refreshMirror(false);
        drawFrame(graphics);

        if (!cachedMirror.isReady()) {
            graphics.drawCenteredString(font, WAITING, width / 2, contentY + contentHeight / 2 - 5, MUTED);
            String status = safe(cachedMirror.statusText());
            if (!status.isEmpty()) {
                graphics.drawCenteredString(font, status, width / 2, contentY + contentHeight / 2 + 10, MUTED);
            }
        } else {
            graphics.enableScissor(contentX + 1, contentY + 1, contentX + contentWidth - 1, contentY + contentHeight - 1);
            switch (tab) {
                case OVERVIEW -> renderOverview(graphics);
                case FACTIONS -> renderFactions(graphics, mouseX, mouseY);
                case ARMIES -> renderArmies(graphics, mouseX, mouseY);
                case ORDERS -> renderOrders(graphics, mouseX, mouseY);
                case LOGISTICS -> renderLogistics(graphics, mouseX, mouseY);
            }
            graphics.disableScissor();
        }

        long now = System.currentTimeMillis();
        if (cancelConfirmUntilMillis != 0L && now >= cancelConfirmUntilMillis) {
            resetCancelConfirmation();
        }
        if (feedback != null && now < feedbackUntilMillis) {
            graphics.drawCenteredString(font, feedback, panelX + panelWidth / 2, panelY + panelHeight - 43, GOLD);
        }
        for (Renderable renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
        graphics.fill(tabsX + tab.ordinal() * tabWidth, panelY + 60,
                tabsX + (tab.ordinal() + 1) * tabWidth - 2, panelY + 62, GOLD);
    }

    private void drawFrame(GuiGraphics graphics) {
        graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL);
        outline(graphics, panelX, panelY, panelWidth, panelHeight, BORDER);
        outline(graphics, panelX + 3, panelY + 3, panelWidth - 6, panelHeight - 6, BORDER_DARK);
        graphics.fill(panelX + 7, panelY + 7, panelX + panelWidth - 7, panelY + 34, PANEL_ALT);
        graphics.drawString(font, TITLE, panelX + 14, panelY + 15, GOLD, true);
        String realm = safe(cachedMirror.playerFactionName());
        if (!realm.isEmpty()) {
            graphics.drawString(font, realm, panelX + panelWidth - 14 - font.width(realm), panelY + 15, TEXT, true);
        }
        graphics.fill(contentX, contentY, contentX + contentWidth, contentY + contentHeight, PANEL_INNER);
        outline(graphics, contentX, contentY, contentWidth, contentHeight, BORDER_DARK);
    }

    private void renderOverview(GuiGraphics graphics) {
        int gap = 6;
        int cardWidth = (contentWidth - gap * 3) / 4;
        drawMetricCard(graphics, contentX, contentY, cardWidth, METRIC_FACTIONS, factionCountText);
        drawMetricCard(graphics, contentX + cardWidth + gap, contentY, cardWidth, METRIC_ARMIES, armyCountText);
        drawMetricCard(graphics, contentX + (cardWidth + gap) * 2, contentY, cardWidth, METRIC_UNITS, unitCountText);
        drawMetricCard(graphics, contentX + (cardWidth + gap) * 3, contentY, cardWidth, METRIC_ROUTES, logisticsCountText);

        int boxY = contentY + 46;
        int boxHeight = contentHeight - 46;
        int detailStep = Math.max(10, Math.min(14, (boxHeight - 26) / 4));
        int half = (contentWidth - 6) / 2;
        sectionBox(graphics, contentX, boxY, half, boxHeight, LABEL_DIPLOMACY);
        sectionBox(graphics, contentX + half + 6, boxY, half, boxHeight, LABEL_COMMAND);
        drawOverviewFaction(graphics, contentX + 9, boxY + 17, detailStep);
        drawOverviewArmy(graphics, contentX + half + 15, boxY + 17, detailStep);
    }

    private void drawOverviewFaction(GuiGraphics graphics, int x, int y, int step) {
        int index = findFactionIndex(selectedFactionId);
        if (index < 0 && cachedMirror.factionCount() > 0) {
            index = 0;
        }
        if (index < 0) {
            graphics.drawString(font, NO_FACTIONS, x, y, MUTED, false);
            return;
        }
        graphics.drawString(font, safe(cachedMirror.factionName(index)), x, y, GOLD, true);
        drawPair(graphics, x, y + step, LABEL_CULTURE, safe(cachedMirror.factionName(index)));
        drawPair(graphics, x, y + step * 2, LABEL_RELATION, relationText(cachedMirror.factionRelationCode(index)));
        drawPair(graphics, x, y + step * 3, LABEL_SETTLEMENTS, selectedFactionSettlementText);
        drawPair(graphics, x, y + step * 4, LABEL_POPULATION, selectedFactionPopulationText);
    }

    private void drawOverviewArmy(GuiGraphics graphics, int x, int y, int step) {
        int index = findArmyIndex(selectedArmyId);
        if (index < 0 && cachedMirror.armyCount() > 0) {
            index = 0;
        }
        if (index < 0) {
            graphics.drawString(font, NO_ARMIES, x, y, MUTED, false);
            return;
        }
        graphics.drawString(font, safe(cachedMirror.armyName(index)), x, y, GOLD, true);
        drawPair(graphics, x, y + step, LABEL_ORDER, orderText(cachedMirror.armyOrderTypeCode(index)));
        drawPair(graphics, x, y + step * 2, LABEL_LOCATION, safe(cachedMirror.armyLocation(index)));
        drawPair(graphics, x, y + step * 3, LABEL_READY, selectedArmyReadyText);
        drawPair(graphics, x, y + step * 4, LABEL_SUPPLIES, selectedArmySupplyText);
    }

    private void renderFactions(GuiGraphics graphics, int mouseX, int mouseY) {
        drawListPanel(graphics, mouseX, mouseY, cachedMirror.factionCount(), NO_FACTIONS, ListKind.FACTION);
        int index = findFactionIndex(selectedFactionId);
        if (index < 0) {
            return;
        }
        int x = contentX + listWidth + 15;
        int y = contentY + 6;
        int step = detailStep(8);
        graphics.drawString(font, safe(cachedMirror.factionName(index)), x, y, GOLD, true);
        drawPair(graphics, x, y + 14, LABEL_CULTURE, safe(cachedMirror.factionName(index)));
        drawPair(graphics, x, y + 14 + step, LABEL_RELATION, relationText(cachedMirror.factionRelationCode(index)));
        drawPair(graphics, x, y + 14 + step * 2, LABEL_REPUTATION, selectedFactionReputationText);
        drawPair(graphics, x, y + 14 + step * 3, LABEL_INFLUENCE, selectedFactionInfluenceText);
        drawPair(graphics, x, y + 14 + step * 4, LABEL_CAPITAL, safe(cachedMirror.factionCapitalName(index)));
        drawPair(graphics, x, y + 14 + step * 5, LABEL_SETTLEMENTS, selectedFactionSettlementText);
        drawPair(graphics, x, y + 14 + step * 6, LABEL_POPULATION, selectedFactionPopulationText);
        drawPair(graphics, x, y + 14 + step * 7, METRIC_ARMIES, selectedFactionArmyCountText);
    }

    private void renderArmies(GuiGraphics graphics, int mouseX, int mouseY) {
        drawListPanel(graphics, mouseX, mouseY, cachedMirror.armyCount(), NO_ARMIES, ListKind.ARMY);
        int index = findArmyIndex(selectedArmyId);
        if (index < 0) {
            return;
        }
        int x = contentX + listWidth + 15;
        int y = contentY + 6;
        int step = detailStep(10);
        graphics.drawString(font, safe(cachedMirror.armyName(index)), x, y, GOLD, true);
        drawPair(graphics, x, y + 14, LABEL_FACTION, safe(cachedMirror.armyFactionName(index)));
        drawPair(graphics, x, y + 14 + step, LABEL_LOCATION, safe(cachedMirror.armyLocation(index)));
        drawPair(graphics, x, y + 14 + step * 2, LABEL_STRENGTH, selectedArmyStrengthText);
        drawPair(graphics, x, y + 14 + step * 3, LABEL_READY, selectedArmyReadyText);
        drawPair(graphics, x, y + 14 + step * 4, LABEL_MORALE, selectedArmyMoraleText);
        drawPair(graphics, x, y + 14 + step * 5, LABEL_SUPPLIES, selectedArmySupplyText);
        drawPair(graphics, x, y + 14 + step * 6, LABEL_SPEED, selectedArmySpeedText);
        drawPair(graphics, x, y + 14 + step * 7, LABEL_ORDER, orderText(cachedMirror.armyOrderTypeCode(index)));
        drawPair(graphics, x, y + 14 + step * 8, LABEL_TARGET, safe(cachedMirror.armyOrderTarget(index)));
        drawPair(graphics, x, y + 14 + step * 9, LABEL_COMPOSITION, safe(cachedMirror.armyComposition(index)));
    }

    private void renderOrders(GuiGraphics graphics, int mouseX, int mouseY) {
        drawListPanel(graphics, mouseX, mouseY, cachedMirror.orderCount(), NO_ORDERS, ListKind.ORDER);
        int index = findOrderIndex(selectedOrderId);
        if (index < 0) {
            return;
        }
        int x = contentX + listWidth + 15;
        int y = contentY + 12;
        graphics.drawString(font, safe(cachedMirror.orderArmyName(index)), x, y, GOLD, true);
        drawPair(graphics, x, y + 22, LABEL_ORDER, orderText(cachedMirror.orderTypeCode(index)));
        drawPair(graphics, x, y + 38, LABEL_TARGET, safe(cachedMirror.orderTarget(index)));
        drawPair(graphics, x, y + 54, LABEL_STATE, safe(cachedMirror.orderState(index)));
        drawPair(graphics, x, y + 70, LABEL_ISSUED, selectedOrderIssuedText);
    }

    private void renderLogistics(GuiGraphics graphics, int mouseX, int mouseY) {
        drawListPanel(graphics, mouseX, mouseY, cachedMirror.logisticsCount(), NO_LOGISTICS, ListKind.LOGISTICS);
        int index = findLogisticsIndex(selectedLogisticsId);
        if (index < 0) {
            int rightCenter = contentX + listWidth + (contentWidth - listWidth) / 2;
            graphics.drawCenteredString(font, NO_LOGISTICS_HINT, rightCenter,
                    contentY + contentHeight / 2 - 4, MUTED);
            return;
        }
        int x = contentX + listWidth + 15;
        int y = contentY + 12;
        graphics.drawString(font, safe(cachedMirror.logisticsName(index)), x, y, GOLD, true);
        drawPair(graphics, x, y + 22, LABEL_CARGO, selectedLogisticsCargoText);
        drawPair(graphics, x, y + 38, LABEL_ROUTE, selectedLogisticsRouteText);
        drawPair(graphics, x, y + 54, LABEL_ASSIGNED, safe(cachedMirror.logisticsAssignedArmy(index)));
        drawPair(graphics, x, y + 70, LABEL_STATE, logisticsText(cachedMirror.logisticsStatusCode(index)));
        drawPair(graphics, x, y + 86, LABEL_PROGRESS, selectedLogisticsProgressText);
        drawPair(graphics, x, y + 102, LABEL_RISK, selectedLogisticsRiskText);
        drawPair(graphics, x, y + 118, LABEL_PRIORITY, selectedLogisticsPriorityText);
    }

    private void drawListPanel(GuiGraphics graphics, int mouseX, int mouseY, int count, Component empty, ListKind kind) {
        int listRight = contentX + listWidth;
        graphics.fill(contentX + 1, contentY + 1, listRight, contentY + contentHeight - 1, PANEL_ALT);
        graphics.fill(listRight, contentY + 1, listRight + 1, contentY + contentHeight - 1, BORDER_DARK);
        if (count <= 0) {
            graphics.drawCenteredString(font, empty, contentX + listWidth / 2, contentY + contentHeight / 2 - 4, MUTED);
            return;
        }

        int visible = Math.max(1, (contentHeight - 6) / ROW_HEIGHT);
        int scroll = clampScroll(scrollRows[tab.ordinal()], count, visible);
        scrollRows[tab.ordinal()] = scroll;
        int end = Math.min(count, scroll + visible);
        graphics.enableScissor(contentX + 2, contentY + 2, listRight - 1, contentY + contentHeight - 2);
        for (int index = scroll; index < end; index++) {
            int y = contentY + 3 + (index - scroll) * ROW_HEIGHT;
            boolean selected = kind.isSelected(this, index);
            boolean hovered = mouseX >= contentX + 3 && mouseX < listRight - 2 && mouseY >= y && mouseY < y + ROW_HEIGHT - 2;
            if (selected || hovered) {
                graphics.fill(contentX + 3, y, listRight - 2, y + ROW_HEIGHT - 2, selected ? SELECTED : HOVER);
            }
            graphics.drawString(font, kind.name(cachedMirror, index), contentX + 8, y + 5, selected ? GOLD : TEXT, true);
            graphics.drawString(font, kind.summary(cachedMirror, index), contentX + 8, y + 16, MUTED, false);
        }
        graphics.disableScissor();
        if (count > visible) {
            int trackY = contentY + 4;
            int trackHeight = contentHeight - 8;
            int thumbHeight = Math.max(8, trackHeight * visible / count);
            int travel = trackHeight - thumbHeight;
            int thumbY = trackY + travel * scroll / Math.max(1, count - visible);
            graphics.fill(listRight - 5, trackY, listRight - 3, trackY + trackHeight, BORDER_DARK);
            graphics.fill(listRight - 5, thumbY, listRight - 3, thumbY + thumbHeight, MUTED);
        }
    }

    private void drawMetricCard(GuiGraphics graphics, int x, int y, int width, Component label, String value) {
        graphics.fill(x, y, x + width, y + 40, PANEL_ALT);
        outline(graphics, x, y, width, 40, BORDER_DARK);
        graphics.drawString(font, label, x + 7, y + 5, MUTED, false);
        graphics.drawString(font, value, x + 7, y + 21, GOLD, true);
    }

    private void sectionBox(GuiGraphics graphics, int x, int y, int width, int height, Component label) {
        graphics.fill(x, y, x + width, y + height, PANEL_ALT);
        outline(graphics, x, y, width, height, BORDER_DARK);
        graphics.drawString(font, label, x + 7, y + 5, MUTED, false);
    }

    private void drawPair(GuiGraphics graphics, int x, int y, Component label, String value) {
        graphics.drawString(font, label, x, y, MUTED, false);
        int valueX = x + Math.min(104, Math.max(58, font.width(label) + 8));
        graphics.drawString(font, value, valueX, y, TEXT, false);
    }

    private void drawPair(GuiGraphics graphics, int x, int y, Component label, Component value) {
        graphics.drawString(font, label, x, y, MUTED, false);
        int valueX = x + Math.min(104, Math.max(58, font.width(label) + 8));
        graphics.drawString(font, value, valueX, y, TEXT, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && cachedMirror.isReady() && insideList(mouseX, mouseY)) {
            int count = currentListCount();
            int visible = Math.max(1, (contentHeight - 6) / ROW_HEIGHT);
            int index = scrollRows[tab.ordinal()] + ((int) mouseY - contentY - 3) / ROW_HEIGHT;
            if (index >= 0 && index < count && index < scrollRows[tab.ordinal()] + visible) {
                selectIndex(index);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (insideList(mouseX, mouseY)) {
            int count = currentListCount();
            int visible = Math.max(1, (contentHeight - 6) / ROW_HEIGHT);
            int direction = scrollY > 0.0D ? -1 : scrollY < 0.0D ? 1 : 0;
            scrollRows[tab.ordinal()] = clampScroll(scrollRows[tab.ordinal()] + direction, count, visible);
            return direction != 0;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void selectTab(StrategicTab target) {
        tab = target;
        resetCancelConfirmation();
        updateButtons();
    }

    private void selectIndex(int index) {
        switch (tab) {
            case FACTIONS -> selectedFactionId = cachedMirror.factionId(index);
            case ARMIES -> {
                selectedArmyId = cachedMirror.armyId(index);
                hasSelectedArmy = true;
            }
            case ORDERS -> {
                selectedOrderId = cachedMirror.orderId(index);
                selectedArmyId = cachedMirror.orderArmyId(index);
                hasSelectedArmy = true;
            }
            case LOGISTICS -> selectedLogisticsId = cachedMirror.logisticsId(index);
            default -> {
            }
        }
        refreshSelectionText();
        updateButtons();
    }

    private void issueOrder(int typeCode) {
        if (hasSelectedArmy && ArmyClientState.current().requestIssueOrder(selectedArmyId, typeCode)) {
            showFeedback(COMMAND_SENT);
        } else {
            showFeedback(COMMAND_UNAVAILABLE);
        }
    }

    private void cancelSelected() {
        long now = System.currentTimeMillis();
        if (now >= cancelConfirmUntilMillis) {
            cancelConfirmUntilMillis = now + 3000L;
            cancelButton.setMessage(CANCEL_CONFIRM);
            showFeedback(CANCEL_ARMED);
            return;
        }
        ArmyClientMirror mirror = ArmyClientState.current();
        boolean accepted = switch (tab) {
            case ORDERS -> selectedOrderId >= 0 && mirror.requestCancelOrder(selectedOrderId);
            case LOGISTICS -> selectedLogisticsId >= 0 && mirror.requestCancelLogistics(selectedLogisticsId);
            default -> false;
        };
        showFeedback(accepted ? COMMAND_SENT : COMMAND_UNAVAILABLE);
        resetCancelConfirmation();
    }

    private void togglePriority() {
        int index = findLogisticsIndex(selectedLogisticsId);
        boolean accepted = index >= 0 && ArmyClientState.current().requestSetLogisticsPriority(
                selectedLogisticsId, !cachedMirror.logisticsHighPriority(index));
        showFeedback(accepted ? COMMAND_SENT : COMMAND_UNAVAILABLE);
    }

    private void showFeedback(Component message) {
        feedback = message;
        feedbackUntilMillis = System.currentTimeMillis() + 1800L;
    }

    private void refreshMirror(boolean force) {
        ArmyClientMirror mirror = ArmyClientState.current();
        long revision = mirror.revision();
        if (!force && mirror == cachedMirror && revision == cachedRevision) {
            return;
        }
        cachedMirror = mirror;
        cachedRevision = revision;
        factionCountText = integer(mirror.factionCount());
        armyCountText = integer(mirror.armyCount());
        unitCountText = integer(mirror.totalUnitCount());
        logisticsCountText = integer(mirror.logisticsCount());
        validateSelection();
        refreshSelectionText();
        updateButtons();
    }

    /** Rebuilds dynamic numeric/combined labels once per mirror revision or selection change. */
    private void refreshSelectionText() {
        int faction = findFactionIndex(selectedFactionId);
        if (faction >= 0) {
            selectedFactionReputationText = signed(cachedMirror.factionReputation(faction));
            selectedFactionInfluenceText = integer(cachedMirror.factionInfluence(faction));
            selectedFactionSettlementText = integer(cachedMirror.factionSettlementCount(faction));
            selectedFactionArmyCountText = integer(cachedMirror.factionArmyCount(faction));
            selectedFactionPopulationText = integer(cachedMirror.factionPopulation(faction));
        }

        int army = findArmyIndex(selectedArmyId);
        if (army >= 0) {
            int strength = cachedMirror.armyUnitCount(army);
            selectedArmyStrengthText = integer(strength);
            selectedArmyReadyText = ratio(cachedMirror.armyReadyUnitCount(army), strength);
            selectedArmyMoraleText = percent(cachedMirror.armyMoralePercent(army));
            selectedArmySupplyText = percent(cachedMirror.armySupplyPercent(army));
            selectedArmySpeedText = percent(cachedMirror.armySpeedPercent(army));
        }

        int order = findOrderIndex(selectedOrderId);
        if (order >= 0) {
            selectedOrderIssuedText = longInteger(cachedMirror.orderIssuedGameTime(order));
        }

        int logistics = findLogisticsIndex(selectedLogisticsId);
        if (logistics >= 0) {
            selectedLogisticsCargoText = safe(cachedMirror.logisticsCargo(logistics))
                    + " ×" + cachedMirror.logisticsCargoCount(logistics);
            selectedLogisticsRouteText = safe(cachedMirror.logisticsSource(logistics))
                    + " → " + safe(cachedMirror.logisticsDestination(logistics));
            selectedLogisticsProgressText = percent(cachedMirror.logisticsProgressPercent(logistics));
            selectedLogisticsRiskText = percent(cachedMirror.logisticsRiskPercent(logistics));
            selectedLogisticsPriorityText = cachedMirror.logisticsHighPriority(logistics)
                    ? PRIORITY_HIGH
                    : PRIORITY_NORMAL;
        }
    }

    private void validateSelection() {
        if (findFactionIndex(selectedFactionId) < 0) {
            selectedFactionId = cachedMirror.factionCount() == 0 ? -1 : cachedMirror.factionId(0);
        }
        if (!hasSelectedArmy || findArmyIndex(selectedArmyId) < 0) {
            hasSelectedArmy = cachedMirror.armyCount() != 0;
            if (hasSelectedArmy) {
                selectedArmyId = cachedMirror.armyId(0);
            }
        }
        if (findOrderIndex(selectedOrderId) < 0) {
            selectedOrderId = cachedMirror.orderCount() == 0 ? -1L : cachedMirror.orderId(0);
        }
        if (findLogisticsIndex(selectedLogisticsId) < 0) {
            selectedLogisticsId = cachedMirror.logisticsCount() == 0 ? -1 : cachedMirror.logisticsId(0);
        }
    }

    private void updateButtons() {
        boolean armyActions = tab == StrategicTab.ARMIES && hasSelectedArmy;
        setState(holdButton, armyActions, tab == StrategicTab.ARMIES);
        setState(moveButton, armyActions, tab == StrategicTab.ARMIES);
        setState(rallyButton, armyActions, tab == StrategicTab.ARMIES);
        setState(logisticsButton, armyActions, tab == StrategicTab.ARMIES);
        boolean cancelVisible = tab == StrategicTab.ORDERS || tab == StrategicTab.LOGISTICS;
        boolean cancelActive = tab == StrategicTab.ORDERS ? selectedOrderId >= 0 : selectedLogisticsId >= 0;
        setState(cancelButton, cancelActive, cancelVisible);
        setState(priorityButton, selectedLogisticsId >= 0, tab == StrategicTab.LOGISTICS);
    }

    private static void setState(Button button, boolean active, boolean visible) {
        if (button != null) {
            button.active = active;
            button.visible = visible;
        }
    }

    private int currentListCount() {
        return switch (tab) {
            case FACTIONS -> cachedMirror.factionCount();
            case ARMIES -> cachedMirror.armyCount();
            case ORDERS -> cachedMirror.orderCount();
            case LOGISTICS -> cachedMirror.logisticsCount();
            default -> 0;
        };
    }

    private boolean insideList(double x, double y) {
        return tab != StrategicTab.OVERVIEW
                && x >= contentX + 2 && x < contentX + listWidth
                && y >= contentY + 2 && y < contentY + contentHeight - 2;
    }

    private int findFactionIndex(int id) {
        for (int i = 0, count = cachedMirror.factionCount(); i < count; i++) {
            if (cachedMirror.factionId(i) == id) return i;
        }
        return -1;
    }

    private int findArmyIndex(int id) {
        for (int i = 0, count = cachedMirror.armyCount(); i < count; i++) {
            if (cachedMirror.armyId(i) == id) return i;
        }
        return -1;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
            int direction = keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 1;
            int next = Math.floorMod(tab.ordinal() + direction, StrategicTab.VALUES.length);
            selectTab(StrategicTab.VALUES[next]);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
            int count = currentListCount();
            if (count > 0 && tab != StrategicTab.OVERVIEW) {
                int current = selectedListIndex();
                int direction = keyCode == GLFW.GLFW_KEY_UP ? -1 : 1;
                int next = Math.max(0, Math.min(count - 1, (current < 0 ? 0 : current) + direction));
                selectIndex(next);
                int visible = Math.max(1, (contentHeight - 6) / ROW_HEIGHT);
                int scroll = scrollRows[tab.ordinal()];
                if (next < scroll) {
                    scroll = next;
                } else if (next >= scroll + visible) {
                    scroll = next - visible + 1;
                }
                scrollRows[tab.ordinal()] = scroll;
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private int selectedListIndex() {
        return switch (tab) {
            case FACTIONS -> findFactionIndex(selectedFactionId);
            case ARMIES -> findArmyIndex(selectedArmyId);
            case ORDERS -> findOrderIndex(selectedOrderId);
            case LOGISTICS -> findLogisticsIndex(selectedLogisticsId);
            default -> -1;
        };
    }

    private int detailStep(int rowsAfterTitle) {
        return Math.max(10, Math.min(16,
                (contentHeight - 29) / Math.max(1, rowsAfterTitle - 1)));
    }

    private void resetCancelConfirmation() {
        cancelConfirmUntilMillis = 0L;
        if (cancelButton != null) {
            cancelButton.setMessage(CANCEL);
        }
    }

    private int findOrderIndex(long id) {
        if (id < 0L) return -1;
        for (int i = 0, count = cachedMirror.orderCount(); i < count; i++) {
            if (cachedMirror.orderId(i) == id) return i;
        }
        return -1;
    }

    private int findLogisticsIndex(int id) {
        if (id < 0) return -1;
        for (int i = 0, count = cachedMirror.logisticsCount(); i < count; i++) {
            if (cachedMirror.logisticsId(i) == id) return i;
        }
        return -1;
    }

    private Component relationText(byte code) {
        return arrayText(RELATIONS, code);
    }

    private Component orderText(int code) {
        return arrayText(ORDER_TYPES, code);
    }

    private Component logisticsText(byte code) {
        return arrayText(LOGISTICS_STATES, code);
    }

    private static Component arrayText(Component[] values, int code) {
        return code >= 0 && code < values.length ? values[code] : UNKNOWN;
    }

    private static Component[] translatedArray(String prefix, String... suffixes) {
        Component[] result = new Component[suffixes.length];
        for (int i = 0; i < suffixes.length; i++) {
            result[i] = Component.translatable(prefix + suffixes[i]);
        }
        return result;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String integer(int value) {
        return Integer.toString(value);
    }

    private static String longInteger(long value) {
        return Long.toString(value);
    }

    private static String signed(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }

    private static String percent(int value) {
        return value + "%";
    }

    private static String ratio(int first, int second) {
        return first + " / " + second;
    }

    private static int clampScroll(int value, int count, int visible) {
        return Math.max(0, Math.min(value, Math.max(0, count - visible)));
    }

    private static void outline(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y, x + 1, y + height, color);
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }

    private enum ListKind {
        FACTION {
            @Override String name(ArmyClientMirror mirror, int index) { return safe(mirror.factionName(index)); }
            @Override String summary(ArmyClientMirror mirror, int index) { return safe(mirror.factionSummary(index)); }
            @Override boolean isSelected(MillenaireCommandScreen screen, int index) {
                return screen.selectedFactionId == screen.cachedMirror.factionId(index);
            }
        },
        ARMY {
            @Override String name(ArmyClientMirror mirror, int index) { return safe(mirror.armyName(index)); }
            @Override String summary(ArmyClientMirror mirror, int index) { return safe(mirror.armySummary(index)); }
            @Override boolean isSelected(MillenaireCommandScreen screen, int index) {
                return screen.hasSelectedArmy && screen.selectedArmyId == screen.cachedMirror.armyId(index);
            }
        },
        ORDER {
            @Override String name(ArmyClientMirror mirror, int index) { return safe(mirror.orderArmyName(index)); }
            @Override String summary(ArmyClientMirror mirror, int index) { return safe(mirror.orderSummary(index)); }
            @Override boolean isSelected(MillenaireCommandScreen screen, int index) {
                return screen.selectedOrderId == screen.cachedMirror.orderId(index);
            }
        },
        LOGISTICS {
            @Override String name(ArmyClientMirror mirror, int index) { return safe(mirror.logisticsName(index)); }
            @Override String summary(ArmyClientMirror mirror, int index) { return safe(mirror.logisticsSummary(index)); }
            @Override boolean isSelected(MillenaireCommandScreen screen, int index) {
                return screen.selectedLogisticsId == screen.cachedMirror.logisticsId(index);
            }
        };

        abstract String name(ArmyClientMirror mirror, int index);
        abstract String summary(ArmyClientMirror mirror, int index);
        abstract boolean isSelected(MillenaireCommandScreen screen, int index);
    }
}
