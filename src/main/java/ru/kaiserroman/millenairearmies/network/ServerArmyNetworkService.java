package ru.kaiserroman.millenairearmies.network;

import java.nio.charset.StandardCharsets;
import net.minecraft.server.level.ServerPlayer;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.integration.millenaire.FactionProjectionService;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireRecruitmentService;
import ru.kaiserroman.millenairearmies.integration.millenaire.MillenaireVillageIndex;
import ru.kaiserroman.millenairearmies.persistence.ArmySavedData;
import ru.kaiserroman.millenairearmies.persistence.PackedFactionState;
import ru.kaiserroman.millenairearmies.persistence.PackedLogisticsState;
import ru.kaiserroman.millenairearmies.persistence.PackedUnitMembership;
import ru.kaiserroman.millenairearmies.server.execution.ArmyOrderExecutionBridge;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandAuthority;
import ru.kaiserroman.millenairearmies.server.service.ArmyCommandService;
import ru.kaiserroman.millenairearmies.server.service.StrategicArmyOrder;

/**
 * Minimal authoritative networking vertical slice. It projects only armies controlled by the
 * authenticated player (or all armies for an operator), and delegates every mutation to the one
 * lifecycle-owned command service.
 */
public final class ServerArmyNetworkService implements ServerIntentSink {
    private final ArmySavedData data;
    private final ArmyCommandService commands;
    private final VisibleArmies visible = new VisibleArmies();
    private final PackedArmyEcs.UnitCursor unitCursor;
    private final PackedFactionState.Cursor relationCursor;
    private final PackedLogisticsState.Cursor logisticsCursor;
    private final PackedUnitMembership.UuidBits unitUuid;
    private final int[] visibleFactions = new int[ArmiesProtocol.MAX_FACTIONS_PER_SNAPSHOT];
    private FactionProjectionService factionProjection;
    private final MillenaireRecruitmentService recruitment;
    private final ServerArmyRosterProjection rosterProjection;
    private final ArmyOrderExecutionBridge execution;
    private int visibleFactionCount;

    public ServerArmyNetworkService(ArmySavedData data, ArmyCommandService commands) {
        this(data, commands, null, null, null, null);
    }

    public ServerArmyNetworkService(
            ArmySavedData data,
            ArmyCommandService commands,
            FactionProjectionService factionProjection) {
        this(data, commands, factionProjection, null, null, null);
    }

    public ServerArmyNetworkService(
            ArmySavedData data,
            ArmyCommandService commands,
            FactionProjectionService factionProjection,
            MillenaireVillageIndex villageIndex,
            MillenaireRecruitmentService recruitment,
            ArmyOrderExecutionBridge execution) {
        this.data = data;
        this.commands = commands;
        this.factionProjection = factionProjection;
        this.recruitment = recruitment;
        this.rosterProjection = villageIndex == null || factionProjection == null
                ? null
                : new ServerArmyRosterProjection(data, villageIndex, factionProjection, recruitment);
        this.execution = execution;
        this.unitCursor = data.ecs().newUnitCursor();
        this.relationCursor = data.factions().newCursor();
        this.logisticsCursor = data.logistics().newCursor();
        this.unitUuid = data.memberships().newUuidBits();
    }

    /** Cold lifecycle hook; projection data never changes command authority or visibility. */
    public void factionProjection(FactionProjectionService replacement) {
        factionProjection = replacement;
    }

    @Override
    public void open(ServerPlayer player, OpenCommandIntent intent) {
        byte sections = switch (intent.view()) {
            case ArmiesProtocol.VIEW_FACTION -> (byte) (ArmiesProtocol.SECTION_FACTIONS
                    | ArmiesProtocol.SECTION_RELATIONS
                    | ArmiesProtocol.SECTION_ARMIES
                    | ArmiesProtocol.SECTION_UNITS);
            case ArmiesProtocol.VIEW_ARMY -> (byte) (ArmiesProtocol.SECTION_ARMIES
                    | ArmiesProtocol.SECTION_UNITS
                    | ArmiesProtocol.SECTION_ORDERS
                    | ArmiesProtocol.SECTION_LOGISTICS);
            case ArmiesProtocol.VIEW_LOGISTICS -> (byte) (ArmiesProtocol.SECTION_LOGISTICS
                    | ArmiesProtocol.SECTION_ARMIES);
            default -> ArmiesProtocol.SECTION_ALL;
        };
        byte scope = intent.view() == ArmiesProtocol.VIEW_ARMY
                ? ArmiesProtocol.SCOPE_ARMY
                : intent.view() == ArmiesProtocol.VIEW_FACTION
                        ? ArmiesProtocol.SCOPE_FACTION
                        : ArmiesProtocol.SCOPE_GLOBAL;
        sendSnapshot(player, sections, scope, intent.contextHandle());
    }

    @Override
    public void requestState(ServerPlayer player, RequestStateIntent intent) {
        // Cursor zero is the complete bounded projection implemented by this first vertical slice.
        // Non-zero cursors are reserved for the later paged dictionary/content protocol.
        if (intent.cursor() != 0) {
            return;
        }
        sendSnapshot(player, intent.sectionMask(), intent.scope(), intent.scopeHandle());
    }

    @Override
    public void createArmy(ServerPlayer player, CreateArmyIntent intent) {
        if (intent.expectedRevision() != data.armyRevision() || intent.flags() != 0) {
            sendSnapshot(player, ArmiesProtocol.SECTION_ALL, ArmiesProtocol.SCOPE_GLOBAL, 0);
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_CREATE_ARMY,
                    ArmiesProtocol.RESULT_STALE, 0);
            return;
        }
        if (recruitment == null || intent.templateKeyId() != 0 || intent.desiredUnits() != 1) {
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_CREATE_ARMY,
                    ArmiesProtocol.RESULT_INVALID, 0);
            return;
        }
        long result = recruitment.createArmy(
                authority(player), intent.factionId(), intent.homeVillagePosition());
        if (result >= 0) {
            sendSnapshot(player, ArmiesProtocol.SECTION_ALL, ArmiesProtocol.SCOPE_GLOBAL, 0);
        }
        sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_CREATE_ARMY,
                result >= 0 ? ArmiesProtocol.RESULT_ACCEPTED : recruitmentResult(result),
                result >= 0 ? intent.desiredUnits() : 0);
    }

    @Override
    public void recruitUnits(ServerPlayer player, RecruitUnitsIntent intent) {
        if (intent.expectedRevision() != data.armyRevision()) {
            sendSnapshot(player, ArmiesProtocol.SECTION_ALL, ArmiesProtocol.SCOPE_GLOBAL, 0);
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_RECRUIT,
                    ArmiesProtocol.RESULT_STALE, 0);
            return;
        }
        if (recruitment == null) {
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_RECRUIT,
                    ArmiesProtocol.RESULT_INVALID, 0);
            return;
        }

        ArmyCommandAuthority authority = authority(player);
        int affected = 0;
        long failure = 0L;
        long[] bits = intent.villagerUuidBits();
        for (int index = 0; index < intent.count(); index++) {
            long result = recruitment.recruitSelected(
                    authority,
                    intent.armyHandle(),
                    player.serverLevel(),
                    player.blockPosition(),
                    bits[index * 2],
                    bits[index * 2 + 1]);
            if (result < 0L) {
                failure = result;
                break;
            }
            affected++;
        }
        if (affected > 0) {
            sendSnapshot(player, ArmiesProtocol.SECTION_ALL, ArmiesProtocol.SCOPE_GLOBAL, 0);
        }
        int result = affected == intent.count()
                ? ArmiesProtocol.RESULT_ACCEPTED
                : affected > 0 ? ArmiesProtocol.RESULT_PARTIAL : recruitmentResult(failure);
        sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_RECRUIT, result, affected);
    }

    @Override
    public void issueOrder(ServerPlayer player, IssueOrderIntent intent) {
        if (intent.expectedRevision() != data.armyRevision() || intent.flags() != 0) {
            sendSnapshot(player, ArmiesProtocol.SECTION_ALL, ArmiesProtocol.SCOPE_GLOBAL, 0);
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_ISSUE_ORDER,
                    ArmiesProtocol.RESULT_STALE, 0);
            return;
        }
        StrategicArmyOrder order = order(intent.orderType());
        if (order == null) {
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_ISSUE_ORDER,
                    ArmiesProtocol.RESULT_INVALID, 0);
            return;
        }
        if (order.requiresTarget()
                && !intent.targetDimension().equals(player.serverLevel().dimension().location())) {
            sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_ISSUE_ORDER,
                    ArmiesProtocol.RESULT_INVALID, 0);
            return;
        }
        long result = commands.issueOrder(authority(player), intent.armyHandle(), order, intent.primaryPosition());
        if (result == ArmyCommandService.SUCCESS) {
            sendSnapshot(player, ArmiesProtocol.SECTION_ALL, ArmiesProtocol.SCOPE_GLOBAL, 0);
        }
        sendRoster(player, intent.actionId(), ArmiesProtocol.ACTION_ISSUE_ORDER,
                commandResult(result), result == ArmyCommandService.SUCCESS ? 1 : 0);
    }

    private void sendSnapshot(ServerPlayer player, byte sections, byte scope, int scopeHandle) {
        ArmyCommandAuthority authority = authority(player);
        visible.reset(scope, scopeHandle);
        commands.visitVisibleArmies(authority, visible);
        collectVisibleFactions();

        int factionRows = (sections & ArmiesProtocol.SECTION_FACTIONS) != 0 ? visibleFactionCount : 0;
        int armyRows = (sections & ArmiesProtocol.SECTION_ARMIES) != 0 ? visible.size : 0;
        int unitRows = (sections & ArmiesProtocol.SECTION_UNITS) != 0 ? countVisibleUnits() : 0;
        int relationRows = (sections & ArmiesProtocol.SECTION_RELATIONS) != 0 ? countVisibleRelations() : 0;
        int logisticsRows = (sections & ArmiesProtocol.SECTION_LOGISTICS) != 0 ? countVisibleLogistics() : 0;
        int orderRows = (sections & ArmiesProtocol.SECTION_ORDERS) != 0 ? visible.size : 0;
        int totalRows = factionRows + armyRows + unitRows + relationRows + logisticsRows + orderRows;
        int[] ints = new int[totalRows * ArmiesProtocol.INT_COLUMNS];
        long[] longs = new long[totalRows * ArmiesProtocol.LONG_COLUMNS];
        byte[] bytes = new byte[totalRows * ArmiesProtocol.BYTE_COLUMNS];

        int row = 0;
        if (factionRows != 0) {
            for (int index = 0; index < visibleFactionCount; index++) {
                int faction = visibleFactions[index];
                setInt(ints, row, ArmiesProtocol.COLUMN_HANDLE, faction);
                setInt(ints, row, ArmiesProtocol.COLUMN_OWNER, faction);
                row++;
            }
        }
        if (armyRows != 0) {
            for (int index = 0; index < visible.size; index++) {
                writeArmy(ints, longs, bytes, row++, index);
            }
        }
        if (unitRows != 0) {
            int written = 0;
            unitCursor.reset();
            while (written < unitRows && unitCursor.advance()) {
                if (!visible.contains(unitCursor.army())) {
                    continue;
                }
                setInt(ints, row, ArmiesProtocol.COLUMN_HANDLE, unitCursor.handle());
                setInt(ints, row, ArmiesProtocol.COLUMN_OWNER, unitCursor.army());
                setInt(ints, row, ArmiesProtocol.COLUMN_PRIMARY_KEY, unitCursor.order());
                setInt(ints, row, ArmiesProtocol.COLUMN_SECONDARY_KEY, unitCursor.state());
                if (data.memberships().read(unitCursor.handle(), unitUuid)) {
                    setLong(longs, row, 0, unitUuid.most());
                    setLong(longs, row, 1, unitUuid.least());
                }
                row++;
                written++;
            }
        }
        if (relationRows != 0) {
            int written = 0;
            relationCursor.reset();
            while (written < relationRows && relationCursor.advance()) {
                if (!relationVisible()) {
                    continue;
                }
                int source = relationCursor.sourceFactionId();
                int target = relationCursor.targetFactionId();
                setInt(ints, row, ArmiesProtocol.COLUMN_HANDLE, (source << 16) | (target & 0xffff));
                setInt(ints, row, ArmiesProtocol.COLUMN_OWNER, source);
                setInt(ints, row, ArmiesProtocol.COLUMN_PRIMARY_KEY, target);
                setInt(ints, row, ArmiesProtocol.COLUMN_VALUE_0, relationCursor.reputation());
                setLong(longs, row, 0, relationCursor.revision());
                setByte(bytes, row, 0, relationCursor.allegianceCode());
                row++;
                written++;
            }
        }
        if (logisticsRows != 0) {
            int written = 0;
            logisticsCursor.reset();
            while (written < logisticsRows && logisticsCursor.advance()) {
                if (!logisticsVisible()) {
                    continue;
                }
                long requestId = logisticsCursor.requestId();
                setInt(ints, row, ArmiesProtocol.COLUMN_HANDLE, (int) requestId);
                setInt(ints, row, ArmiesProtocol.COLUMN_OWNER, logisticsCursor.factionId());
                setInt(ints, row, ArmiesProtocol.COLUMN_PRIMARY_KEY, logisticsCursor.itemKey());
                setInt(ints, row, ArmiesProtocol.COLUMN_SECONDARY_KEY, logisticsCursor.requesterArmyHandle());
                setInt(ints, row, ArmiesProtocol.COLUMN_VALUE_0, logisticsCursor.requiredAmount());
                setInt(ints, row, ArmiesProtocol.COLUMN_VALUE_1, logisticsCursor.fulfilledAmount());
                setInt(ints, row, ArmiesProtocol.COLUMN_VALUE_2, logisticsCursor.dimensionId());
                setLong(longs, row, 0, logisticsCursor.destination());
                setLong(longs, row, 1, logisticsCursor.createdGameTime());
                setByte(bytes, row, 0, logisticsCursor.statusCode());
                setByte(bytes, row, 1, logisticsCursor.priority());
                row++;
                written++;
            }
        }
        if (orderRows != 0) {
            for (int index = 0; index < visible.size; index++) {
                int handle = visible.handles[index];
                setInt(ints, row, ArmiesProtocol.COLUMN_HANDLE, handle);
                setInt(ints, row, ArmiesProtocol.COLUMN_OWNER, handle);
                setInt(ints, row, ArmiesProtocol.COLUMN_PRIMARY_KEY, visible.orders[index]);
                setInt(ints, row, ArmiesProtocol.COLUMN_SECONDARY_KEY, visible.states[index]);
                setLong(longs, row, 0, Integer.toUnsignedLong(handle));
                setLong(longs, row, 1, visible.targets[index]);
                setByte(bytes, row, 0, (byte) visible.orders[index]);
                setByte(bytes, row, 1, execution == null
                        ? ArmiesProtocol.EXECUTION_BLOCKED
                        : execution.armyExecutionStatus(handle));
                row++;
            }
        }

        int playerFaction = visibleFactionCount == 0 ? -1 : visibleFactions[0];
        ArmiesNetwork.sendSnapshot(player, new ArmyStateSnapshotPayload(
                data.armyRevision(),
                playerFaction,
                sections,
                factionRows,
                armyRows,
                unitRows,
                relationRows,
                logisticsRows,
                orderRows,
                ints,
                longs,
                bytes));
        sendFactionMetadata(player);
        sendRoster(player, 0, ArmiesProtocol.ACTION_NONE, ArmiesProtocol.RESULT_NONE, 0);
    }

    private void sendFactionMetadata(ServerPlayer player) {
        int count = visibleFactionCount;
        int[] ints = new int[count * FactionMetadataPayload.INT_COLUMNS];
        long[] positions = new long[count];
        String[] strings = new String[count * FactionMetadataPayload.STRING_COLUMNS];
        FactionProjectionService projection = factionProjection;
        for (int row = 0; row < count; row++) {
            int factionId = visibleFactions[row];
            int primitive = row * FactionMetadataPayload.INT_COLUMNS;
            int text = row * FactionMetadataPayload.STRING_COLUMNS;
            ints[primitive + FactionMetadataPayload.COLUMN_FACTION_ID] = factionId;
            int projectionRow = projection == null ? -1 : projection.findFactionRow(factionId);
            if (projectionRow < 0) {
                strings[text + FactionMetadataPayload.STRING_CULTURE_ID] = "";
                strings[text + FactionMetadataPayload.STRING_DISPLAY_NAME] = "";
                strings[text + FactionMetadataPayload.STRING_CAPITAL_NAME] = "";
                continue;
            }
            ints[primitive + FactionMetadataPayload.COLUMN_SETTLEMENTS] =
                    projection.settlementCount(projectionRow);
            ints[primitive + FactionMetadataPayload.COLUMN_POPULATION] = projection.population(projectionRow);
            ints[primitive + FactionMetadataPayload.COLUMN_INFLUENCE] = projection.influence(projectionRow);
            positions[row] = projection.capitalPosition(projectionRow);
            strings[text + FactionMetadataPayload.STRING_CULTURE_ID] = boundedUtf8(
                    projection.cultureId(projectionRow).toString());
            strings[text + FactionMetadataPayload.STRING_DISPLAY_NAME] = boundedUtf8(
                    projection.displayName(projectionRow));
            strings[text + FactionMetadataPayload.STRING_CAPITAL_NAME] = boundedUtf8(
                    projection.capitalName(projectionRow));
        }
        ArmiesNetwork.sendFactionMetadata(player, new FactionMetadataPayload(
                data.armyRevision(),
                projection == null ? 0L : projection.revision(),
                count,
                ints,
                positions,
                strings));
    }

    private static String boundedUtf8(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length <= FactionMetadataPayload.MAX_STRING_UTF8_BYTES) {
            return value;
        }
        int end = FactionMetadataPayload.MAX_STRING_UTF8_BYTES;
        while (end > 0 && (encoded[end] & 0xc0) == 0x80) {
            end--;
        }
        return new String(encoded, 0, end, StandardCharsets.UTF_8);
    }

    private void collectVisibleFactions() {
        visibleFactionCount = 0;
        for (int index = 0; index < visible.size; index++) {
            addFaction(visible.factions[index]);
        }
        // Relations disclose only factions directly connected to an already visible faction.
        int originalCount = visibleFactionCount;
        relationCursor.reset();
        while (relationCursor.advance() && visibleFactionCount < visibleFactions.length) {
            int source = relationCursor.sourceFactionId();
            int target = relationCursor.targetFactionId();
            if (containsFaction(source, originalCount) || containsFaction(target, originalCount)) {
                addFaction(source);
                addFaction(target);
            }
        }
    }

    private int countVisibleUnits() {
        int count = 0;
        unitCursor.reset();
        while (unitCursor.advance() && count < ArmiesProtocol.MAX_UNITS_PER_SNAPSHOT) {
            if (visible.contains(unitCursor.army())) {
                count++;
            }
        }
        return count;
    }

    private int countVisibleRelations() {
        int count = 0;
        relationCursor.reset();
        while (relationCursor.advance() && count < ArmiesProtocol.MAX_RELATIONS_PER_SNAPSHOT) {
            if (relationVisible()) {
                count++;
            }
        }
        return count;
    }

    private int countVisibleLogistics() {
        int count = 0;
        logisticsCursor.reset();
        while (logisticsCursor.advance() && count < ArmiesProtocol.MAX_LOGISTICS_PER_SNAPSHOT) {
            if (logisticsVisible()) {
                count++;
            }
        }
        return count;
    }

    private boolean relationVisible() {
        return containsFaction(relationCursor.sourceFactionId(), visibleFactionCount)
                && containsFaction(relationCursor.targetFactionId(), visibleFactionCount);
    }

    private boolean logisticsVisible() {
        return visible.contains(logisticsCursor.requesterArmyHandle())
                || containsFaction(logisticsCursor.factionId(), visibleFactionCount);
    }

    private void writeArmy(int[] ints, long[] longs, byte[] bytes, int row, int index) {
        setInt(ints, row, ArmiesProtocol.COLUMN_HANDLE, visible.handles[index]);
        setInt(ints, row, ArmiesProtocol.COLUMN_OWNER, visible.factions[index]);
        setInt(ints, row, ArmiesProtocol.COLUMN_PRIMARY_KEY, visible.states[index]);
        setInt(ints, row, ArmiesProtocol.COLUMN_SECONDARY_KEY, visible.units[index]);
        setInt(ints, row, ArmiesProtocol.COLUMN_VALUE_0, visible.units[index]);
        setInt(ints, row, ArmiesProtocol.COLUMN_VALUE_1, 100);
        setInt(ints, row, ArmiesProtocol.COLUMN_VALUE_2, 100);
        setLong(longs, row, 0, visible.targets[index]);
        setLong(longs, row, 1, visible.targets[index]);
        setByte(bytes, row, 0, (byte) visible.orders[index]);
        setByte(bytes, row, 1, (byte) 100);
    }

    private void addFaction(int faction) {
        if (faction < 0 || containsFaction(faction, visibleFactionCount)
                || visibleFactionCount == visibleFactions.length) {
            return;
        }
        visibleFactions[visibleFactionCount++] = faction;
    }

    private boolean containsFaction(int faction, int limit) {
        for (int index = 0; index < limit; index++) {
            if (visibleFactions[index] == faction) {
                return true;
            }
        }
        return false;
    }

    private static ArmyCommandAuthority authority(ServerPlayer player) {
        return ArmyCommandAuthority.player(player.getUUID(), player.hasPermissions(2));
    }

    private static StrategicArmyOrder order(byte code) {
        return switch (code) {
            case ArmiesProtocol.ORDER_HOLD -> StrategicArmyOrder.HOLD;
            case ArmiesProtocol.ORDER_MOVE -> StrategicArmyOrder.MOVE;
            case ArmiesProtocol.ORDER_RALLY -> StrategicArmyOrder.RALLY;
            case ArmiesProtocol.ORDER_LOGISTICS -> StrategicArmyOrder.LOGISTICS;
            default -> null;
        };
    }

    private void sendRoster(ServerPlayer player, int actionId, byte action, int result, int affected) {
        if (rosterProjection != null) {
            ArmiesNetwork.sendRoster(player, rosterProjection.snapshot(
                    player, actionId, action, result, affected));
        }
    }

    private static int commandResult(long result) {
        if (result >= 0 || result == ArmyCommandService.SUCCESS) {
            return ArmiesProtocol.RESULT_ACCEPTED;
        }
        return switch ((int) result) {
            case (int) ArmyCommandService.PERMISSION_DENIED -> ArmiesProtocol.RESULT_PERMISSION_DENIED;
            case (int) ArmyCommandService.ARMY_NOT_FOUND -> ArmiesProtocol.RESULT_NOT_FOUND;
            case (int) ArmyCommandService.LIMIT_REACHED -> ArmiesProtocol.RESULT_LIMIT_REACHED;
            default -> ArmiesProtocol.RESULT_INVALID;
        };
    }

    private static int recruitmentResult(long result) {
        return switch ((int) result) {
            case (int) MillenaireRecruitmentService.PERMISSION_DENIED,
                    (int) MillenaireRecruitmentService.WRONG_FACTION,
                    (int) MillenaireRecruitmentService.SETTLEMENT_NOT_CONTROLLED,
                    (int) MillenaireRecruitmentService.REPUTATION_TOO_LOW ->
                    ArmiesProtocol.RESULT_PERMISSION_DENIED;
            case (int) MillenaireRecruitmentService.ARMY_NOT_FOUND,
                    (int) MillenaireRecruitmentService.VILLAGE_NOT_FOUND,
                    (int) MillenaireRecruitmentService.VILLAGER_NOT_IN_VILLAGE,
                    (int) MillenaireRecruitmentService.VILLAGER_NOT_LOADED -> ArmiesProtocol.RESULT_NOT_FOUND;
            case (int) MillenaireRecruitmentService.UNIT_LIMIT_REACHED,
                    (int) MillenaireRecruitmentService.ARMY_LIMIT_REACHED,
                    (int) MillenaireRecruitmentService.ARMY_FULL -> ArmiesProtocol.RESULT_LIMIT_REACHED;
            default -> ArmiesProtocol.RESULT_INVALID;
        };
    }

    private static void setInt(int[] columns, int row, int column, int value) {
        columns[row * ArmiesProtocol.INT_COLUMNS + column] = value;
    }

    private static void setLong(long[] columns, int row, int column, long value) {
        columns[row * ArmiesProtocol.LONG_COLUMNS + column] = value;
    }

    private static void setByte(byte[] columns, int row, int column, byte value) {
        columns[row * ArmiesProtocol.BYTE_COLUMNS + column] = value;
    }

    private static final class VisibleArmies implements ArmyCommandService.ArmyViewSink {
        private final int[] handles = new int[ArmiesProtocol.MAX_ARMIES_PER_SNAPSHOT];
        private final int[] factions = new int[ArmiesProtocol.MAX_ARMIES_PER_SNAPSHOT];
        private final int[] orders = new int[ArmiesProtocol.MAX_ARMIES_PER_SNAPSHOT];
        private final int[] states = new int[ArmiesProtocol.MAX_ARMIES_PER_SNAPSHOT];
        private final int[] units = new int[ArmiesProtocol.MAX_ARMIES_PER_SNAPSHOT];
        private final long[] targets = new long[ArmiesProtocol.MAX_ARMIES_PER_SNAPSHOT];
        private int size;
        private byte scope;
        private int scopeHandle;

        void reset(byte scope, int scopeHandle) {
            this.size = 0;
            this.scope = scope;
            this.scopeHandle = scopeHandle;
        }

        @Override
        public void accept(int handle, int faction, int order, int state, int units, long packedTargetPosition) {
            if (size == handles.length
                    || scope == ArmiesProtocol.SCOPE_ARMY && handle != scopeHandle
                    || scope == ArmiesProtocol.SCOPE_FACTION && faction != scopeHandle) {
                return;
            }
            handles[size] = handle;
            factions[size] = faction;
            orders[size] = order;
            states[size] = state;
            this.units[size] = units;
            targets[size] = packedTargetPosition;
            size++;
        }

        boolean contains(int handle) {
            for (int index = 0; index < size; index++) {
                if (handles[index] == handle) {
                    return true;
                }
            }
            return false;
        }
    }
}
