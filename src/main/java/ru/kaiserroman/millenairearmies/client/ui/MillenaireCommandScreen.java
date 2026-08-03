package ru.kaiserroman.millenairearmies.client.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import ru.kaiserroman.millenairearmies.client.ArmyClientMirror;
import ru.kaiserroman.millenairearmies.client.ArmyClientState;
import ru.kaiserroman.millenairearmies.network.ArmiesProtocol;
import ru.kaiserroman.millenairearmies.persistence.RealmGovernanceSavedData;

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
    private static final Component NO_SETTLEMENTS = Component.translatable("gui.millenaire_armies.empty.settlements");
    private static final Component NO_RECRUITS = Component.translatable("gui.millenaire_armies.empty.recruits");
    private static final Component SELECT_ARMY = Component.translatable("gui.millenaire_armies.recruit.select_army");
    private static final Component SELECT_RECRUITS = Component.translatable("gui.millenaire_armies.recruit.select_recruits");
    private static final Component CREATE_ARMY = Component.translatable("gui.millenaire_armies.action.create_army");
    private static final Component RECRUIT = Component.translatable("gui.millenaire_armies.action.recruit");
    private static final Component RETRY = Component.translatable("gui.millenaire_armies.action.retry");
    private static final Component SYNC_TIMEOUT = Component.translatable("gui.millenaire_armies.state.timeout");
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
    private static final Component SECTION_SETTLEMENTS = Component.translatable("gui.millenaire_armies.section.settlements");
    private static final Component LABEL_RECRUITS = Component.translatable("gui.millenaire_armies.section.recruits");
    private static final Component LABEL_TARGET_ARMY = Component.translatable("gui.millenaire_armies.label.target_army");
    private static final Component LABEL_FORMATION = Component.translatable("gui.millenaire_armies.label.formation");
    private static final Component SECTION_GOVERNANCE = Component.translatable("gui.millenaire_armies.section.governance");
    private static final Component SECTION_ECONOMY = Component.translatable("gui.millenaire_armies.section.economy");
    private static final Component LABEL_ROLE = Component.translatable("gui.millenaire_armies.label.role");
    private static final Component LABEL_GOVERNMENT = Component.translatable("gui.millenaire_armies.label.government");
    private static final Component LABEL_CONTROLLED_SETTLEMENT = Component.translatable("gui.millenaire_armies.label.controlled_settlement");
    private static final Component LABEL_REGIONS = Component.translatable("gui.millenaire_armies.label.regions");
    private static final Component LABEL_TREASURY = Component.translatable("gui.millenaire_armies.label.treasury");
    private static final Component LABEL_TAX_RATE = Component.translatable("gui.millenaire_armies.label.tax_rate");
    private static final Component LABEL_CAPTURES = Component.translatable("gui.millenaire_armies.label.captures");
    private static final Component LABEL_RESOURCES = Component.translatable("gui.millenaire_armies.label.resources");
    private static final Component REALM_NOT_FOUNDED = Component.translatable("gui.millenaire_armies.realm.not_founded");
    private static final Component REALM_FOUND_HINT = Component.translatable("gui.millenaire_armies.realm.found_hint");
    private static final Component REALM_NO_CAPITAL = Component.translatable("gui.millenaire_armies.realm.no_capital");
    private static final Component REALM_CAPTURE_LOCKED = Component.translatable("gui.millenaire_armies.realm.capture_locked");
    private static final Component REALM_TAX_HINT = Component.translatable("gui.millenaire_armies.realm.tax_hint");

    private static final Component[] RELATIONS = translatedArray("gui.millenaire_armies.relation.",
            "hostile", "neutral", "friendly", "allied", "vassal");
    private static final Component[] ORDER_TYPES = translatedArray("gui.millenaire_armies.order.",
            "hold", "move", "rally", "logistics", "attack");
    private static final Component[] FORMATIONS = translatedArray("gui.millenaire_armies.formation.",
            "line", "column", "wedge", "square", "skirmish");
    private static final Component[] REALM_ROLES = translatedArray("gui.millenaire_armies.realm.role.",
            "none", "head", "feudal", "governor");
    private static final Component[] REALM_GOVERNMENTS = translatedArray("gui.millenaire_armies.realm.government.",
            "none", "feudal", "administrative");
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
    private static final int ORDER_ATTACK = 4;

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
    private long selectedSettlementMost;
    private long selectedSettlementLeast;
    private boolean hasSelectedSettlement;
    private int selectedRecruitRow = -1;
    private int rosterColumn;
    private int settlementScroll;
    private int recruitScroll;
    private final long[] selectedRecruitBits = new long[ArmiesProtocol.MAX_RECRUITS_PER_INTENT * 2];
    private int selectedRecruitCount;

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
    private String selectedLogisticsCargoText = "";
    private String selectedLogisticsRouteText = "";
    private String selectedLogisticsProgressText = "0%";
    private String selectedLogisticsRiskText = "0%";
    private Component selectedLogisticsPriorityText = PRIORITY_NORMAL;
    private Component feedback;
    private long feedbackUntilMillis;
    private long cancelConfirmUntilMillis;
    private int lastAcknowledgementId;

    private Button holdButton;
    private Button moveButton;
    private Button rallyButton;
    private Button attackButton;
    private Button formationButton;
    private Button logisticsButton;
    private Button cancelButton;
    private Button priorityButton;
    private Button createArmyButton;
    private Button recruitButton;
    private Button foundRealmButton;
    private Button taxLessButton;
    private Button taxMoreButton;
    private Button retryButton;
    private int syncTicks;
    private int waitingTicks;

    public MillenaireCommandScreen() {
        super(TITLE);
    }

    public MillenaireCommandScreen(int preferredArmyId) {
        this();
        selectedArmyId = preferredArmyId;
        hasSelectedArmy = true;
        tab = StrategicTab.ARMIES;
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
        contentHeight = panelHeight - 129;
        listWidth = Math.max(126, Math.min(295, contentWidth * 42 / 100));

        tabWidth = Math.max(40, (panelWidth - 18) / StrategicTab.VALUES.length);
        tabsX = panelX + 9;
        for (int i = 0; i < StrategicTab.VALUES.length; i++) {
            final StrategicTab target = StrategicTab.VALUES[i];
            Button button = Button.builder(target.title, ignored -> selectTab(target))
                    .bounds(tabsX + i * tabWidth, panelY + 39, tabWidth - 2, 20)
                    .build();
            tabButtons[i] = addRenderableWidget(button);
        }

        int actionY = panelY + panelHeight - 30;
        int commandY = actionY - 24;
        int actionAreaWidth = panelWidth - 86;
        int orderButtonWidth = Math.max(42, (actionAreaWidth - 6) / 4);
        int actionX = panelX + 10;
        holdButton = actionButton("gui.millenaire_armies.action.hold", actionX, commandY, orderButtonWidth, ORDER_HOLD);
        moveButton = actionButton("gui.millenaire_armies.action.move", actionX + orderButtonWidth + 2, commandY, orderButtonWidth, ORDER_MOVE);
        rallyButton = actionButton("gui.millenaire_armies.action.rally", actionX + (orderButtonWidth + 2) * 2, commandY, orderButtonWidth, ORDER_RALLY);
        attackButton = actionButton("gui.millenaire_armies.action.attack", actionX + (orderButtonWidth + 2) * 3, commandY, orderButtonWidth, ORDER_ATTACK);

        int secondaryButtonWidth = Math.max(72, (actionAreaWidth - 4) / 2);
        formationButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.millenaire_armies.action.formation"), ignored -> cycleFormation())
                .bounds(actionX, actionY, secondaryButtonWidth, 20)
                .build());
        logisticsButton = actionButton(
                "gui.millenaire_armies.action.logistics",
                actionX + secondaryButtonWidth + 4,
                actionY,
                secondaryButtonWidth,
                ORDER_LOGISTICS);
        cancelButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.millenaire_armies.action.cancel"), ignored -> cancelSelected())
                .bounds(actionX, actionY, secondaryButtonWidth, 20)
                .build());
        priorityButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.millenaire_armies.action.priority"), ignored -> togglePriority())
                .bounds(actionX + secondaryButtonWidth + 4, actionY, secondaryButtonWidth, 20)
                .build());
        createArmyButton = addRenderableWidget(Button.builder(CREATE_ARMY, ignored -> createArmy())
                .bounds(actionX, actionY, secondaryButtonWidth, 20)
                .build());
        recruitButton = addRenderableWidget(Button.builder(RECRUIT, ignored -> recruitSelected())
                .bounds(actionX + secondaryButtonWidth + 4, actionY, secondaryButtonWidth, 20)
                .build());
        foundRealmButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.millenaire_armies.action.found_realm"),
                        ignored -> foundRealm())
                .bounds(actionX, actionY, actionAreaWidth, 20)
                .build());
        taxLessButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.millenaire_armies.action.tax_down"),
                        ignored -> adjustRealmTax(-5))
                .bounds(actionX, actionY, secondaryButtonWidth, 20)
                .build());
        taxMoreButton = addRenderableWidget(Button.builder(
                        Component.translatable("gui.millenaire_armies.action.tax_up"),
                        ignored -> adjustRealmTax(5))
                .bounds(actionX + secondaryButtonWidth + 4, actionY, secondaryButtonWidth, 20)
                .build());
        retryButton = addRenderableWidget(Button.builder(RETRY, ignored -> retrySync())
                .bounds(actionX, actionY, secondaryButtonWidth, 20)
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
    public void tick() {
        super.tick();
        waitingTicks = cachedMirror.isReady() ? 0 : waitingTicks + 1;
        if (++syncTicks >= 20) {
            syncTicks = 0;
            ArmyClientState.current().requestFullSync();
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        refreshMirror(false);
        drawFrame(graphics);

        if (!cachedMirror.isReady()) {
            graphics.drawCenteredString(font, WAITING, width / 2, contentY + contentHeight / 2 - 5, MUTED);
            String status = waitingTicks >= 100 ? SYNC_TIMEOUT.getString() : safe(cachedMirror.statusText());
            if (!status.isEmpty()) {
                graphics.drawCenteredString(font, status, width / 2, contentY + contentHeight / 2 + 10, MUTED);
            }
        } else {
            graphics.enableScissor(contentX + 1, contentY + 1, contentX + contentWidth - 1, contentY + contentHeight - 1);
            switch (tab) {
                case OVERVIEW -> renderOverview(graphics);
                case RECRUITMENT -> renderRecruitment(graphics, mouseX, mouseY);
                case REALM -> renderRealm(graphics, mouseX, mouseY);
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
        String realm = cachedMirror.realmFounded()
                ? safe(cachedMirror.realmName())
                : safe(cachedMirror.playerFactionName());
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

    private void renderRecruitment(GuiGraphics graphics, int mouseX, int mouseY) {
        int split = contentX + listWidth;
        graphics.fill(contentX + 1, contentY + 1, split, contentY + contentHeight - 1, PANEL_ALT);
        graphics.fill(split, contentY + 1, split + 1, contentY + contentHeight - 1, BORDER_DARK);
        graphics.drawString(font, SECTION_SETTLEMENTS, contentX + 7, contentY + 6, MUTED, false);
        graphics.drawString(font, LABEL_RECRUITS, split + 8, contentY + 6, MUTED, false);

        int settlementCount = cachedMirror.settlementCount();
        int listY = contentY + 20;
        int listHeight = contentHeight - 35;
        int visible = Math.max(1, listHeight / ROW_HEIGHT);
        settlementScroll = clampScroll(settlementScroll, settlementCount, visible);
        if (settlementCount == 0) {
            graphics.drawCenteredString(font, NO_SETTLEMENTS,
                    contentX + listWidth / 2, contentY + contentHeight / 2, MUTED);
        } else {
            int end = Math.min(settlementCount, settlementScroll + visible);
            for (int row = settlementScroll; row < end; row++) {
                int y = listY + (row - settlementScroll) * ROW_HEIGHT;
                boolean selected = settlementSelected(row);
                boolean hovered = mouseX >= contentX + 3 && mouseX < split - 2
                        && mouseY >= y && mouseY < y + ROW_HEIGHT - 2;
                if (selected || hovered) {
                    graphics.fill(contentX + 3, y, split - 2, y + ROW_HEIGHT - 2,
                            selected ? SELECTED : HOVER);
                }
                graphics.drawString(font, safe(cachedMirror.settlementName(row)),
                        contentX + 8, y + 5, selected ? GOLD : TEXT, true);
                Component summary = Component.translatable("gui.millenaire_armies.summary.settlement",
                        cachedMirror.settlementPopulation(row),
                        cachedMirror.settlementAvailableRecruitCount(row));
                graphics.drawString(font, summary, contentX + 8, y + 16, MUTED, false);
            }
        }

        int recruitCount = filteredRecruitCount();
        recruitScroll = clampScroll(recruitScroll, recruitCount, visible);
        int rightX = split + 2;
        int rightWidth = contentX + contentWidth - rightX;
        if (!hasSelectedSettlement) {
            graphics.drawCenteredString(font, NO_RECRUITS,
                    rightX + rightWidth / 2, contentY + contentHeight / 2, MUTED);
            return;
        }
        if (recruitCount == 0) {
            graphics.drawCenteredString(font, NO_RECRUITS,
                    rightX + rightWidth / 2, contentY + contentHeight / 2, MUTED);
        } else {
            int end = Math.min(recruitCount, recruitScroll + visible);
            for (int ordinal = recruitScroll; ordinal < end; ordinal++) {
                int row = filteredRecruitRow(ordinal);
                int y = listY + (ordinal - recruitScroll) * ROW_HEIGHT;
                boolean selected = recruitSelected(row);
                boolean focused = selectedRecruitRow == row;
                boolean hovered = mouseX >= rightX + 2 && mouseX < contentX + contentWidth - 2
                        && mouseY >= y && mouseY < y + ROW_HEIGHT - 2;
                if (selected || focused || hovered) {
                    graphics.fill(rightX + 2, y, contentX + contentWidth - 2, y + ROW_HEIGHT - 2,
                            selected ? SELECTED : HOVER);
                }
                String marker = selected ? "✓ " : "";
                graphics.drawString(font, marker + safe(cachedMirror.recruitName(row)),
                        rightX + 7, y + 5, selected ? GOLD : TEXT, true);
                Component summary = Component.translatable("gui.millenaire_armies.summary.recruit",
                        safe(cachedMirror.recruitRole(row)), cachedMirror.recruitStrength(row));
                graphics.drawString(font, summary, rightX + 7, y + 16, MUTED, false);
            }
        }

        Component state = !hasSelectedArmy
                ? SELECT_ARMY
                : selectedRecruitCount == 0
                        ? SELECT_RECRUITS
                        : Component.translatable("gui.millenaire_armies.recruit.ready",
                                selectedRecruitCount, safe(selectedArmyName()));
        graphics.drawString(font, state, split + 8, contentY + contentHeight - 12, GOLD, false);
    }

    private void renderRealm(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!cachedMirror.realmFounded()) {
            renderRealmFoundation(graphics, mouseX, mouseY);
            return;
        }

        int gap = 6;
        int half = (contentWidth - gap) / 2;
        int boxHeight = contentHeight;
        sectionBox(graphics, contentX, contentY, half, boxHeight, SECTION_GOVERNANCE);
        sectionBox(graphics, contentX + half + gap, contentY, half, boxHeight, SECTION_ECONOMY);

        int step = Math.max(9, Math.min(14, (boxHeight - 28) / 8));
        int leftX = contentX + 8;
        int leftY = contentY + 19;
        graphics.drawString(font, safe(cachedMirror.realmName()), leftX, leftY, GOLD, true);
        drawPair(graphics, leftX, leftY + step, LABEL_ROLE, realmRoleText(cachedMirror.realmRoleCode()));
        drawPair(graphics, leftX, leftY + step * 2, LABEL_GOVERNMENT,
                realmGovernmentText(cachedMirror.realmGovernmentCode()));
        drawPair(graphics, leftX, leftY + step * 3, LABEL_CAPITAL, safe(cachedMirror.realmCapitalName()));
        drawPair(graphics, leftX, leftY + step * 4, LABEL_CONTROLLED_SETTLEMENT,
                safe(cachedMirror.realmControlledSettlementName()));
        drawPair(graphics, leftX, leftY + step * 5, LABEL_SETTLEMENTS,
                integer(cachedMirror.realmSettlementCount()));
        drawPair(graphics, leftX, leftY + step * 6, LABEL_REGIONS,
                integer(cachedMirror.realmRegionCount()));
        drawPair(graphics, leftX, leftY + step * 7, LABEL_POPULATION,
                integer(cachedMirror.realmPopulation()));
        if (boxHeight >= 142) {
            drawWrapped(graphics, realmAuthorityHint(cachedMirror.realmRoleCode()), leftX,
                    leftY + step * 8 + 3, half - 16, MUTED, 3);
        }

        int rightX = contentX + half + gap + 8;
        int rightY = contentY + 19;
        drawPair(graphics, rightX, rightY, LABEL_TREASURY, longInteger(cachedMirror.realmTreasury()));
        drawPair(graphics, rightX, rightY + step, LABEL_TAX_RATE, percent(cachedMirror.realmTaxRate()));
        drawPair(graphics, rightX, rightY + step * 2, LABEL_CAPTURES,
                integer(cachedMirror.realmCapturedSettlementCount()));
        graphics.drawString(font, LABEL_RESOURCES, rightX, rightY + step * 3, MUTED, false);
        drawPair(graphics, rightX, rightY + step * 4,
                Component.translatable("gui.millenaire_armies.resource.food"),
                integer(cachedMirror.realmFood()));
        drawPair(graphics, rightX, rightY + step * 5,
                Component.translatable("gui.millenaire_armies.resource.iron"),
                integer(cachedMirror.realmIron()));
        drawPair(graphics, rightX, rightY + step * 6,
                Component.translatable("gui.millenaire_armies.resource.leather"),
                integer(cachedMirror.realmLeather()));
        drawPair(graphics, rightX, rightY + step * 7,
                Component.translatable("gui.millenaire_armies.resource.arrows"),
                integer(cachedMirror.realmArrows()));
        if (boxHeight >= 142) {
            drawWrapped(graphics, REALM_TAX_HINT, rightX, rightY + step * 8 + 3,
                    half - 16, MUTED, 3);
        }
    }

    private void renderRealmFoundation(GuiGraphics graphics, int mouseX, int mouseY) {
        int split = contentX + listWidth;
        graphics.fill(contentX + 1, contentY + 1, split, contentY + contentHeight - 1, PANEL_ALT);
        graphics.fill(split, contentY + 1, split + 1, contentY + contentHeight - 1, BORDER_DARK);
        graphics.drawString(font, SECTION_SETTLEMENTS, contentX + 7, contentY + 6, MUTED, false);

        int settlementCount = cachedMirror.settlementCount();
        int listY = contentY + 20;
        int visible = Math.max(1, (contentHeight - 24) / ROW_HEIGHT);
        settlementScroll = clampScroll(settlementScroll, settlementCount, visible);
        if (settlementCount == 0) {
            graphics.drawCenteredString(font, NO_SETTLEMENTS,
                    contentX + listWidth / 2, contentY + contentHeight / 2, MUTED);
        } else {
            int end = Math.min(settlementCount, settlementScroll + visible);
            for (int row = settlementScroll; row < end; row++) {
                int y = listY + (row - settlementScroll) * ROW_HEIGHT;
                boolean selected = settlementSelected(row);
                boolean hovered = mouseX >= contentX + 3 && mouseX < split - 2
                        && mouseY >= y && mouseY < y + ROW_HEIGHT - 2;
                if (selected || hovered) {
                    graphics.fill(contentX + 3, y, split - 2, y + ROW_HEIGHT - 2,
                            selected ? SELECTED : HOVER);
                }
                graphics.drawString(font, safe(cachedMirror.settlementName(row)),
                        contentX + 8, y + 5, selected ? GOLD : TEXT, true);
                Component summary = Component.translatable("gui.millenaire_armies.summary.settlement",
                        cachedMirror.settlementPopulation(row),
                        cachedMirror.settlementAvailableRecruitCount(row));
                graphics.drawString(font, summary, contentX + 8, y + 16, MUTED, false);
            }
        }

        int rightX = split + 9;
        int rightWidth = contentX + contentWidth - rightX - 7;
        graphics.drawString(font, REALM_NOT_FOUNDED, rightX, contentY + 8, GOLD, true);
        int textY = drawWrapped(graphics, REALM_FOUND_HINT, rightX, contentY + 24,
                rightWidth, MUTED, 4);
        int selected = findSettlementIndex(selectedSettlementMost, selectedSettlementLeast);
        if (selected < 0) {
            drawWrapped(graphics, REALM_NO_CAPITAL, rightX, textY + 6, rightWidth, TEXT, 3);
        } else {
            Component candidate = Component.translatable(
                    "gui.millenaire_armies.realm.capital_candidate",
                    safe(cachedMirror.settlementName(selected)),
                    cachedMirror.settlementPopulation(selected));
            drawWrapped(graphics, candidate, rightX, textY + 6, rightWidth, TEXT, 3);
        }
        if (contentHeight >= 112) {
            drawWrapped(graphics, REALM_CAPTURE_LOCKED, rightX,
                    contentY + contentHeight - 30, rightWidth, MUTED, 3);
        }
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
        drawPair(graphics, x, y + step, LABEL_CULTURE, safe(cachedMirror.factionCultureName(index)));
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
        drawPair(graphics, x, y + 14, LABEL_CULTURE, safe(cachedMirror.factionCultureName(index)));
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
        int step = detailStep(11);
        graphics.drawString(font, safe(cachedMirror.armyName(index)), x, y, GOLD, true);
        drawPair(graphics, x, y + 14, LABEL_FACTION, safe(cachedMirror.armyFactionName(index)));
        drawPair(graphics, x, y + 14 + step, LABEL_LOCATION, safe(cachedMirror.armyLocation(index)));
        drawPair(graphics, x, y + 14 + step * 2, LABEL_STRENGTH, selectedArmyStrengthText);
        drawPair(graphics, x, y + 14 + step * 3, LABEL_READY, selectedArmyReadyText);
        drawPair(graphics, x, y + 14 + step * 4, LABEL_MORALE, selectedArmyMoraleText);
        drawPair(graphics, x, y + 14 + step * 5, LABEL_SUPPLIES, selectedArmySupplyText);
        drawPair(graphics, x, y + 14 + step * 6, LABEL_SPEED, selectedArmySpeedText);
        drawPair(graphics, x, y + 14 + step * 7, LABEL_FORMATION, formationText(cachedMirror.armyFormationCode(index)));
        drawPair(graphics, x, y + 14 + step * 8, LABEL_ORDER, orderText(cachedMirror.armyOrderTypeCode(index)));
        drawPair(graphics, x, y + 14 + step * 9, LABEL_TARGET, safe(cachedMirror.armyOrderTarget(index)));
        drawPair(graphics, x, y + 14 + step * 10, LABEL_COMPOSITION, safe(cachedMirror.armyComposition(index)));
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
        drawPair(graphics, x, y + 38, Component.translatable("gui.millenaire_armies.label.destination"),
                selectedLogisticsRouteText);
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

    private int drawWrapped(
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

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean settlementSelector = tab == StrategicTab.RECRUITMENT
                || tab == StrategicTab.REALM && !cachedMirror.realmFounded();
        if (button == 0 && cachedMirror.isReady() && settlementSelector
                && mouseY >= contentY + 20 && mouseY < contentY + contentHeight - 2) {
            int reserved = tab == StrategicTab.RECRUITMENT ? 35 : 24;
            int visible = Math.max(1, (contentHeight - reserved) / ROW_HEIGHT);
            if (mouseX >= contentX + 2 && mouseX < contentX + listWidth) {
                int row = settlementScroll + ((int) mouseY - contentY - 20) / ROW_HEIGHT;
                if (row >= 0 && row < cachedMirror.settlementCount()
                        && row < settlementScroll + visible) {
                    selectSettlement(row);
                    return true;
                }
            } else if (tab == StrategicTab.RECRUITMENT
                    && mouseX >= contentX + listWidth + 2 && mouseX < contentX + contentWidth) {
                int ordinal = recruitScroll + ((int) mouseY - contentY - 20) / ROW_HEIGHT;
                if (ordinal >= 0 && ordinal < filteredRecruitCount()
                        && ordinal < recruitScroll + visible) {
                    selectedRecruitRow = filteredRecruitRow(ordinal);
                    toggleRecruit(selectedRecruitRow);
                    rosterColumn = 1;
                    updateButtons();
                    return true;
                }
            }
        }
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
        boolean settlementSelector = tab == StrategicTab.RECRUITMENT
                || tab == StrategicTab.REALM && !cachedMirror.realmFounded();
        if (settlementSelector
                && mouseY >= contentY + 2 && mouseY < contentY + contentHeight - 2) {
            int direction = scrollY > 0.0D ? -1 : scrollY < 0.0D ? 1 : 0;
            int reserved = tab == StrategicTab.RECRUITMENT ? 35 : 24;
            int visible = Math.max(1, (contentHeight - reserved) / ROW_HEIGHT);
            if (mouseX < contentX + listWidth || tab == StrategicTab.REALM) {
                settlementScroll = clampScroll(
                        settlementScroll + direction, cachedMirror.settlementCount(), visible);
            } else {
                recruitScroll = clampScroll(recruitScroll + direction, filteredRecruitCount(), visible);
            }
            return direction != 0;
        }
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

    private void selectSettlement(int row) {
        selectedSettlementMost = cachedMirror.settlementUuidMost(row);
        selectedSettlementLeast = cachedMirror.settlementUuidLeast(row);
        hasSelectedSettlement = true;
        selectedRecruitRow = filteredRecruitCount() == 0 ? -1 : filteredRecruitRow(0);
        selectedRecruitCount = 0;
        recruitScroll = 0;
        rosterColumn = 0;
        updateButtons();
    }

    private void createArmy() {
        boolean requested = hasSelectedSettlement && ArmyClientState.current().requestCreateArmy(
                selectedSettlementMost, selectedSettlementLeast);
        showFeedback(requested ? COMMAND_SENT : COMMAND_UNAVAILABLE);
    }

    private void foundRealm() {
        boolean requested = hasSelectedSettlement && ArmyClientState.current().requestFoundRealm(
                selectedSettlementMost, selectedSettlementLeast);
        showFeedback(requested ? COMMAND_SENT : COMMAND_UNAVAILABLE);
    }

    private void adjustRealmTax(int delta) {
        int next = Math.max(0, Math.min(25, cachedMirror.realmTaxRate() + delta));
        boolean requested = next != cachedMirror.realmTaxRate()
                && ArmyClientState.current().requestSetRealmTax(next);
        showFeedback(requested ? COMMAND_SENT : COMMAND_UNAVAILABLE);
    }

    private void retrySync() {
        waitingTicks = 0;
        ArmyClientState.current().requestFullSync();
    }

    private void recruitSelected() {
        boolean requested = hasSelectedArmy && hasSelectedSettlement && selectedRecruitCount > 0
                && ArmyClientState.current().requestRecruitUnits(
                        selectedArmyId,
                        selectedSettlementMost,
                        selectedSettlementLeast,
                        selectedRecruitCount,
                        selectedRecruitPayload());
        showFeedback(requested ? COMMAND_SENT : COMMAND_UNAVAILABLE);
    }

    private long[] selectedRecruitPayload() {
        long[] result = new long[selectedRecruitCount * 2];
        System.arraycopy(selectedRecruitBits, 0, result, 0, result.length);
        return result;
    }

    private void issueOrder(int typeCode) {
        if (hasSelectedArmy && ArmyClientState.current().requestIssueOrder(selectedArmyId, typeCode)) {
            showFeedback(COMMAND_SENT);
        } else {
            showFeedback(COMMAND_UNAVAILABLE);
        }
    }

    private void cycleFormation() {
        int row = findArmyIndex(selectedArmyId);
        int current = row < 0 ? -1 : cachedMirror.armyFormationCode(row);
        int next = current < 0 || current >= FORMATIONS.length - 1 ? 0 : current + 1;
        boolean requested = row >= 0 && ArmyClientState.current().requestSetFormation(selectedArmyId, next);
        showFeedback(requested ? COMMAND_SENT : COMMAND_UNAVAILABLE);
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
        long revision = mirror.viewVersion();
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
        refreshAcknowledgement();
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

        int logistics = findLogisticsIndex(selectedLogisticsId);
        if (logistics >= 0) {
            selectedLogisticsCargoText = safe(cachedMirror.logisticsCargo(logistics))
                    + " ×" + cachedMirror.logisticsCargoCount(logistics);
            selectedLogisticsRouteText = safe(cachedMirror.logisticsDestination(logistics));
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
        if (findSettlementIndex(selectedSettlementMost, selectedSettlementLeast) < 0) {
            hasSelectedSettlement = cachedMirror.settlementCount() > 0;
            if (hasSelectedSettlement) {
                selectedSettlementMost = cachedMirror.settlementUuidMost(0);
                selectedSettlementLeast = cachedMirror.settlementUuidLeast(0);
            }
        }
        compactSelectedRecruits();
        if (selectedRecruitRow < 0 || selectedRecruitRow >= cachedMirror.recruitCount()
                || !recruitInSelectedSettlement(selectedRecruitRow)) {
            selectedRecruitRow = filteredRecruitCount() == 0 ? -1 : filteredRecruitRow(0);
        }
    }

    private void updateButtons() {
        boolean armyActions = tab == StrategicTab.ARMIES && hasSelectedArmy;
        setState(holdButton, armyActions, tab == StrategicTab.ARMIES);
        setState(moveButton, armyActions, tab == StrategicTab.ARMIES);
        setState(rallyButton, armyActions, tab == StrategicTab.ARMIES);
        setState(attackButton, armyActions, tab == StrategicTab.ARMIES);
        setState(formationButton, armyActions, tab == StrategicTab.ARMIES);
        setState(logisticsButton, armyActions, tab == StrategicTab.ARMIES);
        int armyRow = findArmyIndex(selectedArmyId);
        if (formationButton != null) {
            Component formation = armyRow < 0
                    ? UNKNOWN
                    : formationText(cachedMirror.armyFormationCode(armyRow));
            formationButton.setMessage(Component.translatable(
                    "gui.millenaire_armies.action.formation", formation));
        }
        // These intents do not exist server-side yet, so the UI does not present pretend actions.
        setState(cancelButton, false, false);
        setState(priorityButton, false, false);
        setState(createArmyButton, cachedMirror.isReady() && hasSelectedSettlement,
                tab == StrategicTab.RECRUITMENT);
        setState(recruitButton,
                cachedMirror.isReady() && hasSelectedSettlement && hasSelectedArmy && selectedRecruitCount > 0,
                tab == StrategicTab.RECRUITMENT);
        boolean realmTab = tab == StrategicTab.REALM;
        boolean realmFounded = cachedMirror.realmFounded();
        boolean realmHead = cachedMirror.realmRoleCode() == RealmGovernanceSavedData.ROLE_HEAD;
        setState(foundRealmButton,
                cachedMirror.isReady() && !realmFounded && hasSelectedSettlement,
                realmTab && !realmFounded);
        setState(taxLessButton,
                cachedMirror.isReady() && realmFounded && realmHead && cachedMirror.realmTaxRate() > 0,
                realmTab && realmFounded && realmHead);
        setState(taxMoreButton,
                cachedMirror.isReady() && realmFounded && realmHead && cachedMirror.realmTaxRate() < 25,
                realmTab && realmFounded && realmHead);
        setState(retryButton, !cachedMirror.isReady(), !cachedMirror.isReady());
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
            case RECRUITMENT -> cachedMirror.settlementCount();
            default -> 0;
        };
    }

    private boolean insideList(double x, double y) {
        return tab != StrategicTab.OVERVIEW && tab != StrategicTab.RECRUITMENT
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

    private int findSettlementIndex(long most, long least) {
        if (!hasSelectedSettlement) return -1;
        for (int row = 0; row < cachedMirror.settlementCount(); row++) {
            if (cachedMirror.settlementUuidMost(row) == most
                    && cachedMirror.settlementUuidLeast(row) == least) {
                return row;
            }
        }
        return -1;
    }

    private boolean settlementSelected(int row) {
        return hasSelectedSettlement
                && cachedMirror.settlementUuidMost(row) == selectedSettlementMost
                && cachedMirror.settlementUuidLeast(row) == selectedSettlementLeast;
    }

    private boolean recruitInSelectedSettlement(int row) {
        return hasSelectedSettlement
                && cachedMirror.recruitVillageMost(row) == selectedSettlementMost
                && cachedMirror.recruitVillageLeast(row) == selectedSettlementLeast;
    }

    private int filteredRecruitCount() {
        int count = 0;
        for (int row = 0; row < cachedMirror.recruitCount(); row++) {
            if (recruitInSelectedSettlement(row)) count++;
        }
        return count;
    }

    private int filteredRecruitRow(int ordinal) {
        for (int row = 0; row < cachedMirror.recruitCount(); row++) {
            if (recruitInSelectedSettlement(row) && ordinal-- == 0) return row;
        }
        return -1;
    }

    private int filteredRecruitOrdinal(int requestedRow) {
        int ordinal = 0;
        for (int row = 0; row < cachedMirror.recruitCount(); row++) {
            if (!recruitInSelectedSettlement(row)) continue;
            if (row == requestedRow) return ordinal;
            ordinal++;
        }
        return -1;
    }

    private boolean recruitSelected(int row) {
        long most = cachedMirror.recruitUuidMost(row);
        long least = cachedMirror.recruitUuidLeast(row);
        for (int index = 0; index < selectedRecruitCount; index++) {
            if (selectedRecruitBits[index * 2] == most && selectedRecruitBits[index * 2 + 1] == least) {
                return true;
            }
        }
        return false;
    }

    private void toggleRecruit(int row) {
        long most = cachedMirror.recruitUuidMost(row);
        long least = cachedMirror.recruitUuidLeast(row);
        for (int index = 0; index < selectedRecruitCount; index++) {
            if (selectedRecruitBits[index * 2] == most && selectedRecruitBits[index * 2 + 1] == least) {
                int tail = selectedRecruitCount - index - 1;
                if (tail > 0) {
                    System.arraycopy(selectedRecruitBits, (index + 1) * 2,
                            selectedRecruitBits, index * 2, tail * 2);
                }
                selectedRecruitCount--;
                return;
            }
        }
        if (selectedRecruitCount < ArmiesProtocol.MAX_RECRUITS_PER_INTENT) {
            selectedRecruitBits[selectedRecruitCount * 2] = most;
            selectedRecruitBits[selectedRecruitCount * 2 + 1] = least;
            selectedRecruitCount++;
        } else {
            showFeedback(Component.translatable(
                    "gui.millenaire_armies.recruit.selection_limit",
                    ArmiesProtocol.MAX_RECRUITS_PER_INTENT));
        }
    }

    private void compactSelectedRecruits() {
        int write = 0;
        for (int selected = 0; selected < selectedRecruitCount; selected++) {
            long most = selectedRecruitBits[selected * 2];
            long least = selectedRecruitBits[selected * 2 + 1];
            boolean present = false;
            for (int row = 0; row < cachedMirror.recruitCount(); row++) {
                if (recruitInSelectedSettlement(row)
                        && cachedMirror.recruitUuidMost(row) == most
                        && cachedMirror.recruitUuidLeast(row) == least) {
                    present = true;
                    break;
                }
            }
            if (present) {
                selectedRecruitBits[write * 2] = most;
                selectedRecruitBits[write * 2 + 1] = least;
                write++;
            }
        }
        selectedRecruitCount = write;
    }

    private String selectedArmyName() {
        int row = findArmyIndex(selectedArmyId);
        return row < 0 ? "" : cachedMirror.armyName(row);
    }

    private void refreshAcknowledgement() {
        int actionId = cachedMirror.acknowledgedActionId();
        if (actionId <= lastAcknowledgementId) return;
        lastAcknowledgementId = actionId;
        int result = cachedMirror.acknowledgedResult();
        byte action = cachedMirror.acknowledgedAction();
        if (result == ArmiesProtocol.RESULT_ACCEPTED
                && action == ArmiesProtocol.ACTION_CREATE_ARMY
                && cachedMirror.armyCount() > 0) {
            selectedArmyId = cachedMirror.armyId(cachedMirror.armyCount() - 1);
            hasSelectedArmy = true;
        }
        Component message;
        if (result == ArmiesProtocol.RESULT_ACCEPTED && action == ArmiesProtocol.ACTION_FOUND_REALM) {
            message = Component.translatable("gui.millenaire_armies.result.realm_founded");
        } else if (result == ArmiesProtocol.RESULT_ACCEPTED
                && action == ArmiesProtocol.ACTION_SET_REALM_TAX) {
            message = Component.translatable("gui.millenaire_armies.result.tax_updated");
        } else if (result == ArmiesProtocol.RESULT_PERMISSION_DENIED
                && action == ArmiesProtocol.ACTION_FOUND_REALM) {
            message = Component.translatable("gui.millenaire_armies.result.realm_permission_denied");
        } else {
            message = switch (result) {
                case ArmiesProtocol.RESULT_ACCEPTED -> Component.translatable(
                        "gui.millenaire_armies.result.accepted", cachedMirror.acknowledgedAffected());
                case ArmiesProtocol.RESULT_STALE -> Component.translatable("gui.millenaire_armies.result.stale");
                case ArmiesProtocol.RESULT_PERMISSION_DENIED -> Component.translatable(
                        "gui.millenaire_armies.result.permission_denied");
                case ArmiesProtocol.RESULT_NOT_FOUND -> Component.translatable("gui.millenaire_armies.result.not_found");
                case ArmiesProtocol.RESULT_LIMIT_REACHED -> Component.translatable(
                        "gui.millenaire_armies.result.limit_reached");
                case ArmiesProtocol.RESULT_PARTIAL -> Component.translatable(
                        "gui.millenaire_armies.result.partial", cachedMirror.acknowledgedAffected());
                default -> Component.translatable("gui.millenaire_armies.result.invalid");
            };
        }
        showFeedback(message);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Preserve vanilla keyboard activation for focused buttons before handling list shortcuts.
        // Without this guard Enter on the recruitment tab could toggle a roster row instead of
        // pressing the focused Recruit/Create/Close button.
        if ((keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER)
                && getFocused() != null
                && super.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        if (tab == StrategicTab.RECRUITMENT
                && (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT)
                && !hasControlDown()) {
            rosterColumn = keyCode == GLFW.GLFW_KEY_LEFT ? 0 : 1;
            return true;
        }
        if (tab == StrategicTab.RECRUITMENT
                && (keyCode == GLFW.GLFW_KEY_SPACE || keyCode == GLFW.GLFW_KEY_ENTER)
                && rosterColumn == 1 && selectedRecruitRow >= 0) {
            toggleRecruit(selectedRecruitRow);
            updateButtons();
            return true;
        }
        boolean realmCapitalSelection = tab == StrategicTab.REALM && !cachedMirror.realmFounded();
        if ((tab == StrategicTab.RECRUITMENT || realmCapitalSelection)
                && (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN)) {
            int direction = keyCode == GLFW.GLFW_KEY_UP ? -1 : 1;
            int reserved = tab == StrategicTab.RECRUITMENT ? 35 : 24;
            int visible = Math.max(1, (contentHeight - reserved) / ROW_HEIGHT);
            if ((realmCapitalSelection || rosterColumn == 0) && cachedMirror.settlementCount() > 0) {
                int current = Math.max(0, findSettlementIndex(selectedSettlementMost, selectedSettlementLeast));
                int next = Math.max(0, Math.min(cachedMirror.settlementCount() - 1, current + direction));
                selectSettlement(next);
                settlementScroll = keepVisible(settlementScroll, next, visible);
                return true;
            }
            int count = filteredRecruitCount();
            if (tab == StrategicTab.RECRUITMENT && rosterColumn == 1 && count > 0) {
                int current = filteredRecruitOrdinal(selectedRecruitRow);
                int next = Math.max(0, Math.min(count - 1, Math.max(0, current) + direction));
                selectedRecruitRow = filteredRecruitRow(next);
                recruitScroll = keepVisible(recruitScroll, next, visible);
                return true;
            }
        }
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
            case RECRUITMENT -> findSettlementIndex(selectedSettlementMost, selectedSettlementLeast);
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

    private Component formationText(int code) {
        return arrayText(FORMATIONS, code);
    }

    private Component realmRoleText(int code) {
        return arrayText(REALM_ROLES, code);
    }

    private Component realmGovernmentText(int code) {
        return arrayText(REALM_GOVERNMENTS, code);
    }

    private Component realmAuthorityHint(int role) {
        return switch (role) {
            case RealmGovernanceSavedData.ROLE_HEAD -> Component.translatable(
                    "gui.millenaire_armies.realm.authority.head");
            case RealmGovernanceSavedData.ROLE_FEUDAL -> Component.translatable(
                    "gui.millenaire_armies.realm.authority.feudal");
            case RealmGovernanceSavedData.ROLE_GOVERNOR -> Component.translatable(
                    "gui.millenaire_armies.realm.authority.governor");
            default -> UNKNOWN;
        };
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
        return value == null || value.isBlank()
                ? I18n.get("gui.millenaire_armies.state.unavailable")
                : value;
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

    private static int keepVisible(int scroll, int row, int visible) {
        if (row < scroll) return row;
        return row >= scroll + visible ? row - visible + 1 : scroll;
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
