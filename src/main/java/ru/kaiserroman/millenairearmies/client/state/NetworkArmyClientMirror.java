package ru.kaiserroman.millenairearmies.client.state;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.network.PacketDistributor;
import ru.kaiserroman.millenairearmies.client.ArmyClientMirror;
import ru.kaiserroman.millenairearmies.client.ArmyClientState;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.network.ArmiesProtocol;
import ru.kaiserroman.millenairearmies.network.FactionMetadataPayload;
import ru.kaiserroman.millenairearmies.network.IssueOrderIntent;
import ru.kaiserroman.millenairearmies.network.RequestStateIntent;
import ru.kaiserroman.millenairearmies.presentation.client.ClientPresentationState;
import ru.kaiserroman.millenairearmies.presentation.client.ClientUnitPresentation;

/**
 * Frozen-UI adapter backed by the primitive network mirror. Presentation strings are rebuilt only
 * after a snapshot/delta, never from render getters.
 */
public final class NetworkArmyClientMirror
        implements ArmyClientMirror, ClientStateListener, FactionMetadataListener {
    private static final String EMPTY = "";
    private static final String READY = "Synced";
    private static final String STALE = "State revision gap; resync required";
    private static final ResourceLocation PRESENTATION_ROLE_LEVY = id("levy");
    private static final ResourceLocation PRESENTATION_RANK_NONE = id("none");
    private static final ResourceLocation PRESENTATION_BANNER_DEFAULT = id("default");
    private static final ResourceLocation[] PRESENTATION_ORDER = {
        id("holding"), id("moving"), id("rallying"), id("supplying")
    };
    public static final NetworkArmyClientMirror INSTANCE = new NetworkArmyClientMirror();

    private final ClientArmyState state = ClientArmyState.INSTANCE;
    private final ClientFactionMetadataState metadata = ClientFactionMetadataState.INSTANCE;
    private final ClientUnitPresentation[] unitPresentations =
            new ClientUnitPresentation[PRESENTATION_ORDER.length];
    private String[] factionNames = new String[0];
    private String[] factionSummaries = new String[0];
    private String[] factionCultureNames = new String[0];
    private String[] factionCapitalNames = new String[0];
    private String[] armyNames = new String[0];
    private String[] armySummaries = new String[0];
    private String[] armyFactionNames = new String[0];
    private String[] armyLocations = new String[0];
    private String[] armyCompositions = new String[0];
    private String[] orderArmyNames = new String[0];
    private String[] orderSummaries = new String[0];
    private String[] orderTargets = new String[0];
    private String[] logisticsNames = new String[0];
    private String[] logisticsSummaries = new String[0];
    private String[] logisticsCargo = new String[0];
    private String[] logisticsDestinations = new String[0];
    private int[] factionArmyCounts = new int[0];
    private int[] factionPopulations = new int[0];
    private int[] factionSettlementCounts = new int[0];
    private int[] factionInfluences = new int[0];
    private int[] factionArmyUnitCounts = new int[0];
    private int actionSequence;

    private NetworkArmyClientMirror() {}

    @Override
    public void stateChanged(ClientArmyState changed) {
        rebuildPresentation();
        // Install the transport-capable mirror before the first snapshot.  The screen's added()
        // hook must be able to send its initial request while the state is still uninitialized.
        ArmyClientState.install(this);
    }

    @Override
    public void metadataChanged(ClientFactionMetadataState changed) {
        rebuildPresentation();
        ArmyClientState.install(this);
    }

    @Override
    public long revision() {
        return state.revision();
    }

    @Override
    public boolean isReady() {
        return state.initialized() && !state.requiresResync();
    }

    @Override
    public String statusText() {
        return state.requiresResync() ? STALE : state.initialized() ? READY : EMPTY;
    }

    @Override
    public int playerFactionId() {
        return state.playerFactionId();
    }

    @Override
    public String playerFactionName() {
        int row = factions().findRow(state.playerFactionId());
        return row < 0 ? EMPTY : factionNames[row];
    }

    @Override
    public int totalUnitCount() {
        return units().size();
    }

    @Override
    public int factionCount() {
        return factions().size();
    }

    @Override
    public int factionId(int index) {
        return factions().handle(index);
    }

    @Override
    public String factionName(int index) {
        return factionNames[index];
    }

    @Override
    public String factionSummary(int index) {
        return factionSummaries[index];
    }

    @Override
    public String factionCultureName(int index) {
        return factionCultureNames[index];
    }

    @Override
    public byte factionRelationCode(int index) {
        int target = factionId(index);
        PackedMirrorTable relations = relations();
        for (int row = 0; row < relations.size(); row++) {
            if (relations.intValue(row, ArmiesProtocol.COLUMN_OWNER) == state.playerFactionId()
                    && relations.intValue(row, ArmiesProtocol.COLUMN_PRIMARY_KEY) == target) {
                return relations.byteValue(row, 0);
            }
        }
        return 1;
    }

    @Override
    public int factionReputation(int index) {
        int target = factionId(index);
        PackedMirrorTable relations = relations();
        for (int row = 0; row < relations.size(); row++) {
            if (relations.intValue(row, ArmiesProtocol.COLUMN_OWNER) == state.playerFactionId()
                    && relations.intValue(row, ArmiesProtocol.COLUMN_PRIMARY_KEY) == target) {
                return relations.intValue(row, ArmiesProtocol.COLUMN_VALUE_0);
            }
        }
        return 0;
    }

    @Override
    public int factionInfluence(int index) {
        return factionInfluences[index];
    }

    @Override
    public int factionSettlementCount(int index) {
        return factionSettlementCounts[index];
    }

    @Override
    public int factionArmyCount(int index) {
        return factionArmyCounts[index];
    }

    @Override
    public int factionPopulation(int index) {
        return factionPopulations[index];
    }

    @Override
    public String factionCapitalName(int index) {
        return factionCapitalNames[index];
    }

    @Override
    public int armyCount() {
        return armies().size();
    }

    @Override
    public int armyId(int index) {
        return armies().handle(index);
    }

    @Override
    public String armyName(int index) {
        return armyNames[index];
    }

    @Override
    public String armySummary(int index) {
        return armySummaries[index];
    }

    @Override
    public int armyFactionId(int index) {
        return armies().intValue(index, ArmiesProtocol.COLUMN_OWNER);
    }

    @Override
    public String armyFactionName(int index) {
        return armyFactionNames[index];
    }

    @Override
    public int armyUnitCount(int index) {
        return armies().intValue(index, ArmiesProtocol.COLUMN_VALUE_0);
    }

    @Override
    public int armyReadyUnitCount(int index) {
        return armyUnitCount(index);
    }

    @Override
    public int armyMoralePercent(int index) {
        return Byte.toUnsignedInt(armies().byteValue(index, 1));
    }

    @Override
    public int armySupplyPercent(int index) {
        return armies().intValue(index, ArmiesProtocol.COLUMN_VALUE_1);
    }

    @Override
    public int armySpeedPercent(int index) {
        return armies().intValue(index, ArmiesProtocol.COLUMN_VALUE_2);
    }

    @Override
    public int armyOrderTypeCode(int index) {
        return Byte.toUnsignedInt(armies().byteValue(index, 0));
    }

    @Override
    public String armyOrderTarget(int index) {
        return armyLocations[index];
    }

    @Override
    public String armyLocation(int index) {
        return armyLocations[index];
    }

    @Override
    public String armyComposition(int index) {
        return armyCompositions[index];
    }

    @Override
    public int orderCount() {
        return orders().size();
    }

    @Override
    public long orderId(int index) {
        return orders().longValue(index, 0);
    }

    @Override
    public int orderArmyId(int index) {
        return orders().intValue(index, ArmiesProtocol.COLUMN_OWNER);
    }

    @Override
    public String orderArmyName(int index) {
        return orderArmyNames[index];
    }

    @Override
    public String orderSummary(int index) {
        return orderSummaries[index];
    }

    @Override
    public int orderTypeCode(int index) {
        return Byte.toUnsignedInt(orders().byteValue(index, 0));
    }

    @Override
    public String orderTarget(int index) {
        return orderTargets[index];
    }

    @Override
    public String orderState(int index) {
        return "active";
    }

    @Override
    public int logisticsCount() {
        return logistics().size();
    }

    @Override
    public int logisticsId(int index) {
        return logistics().handle(index);
    }

    @Override
    public String logisticsName(int index) {
        return logisticsNames[index];
    }

    @Override
    public String logisticsSummary(int index) {
        return logisticsSummaries[index];
    }

    @Override
    public String logisticsCargo(int index) {
        return logisticsCargo[index];
    }

    @Override
    public int logisticsCargoCount(int index) {
        return logistics().intValue(index, ArmiesProtocol.COLUMN_VALUE_0);
    }

    @Override
    public String logisticsDestination(int index) {
        return logisticsDestinations[index];
    }

    @Override
    public String logisticsAssignedArmy(int index) {
        int handle = logistics().intValue(index, ArmiesProtocol.COLUMN_SECONDARY_KEY);
        int row = armies().findRow(handle);
        return row < 0 ? EMPTY : armyNames[row];
    }

    @Override
    public byte logisticsStatusCode(int index) {
        return logistics().byteValue(index, 0);
    }

    @Override
    public int logisticsProgressPercent(int index) {
        int required = logistics().intValue(index, ArmiesProtocol.COLUMN_VALUE_0);
        int fulfilled = logistics().intValue(index, ArmiesProtocol.COLUMN_VALUE_1);
        return required <= 0 ? 0 : Math.min(100, fulfilled * 100 / required);
    }

    @Override
    public boolean logisticsHighPriority(int index) {
        return logistics().byteValue(index, 1) >= 4;
    }

    @Override
    public boolean requestIssueOrder(int armyHandleBits, int orderTypeCode) {
        if (!isReady() || !ArmiesProtocol.validStrategicOrder((byte) orderTypeCode)
                || armies().findRow(armyHandleBits) < 0 || Minecraft.getInstance().getConnection() == null) {
            return false;
        }
        long target = 0L;
        if (orderTypeCode != ArmiesProtocol.ORDER_HOLD) {
            HitResult hit = Minecraft.getInstance().hitResult;
            if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
                return false;
            }
            target = blockHit.getBlockPos().asLong();
        }
        PacketDistributor.sendToServer(new IssueOrderIntent(
                nextActionId(), armyHandleBits, (byte) orderTypeCode, target, 0L, -1,
                state.revision(), (byte) 0));
        return true;
    }

    @Override
    public void requestFullSync() {
        if (Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(new RequestStateIntent(
                    ArmiesProtocol.SECTION_ALL,
                    ArmiesProtocol.SCOPE_GLOBAL,
                    0,
                    0,
                    state.oldestRevision(ArmiesProtocol.SECTION_ALL)));
        }
    }

    private void rebuildPresentation() {
        PackedMirrorTable factions = factions();
        factionNames = new String[factions.size()];
        factionSummaries = new String[factions.size()];
        factionCultureNames = new String[factions.size()];
        factionCapitalNames = new String[factions.size()];
        factionArmyCounts = new int[factions.size()];
        factionPopulations = new int[factions.size()];
        factionSettlementCounts = new int[factions.size()];
        factionInfluences = new int[factions.size()];
        factionArmyUnitCounts = new int[factions.size()];
        for (int row = 0; row < factions.size(); row++) {
            int id = factions.handle(row);
            int metadataRow = metadata.findFactionRow(id);
            if (metadataRow < 0) {
                factionNames[row] = "Faction " + id;
                factionCultureNames[row] = EMPTY;
                factionCapitalNames[row] = EMPTY;
                continue;
            }
            String displayName = metadata.stringValue(
                    metadataRow, FactionMetadataPayload.STRING_DISPLAY_NAME);
            factionNames[row] = displayName.isBlank() ? "Faction " + id : displayName;
            factionCultureNames[row] = metadata.stringValue(
                    metadataRow, FactionMetadataPayload.STRING_CULTURE_ID);
            factionCapitalNames[row] = metadata.stringValue(
                    metadataRow, FactionMetadataPayload.STRING_CAPITAL_NAME);
            factionSettlementCounts[row] = metadata.intValue(
                    metadataRow, FactionMetadataPayload.COLUMN_SETTLEMENTS);
            factionPopulations[row] = metadata.intValue(
                    metadataRow, FactionMetadataPayload.COLUMN_POPULATION);
            factionInfluences[row] = metadata.intValue(
                    metadataRow, FactionMetadataPayload.COLUMN_INFLUENCE);
        }

        rebuildUnitMarkers();

        PackedMirrorTable armies = armies();
        armyNames = new String[armies.size()];
        armySummaries = new String[armies.size()];
        armyFactionNames = new String[armies.size()];
        armyLocations = new String[armies.size()];
        armyCompositions = new String[armies.size()];
        for (int row = 0; row < armies.size(); row++) {
            int handle = armies.handle(row);
            int faction = armies.intValue(row, ArmiesProtocol.COLUMN_OWNER);
            int units = armies.intValue(row, ArmiesProtocol.COLUMN_VALUE_0);
            int factionRow = factions.findRow(faction);
            armyNames[row] = strategicArmyName(handle, factionRow);
            armyFactionNames[row] = factionRow < 0 ? EMPTY : factionNames[factionRow];
            armyLocations[row] = position(armies.longValue(row, 0));
            armyCompositions[row] = I18n.get("gui.millenaire_armies.composition.units", units);
            armySummaries[row] = I18n.get("gui.millenaire_armies.summary.army", units,
                    armies.intValue(row, ArmiesProtocol.COLUMN_VALUE_1));
            if (factionRow >= 0) {
                factionArmyCounts[factionRow]++;
                factionArmyUnitCounts[factionRow] += units;
            }
        }
        for (int row = 0; row < factions.size(); row++) {
            String summaryKey = factionSettlementCounts[row] == 1
                    ? "gui.millenaire_armies.summary.faction.one"
                    : "gui.millenaire_armies.summary.faction.many";
            factionSummaries[row] = I18n.get(summaryKey,
                    factionSettlementCounts[row], factionPopulations[row]);
        }

        PackedMirrorTable orders = orders();
        orderArmyNames = new String[orders.size()];
        orderSummaries = new String[orders.size()];
        orderTargets = new String[orders.size()];
        for (int row = 0; row < orders.size(); row++) {
            int army = orders.intValue(row, ArmiesProtocol.COLUMN_OWNER);
            int armyRow = armies.findRow(army);
            orderArmyNames[row] = armyRow < 0 ? "Army " + Integer.toUnsignedString(army) : armyNames[armyRow];
            orderTargets[row] = position(orders.longValue(row, 1));
            orderSummaries[row] = I18n.get("gui.millenaire_armies.summary.order",
                    I18n.get(orderTranslationKey(orders.byteValue(row, 0))), orderTargets[row]);
        }

        PackedMirrorTable logistics = logistics();
        logisticsNames = new String[logistics.size()];
        logisticsSummaries = new String[logistics.size()];
        logisticsCargo = new String[logistics.size()];
        logisticsDestinations = new String[logistics.size()];
        for (int row = 0; row < logistics.size(); row++) {
            int required = logistics.intValue(row, ArmiesProtocol.COLUMN_VALUE_0);
            int fulfilled = logistics.intValue(row, ArmiesProtocol.COLUMN_VALUE_1);
            logisticsNames[row] = I18n.get("gui.millenaire_armies.logistics.route_name",
                    Integer.toUnsignedString(logistics.handle(row)));
            logisticsCargo[row] = I18n.get("gui.millenaire_armies.logistics.item",
                    logistics.intValue(row, ArmiesProtocol.COLUMN_PRIMARY_KEY), required);
            logisticsDestinations[row] = position(logistics.longValue(row, 0));
            logisticsSummaries[row] = I18n.get("gui.millenaire_armies.summary.logistics",
                    fulfilled, required, logisticsDestinations[row]);
        }
    }

    /** Rebuilds UUID-only render markers on network updates; the render event performs no build. */
    private void rebuildUnitMarkers() {
        PackedMirrorTable units = units();
        ClientPresentationState.units().clear();
        ClientPresentationState.units().reserve(units.size());
        for (int order = 0; order < unitPresentations.length; order++) {
            unitPresentations[order] = ClientUnitPresentation.resolve(
                    ClientPresentationState.catalog(),
                    PRESENTATION_ROLE_LEVY,
                    PRESENTATION_RANK_NONE,
                    PRESENTATION_BANNER_DEFAULT,
                    PRESENTATION_ORDER[order],
                    ClientUnitPresentation.FLAG_SHOW_OVERHEAD_MARKER);
        }
        for (int row = 0; row < units.size(); row++) {
            long uuidMost = units.longValue(row, 0);
            long uuidLeast = units.longValue(row, 1);
            if (uuidMost == 0L && uuidLeast == 0L) {
                continue;
            }
            int order = units.intValue(row, ArmiesProtocol.COLUMN_PRIMARY_KEY);
            if (order < 0 || order >= unitPresentations.length) {
                order = ArmiesProtocol.ORDER_HOLD;
            }
            ClientPresentationState.units().put(
                    uuidMost, uuidLeast, units.handle(row), unitPresentations[order]);
        }
    }

    private int nextActionId() {
        actionSequence = actionSequence == Integer.MAX_VALUE ? 1 : actionSequence + 1;
        return actionSequence;
    }

    private PackedMirrorTable factions() {
        return state.table(ArmiesProtocol.KIND_FACTION);
    }

    private PackedMirrorTable armies() {
        return state.table(ArmiesProtocol.KIND_ARMY);
    }

    private PackedMirrorTable units() {
        return state.table(ArmiesProtocol.KIND_UNIT);
    }

    private PackedMirrorTable relations() {
        return state.table(ArmiesProtocol.KIND_RELATION);
    }

    private PackedMirrorTable logistics() {
        return state.table(ArmiesProtocol.KIND_LOGISTICS);
    }

    private PackedMirrorTable orders() {
        return state.table(ArmiesProtocol.KIND_ORDER);
    }

    private static String position(long packed) {
        return PackedArmyEcs.unpackBlockX(packed) + ", "
                + PackedArmyEcs.unpackBlockY(packed) + ", "
                + PackedArmyEcs.unpackBlockZ(packed);
    }

    private static String orderTranslationKey(byte code) {
        return switch (code) {
            case ArmiesProtocol.ORDER_HOLD -> "gui.millenaire_armies.order.hold";
            case ArmiesProtocol.ORDER_MOVE -> "gui.millenaire_armies.order.move";
            case ArmiesProtocol.ORDER_RALLY -> "gui.millenaire_armies.order.rally";
            case ArmiesProtocol.ORDER_LOGISTICS -> "gui.millenaire_armies.order.logistics";
            default -> "gui.millenaire_armies.order.unknown";
        };
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("millenaire_armies", path);
    }

    private String strategicArmyName(int handle, int factionRow) {
        String suffix = Integer.toUnsignedString(handle, 36);
        if (factionRow < 0) {
            return "Army " + suffix;
        }
        String capital = factionCapitalNames[factionRow];
        if (!capital.isBlank() && !"—".equals(capital)) {
            return capital + " Host · " + suffix;
        }
        return factionNames[factionRow] + " Host · " + suffix;
    }
}
