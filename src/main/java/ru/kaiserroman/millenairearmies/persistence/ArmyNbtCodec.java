package ru.kaiserroman.millenairearmies.persistence;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.server.service.PackedArmyControllers;

/** Primitive-array NBT codec. All object allocation is confined to the cold save/load boundary. */
final class ArmyNbtCodec {
    static final int SCHEMA_VERSION = 1;

    private static final int MAX_ECS_ROWS = 1 << 20;
    private static final int NO_ARMY_ROW = -1;

    private static final String TAG_SCHEMA_VERSION = "SchemaVersion";
    private static final String TAG_DIMENSIONS = "DimensionNames";
    private static final String TAG_ITEMS = "ItemNames";
    private static final String TAG_FACTION_RELATIONS = "FactionRelations";
    private static final String TAG_ARMIES = "Armies";
    private static final String TAG_UNITS = "Units";
    private static final String TAG_CONTROLLERS = "Controllers";
    private static final String TAG_COMMANDS = "Commands";
    private static final String TAG_LOGISTICS = "Logistics";
    private static final String TAG_COUNT = "Count";

    private ArmyNbtCodec() {
    }

    static CompoundTag save(
            CompoundTag root,
            StableDimensionTable dimensions,
            StableItemTable items,
            PackedFactionState factions,
            PackedArmyEcs ecs,
            PackedUnitMembership memberships,
            PackedArmyControllers controllers,
            long armyRevision,
            PackedCommandState commands,
            PackedLogisticsState logistics) {
        root.putInt(TAG_SCHEMA_VERSION, SCHEMA_VERSION);

        ListTag dimensionNames = new ListTag();
        for (int dimensionId = 0; dimensionId < dimensions.size(); dimensionId++) {
            dimensionNames.add(StringTag.valueOf(dimensions.nameString(dimensionId)));
        }
        root.put(TAG_DIMENSIONS, dimensionNames);
        ListTag itemNames = new ListTag();
        for (int itemId = 0; itemId < items.size(); itemId++) {
            itemNames.add(StringTag.valueOf(items.nameString(itemId)));
        }
        root.put(TAG_ITEMS, itemNames);

        int relationCount = factions.size();
        int[] relationSources = new int[relationCount];
        int[] relationTargets = new int[relationCount];
        byte[] relationAllegiances = new byte[relationCount];
        int[] relationReputations = new int[relationCount];
        long[] relationRevisions = new long[relationCount];
        PackedFactionState.Cursor factionCursor = factions.newCursor();
        int row = 0;
        for (factionCursor.reset(); factionCursor.advance(); row++) {
            relationSources[row] = factionCursor.sourceFactionId();
            relationTargets[row] = factionCursor.targetFactionId();
            relationAllegiances[row] = factionCursor.allegianceCode();
            relationReputations[row] = factionCursor.reputation();
            relationRevisions[row] = factionCursor.revision();
        }
        CompoundTag factionTag = new CompoundTag();
        factionTag.putInt(TAG_COUNT, relationCount);
        factionTag.putLong("NextRevision", factions.nextRevision());
        factionTag.putIntArray("Sources", relationSources);
        factionTag.putIntArray("Targets", relationTargets);
        factionTag.putByteArray("Allegiances", relationAllegiances);
        factionTag.putIntArray("Reputations", relationReputations);
        factionTag.putLongArray("Revisions", relationRevisions);
        root.put(TAG_FACTION_RELATIONS, factionTag);

        int armyCount = ecs.armySize();
        int[] armyFactions = new int[armyCount];
        int[] armyOrders = new int[armyCount];
        int[] armyStates = new int[armyCount];
        long[] armyTargets = new long[armyCount];
        IntIntTable armyRows = new IntIntTable(armyCount);

        PackedArmyEcs.ArmyCursor armyCursor = ecs.newArmyCursor();
        row = 0;
        for (armyCursor.reset(); armyCursor.advance(); row++) {
            int handle = armyCursor.handle();
            armyFactions[row] = armyCursor.faction();
            armyOrders[row] = armyCursor.order();
            armyStates[row] = armyCursor.state();
            armyTargets[row] = armyCursor.packedTargetPos();
            armyRows.put(handle, row);
        }

        CompoundTag armyTag = new CompoundTag();
        armyTag.putInt(TAG_COUNT, armyCount);
        armyTag.putLong("Revision", armyRevision);
        armyTag.putIntArray("Factions", armyFactions);
        armyTag.putIntArray("Orders", armyOrders);
        armyTag.putIntArray("States", armyStates);
        armyTag.putLongArray("Targets", armyTargets);
        root.put(TAG_ARMIES, armyTag);

        int controllerCount = controllers.size();
        int[] controllerArmyRows = new int[controllerCount];
        long[] controllerUuidMost = new long[controllerCount];
        long[] controllerUuidLeast = new long[controllerCount];
        byte[] controllerPresent = new byte[controllerCount];
        PackedArmyControllers.Cursor controllerCursor = controllers.newCursor();
        row = 0;
        for (controllerCursor.reset(); controllerCursor.advance(); row++) {
            controllerArmyRows[row] = persistentArmyRow(controllerCursor.armyHandle(), armyRows, "controller");
            controllerUuidMost[row] = controllerCursor.uuidMost();
            controllerUuidLeast[row] = controllerCursor.uuidLeast();
            controllerPresent[row] = controllerCursor.hasController() ? (byte) 1 : (byte) 0;
        }
        CompoundTag controllerTag = new CompoundTag();
        controllerTag.putInt(TAG_COUNT, controllerCount);
        controllerTag.putIntArray("ArmyRows", controllerArmyRows);
        controllerTag.putLongArray("UuidMost", controllerUuidMost);
        controllerTag.putLongArray("UuidLeast", controllerUuidLeast);
        controllerTag.putByteArray("Present", controllerPresent);
        root.put(TAG_CONTROLLERS, controllerTag);

        int unitCount = ecs.unitSize();
        int[] unitArmyRows = new int[unitCount];
        int[] unitOrders = new int[unitCount];
        int[] unitStates = new int[unitCount];
        long[] unitPositions = new long[unitCount];
        byte[] unitIdentityPresent = new byte[unitCount];
        long[] unitUuidMost = new long[unitCount];
        long[] unitUuidLeast = new long[unitCount];
        IntIntTable membershipRows = new IntIntTable(memberships.size());
        long[] membershipUuidMost = new long[memberships.size()];
        long[] membershipUuidLeast = new long[memberships.size()];
        PackedUnitMembership.Cursor membershipCursor = memberships.newCursor();
        int membershipRow = 0;
        for (membershipCursor.reset(); membershipCursor.advance(); membershipRow++) {
            if (!ecs.isUnitAlive(membershipCursor.unitHandle())) {
                throw new IllegalStateException(
                        "Cannot save membership for stale unit handle " + membershipCursor.unitHandle());
            }
            membershipRows.put(membershipCursor.unitHandle(), membershipRow);
            membershipUuidMost[membershipRow] = membershipCursor.uuidMost();
            membershipUuidLeast[membershipRow] = membershipCursor.uuidLeast();
        }
        PackedArmyEcs.UnitCursor unitCursor = ecs.newUnitCursor();
        row = 0;
        for (unitCursor.reset(); unitCursor.advance(); row++) {
            unitArmyRows[row] = persistentArmyRow(unitCursor.army(), armyRows, "unit");
            unitOrders[row] = unitCursor.order();
            unitStates[row] = unitCursor.state();
            unitPositions[row] = unitCursor.packedPos();
            int identityRow = membershipRows.get(unitCursor.handle());
            if (identityRow >= 0) {
                unitIdentityPresent[row] = 1;
                unitUuidMost[row] = membershipUuidMost[identityRow];
                unitUuidLeast[row] = membershipUuidLeast[identityRow];
            }
        }

        CompoundTag unitTag = new CompoundTag();
        unitTag.putInt(TAG_COUNT, unitCount);
        unitTag.putIntArray("ArmyRows", unitArmyRows);
        unitTag.putIntArray("Orders", unitOrders);
        unitTag.putIntArray("States", unitStates);
        unitTag.putLongArray("Positions", unitPositions);
        unitTag.putByteArray("IdentityPresent", unitIdentityPresent);
        unitTag.putLongArray("UuidMost", unitUuidMost);
        unitTag.putLongArray("UuidLeast", unitUuidLeast);
        root.put(TAG_UNITS, unitTag);

        int commandCount = commands.size();
        long[] orderIds = new long[commandCount];
        int[] commandArmyRows = new int[commandCount];
        int[] issuerFactionIds = new int[commandCount];
        byte[] typeCodes = new byte[commandCount];
        int[] dimensionIds = new int[commandCount];
        long[] primaryPositions = new long[commandCount];
        long[] secondaryPositions = new long[commandCount];
        long[] subjectUuidMost = new long[commandCount];
        long[] subjectUuidLeast = new long[commandCount];
        long[] issuedGameTimes = new long[commandCount];
        byte[] flags = new byte[commandCount];

        PackedCommandState.Cursor commandCursor = commands.newCursor();
        row = 0;
        for (commandCursor.reset(); commandCursor.advance(); row++) {
            orderIds[row] = commandCursor.orderId();
            commandArmyRows[row] = persistentArmyRow(commandCursor.armyHandle(), armyRows, "command");
            issuerFactionIds[row] = commandCursor.issuerFactionId();
            typeCodes[row] = commandCursor.typeCode();
            dimensionIds[row] = commandCursor.dimensionId();
            requireDimensionId(dimensions, dimensionIds[row], "command");
            primaryPositions[row] = commandCursor.primaryPosition();
            secondaryPositions[row] = commandCursor.secondaryPosition();
            subjectUuidMost[row] = commandCursor.subjectUuidMost();
            subjectUuidLeast[row] = commandCursor.subjectUuidLeast();
            issuedGameTimes[row] = commandCursor.issuedGameTime();
            flags[row] = commandCursor.flags();
        }

        CompoundTag commandTag = new CompoundTag();
        commandTag.putInt(TAG_COUNT, commandCount);
        commandTag.putLong("NextOrderId", commands.nextOrderId());
        commandTag.putLong("Revision", commands.revision());
        commandTag.putLongArray("OrderIds", orderIds);
        commandTag.putIntArray("ArmyRows", commandArmyRows);
        commandTag.putIntArray("IssuerFactions", issuerFactionIds);
        commandTag.putByteArray("Types", typeCodes);
        commandTag.putIntArray("Dimensions", dimensionIds);
        commandTag.putLongArray("PrimaryPositions", primaryPositions);
        commandTag.putLongArray("SecondaryPositions", secondaryPositions);
        commandTag.putLongArray("SubjectUuidMost", subjectUuidMost);
        commandTag.putLongArray("SubjectUuidLeast", subjectUuidLeast);
        commandTag.putLongArray("IssuedGameTimes", issuedGameTimes);
        commandTag.putByteArray("Flags", flags);
        root.put(TAG_COMMANDS, commandTag);

        int logisticsCount = logistics.size();
        long[] requestIds = new long[logisticsCount];
        int[] logisticsFactions = new int[logisticsCount];
        int[] requesterArmyRows = new int[logisticsCount];
        int[] requiredAmounts = new int[logisticsCount];
        int[] fulfilledAmounts = new int[logisticsCount];
        int[] logisticsDimensions = new int[logisticsCount];
        long[] destinations = new long[logisticsCount];
        long[] createdGameTimes = new long[logisticsCount];
        byte[] priorities = new byte[logisticsCount];
        byte[] statuses = new byte[logisticsCount];
        long[] logisticsRevisions = new long[logisticsCount];
        int[] logisticsItems = new int[logisticsCount];

        PackedLogisticsState.Cursor logisticsCursor = logistics.newCursor();
        row = 0;
        for (logisticsCursor.reset(); logisticsCursor.advance(); row++) {
            requestIds[row] = logisticsCursor.requestId();
            logisticsFactions[row] = logisticsCursor.factionId();
            requesterArmyRows[row] =
                    persistentArmyRow(logisticsCursor.requesterArmyHandle(), armyRows, "logistics request");
            int itemKey = logisticsCursor.itemKey();
            requireItemId(items, itemKey);
            logisticsItems[row] = itemKey;
            requiredAmounts[row] = logisticsCursor.requiredAmount();
            fulfilledAmounts[row] = logisticsCursor.fulfilledAmount();
            logisticsDimensions[row] = logisticsCursor.dimensionId();
            requireDimensionId(dimensions, logisticsDimensions[row], "logistics request");
            destinations[row] = logisticsCursor.destination();
            createdGameTimes[row] = logisticsCursor.createdGameTime();
            priorities[row] = logisticsCursor.priority();
            statuses[row] = logisticsCursor.statusCode();
            logisticsRevisions[row] = logisticsCursor.revision();
        }
        CompoundTag logisticsTag = new CompoundTag();
        logisticsTag.putInt(TAG_COUNT, logisticsCount);
        logisticsTag.putLong("NextRequestId", logistics.nextRequestId());
        logisticsTag.putLong("NextRevision", logistics.nextRevision());
        logisticsTag.putLongArray("RequestIds", requestIds);
        logisticsTag.putIntArray("Factions", logisticsFactions);
        logisticsTag.putIntArray("ArmyRows", requesterArmyRows);
        logisticsTag.putIntArray("Items", logisticsItems);
        logisticsTag.putIntArray("RequiredAmounts", requiredAmounts);
        logisticsTag.putIntArray("FulfilledAmounts", fulfilledAmounts);
        logisticsTag.putIntArray("Dimensions", logisticsDimensions);
        logisticsTag.putLongArray("Destinations", destinations);
        logisticsTag.putLongArray("CreatedGameTimes", createdGameTimes);
        logisticsTag.putByteArray("Priorities", priorities);
        logisticsTag.putByteArray("Statuses", statuses);
        logisticsTag.putLongArray("Revisions", logisticsRevisions);
        root.put(TAG_LOGISTICS, logisticsTag);
        return root;
    }

    static LoadedState load(
            CompoundTag root, int maxFactionRelations, int maxCommands, int maxLogisticsRequests) {
        int schemaVersion = root.getInt(TAG_SCHEMA_VERSION);
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported Millenaire Armies save schema " + schemaVersion + "; expected " + SCHEMA_VERSION);
        }

        ListTag dimensionNames = root.getList(TAG_DIMENSIONS, Tag.TAG_STRING);
        StableDimensionTable dimensions = new StableDimensionTable();
        for (int dimensionId = 0; dimensionId < dimensionNames.size(); dimensionId++) {
            int restoredId = dimensions.intern(dimensionNames.getString(dimensionId));
            if (restoredId != dimensionId) {
                throw new IllegalArgumentException("Duplicate persisted dimension name at row " + dimensionId);
            }
        }
        ListTag itemNames = root.getList(TAG_ITEMS, Tag.TAG_STRING);
        StableItemTable items = new StableItemTable();
        for (int itemId = 0; itemId < itemNames.size(); itemId++) {
            int restoredId = items.intern(itemNames.getString(itemId));
            if (restoredId != itemId) {
                throw new IllegalArgumentException("Duplicate persisted item name at row " + itemId);
            }
        }

        CompoundTag factionTag = root.getCompound(TAG_FACTION_RELATIONS);
        int relationCount = checkedCount(factionTag, TAG_FACTION_RELATIONS, maxFactionRelations);
        int[] relationSources = requiredIntArray(factionTag, "Sources", relationCount, TAG_FACTION_RELATIONS);
        int[] relationTargets = requiredIntArray(factionTag, "Targets", relationCount, TAG_FACTION_RELATIONS);
        byte[] relationAllegiances =
                requiredByteArray(factionTag, "Allegiances", relationCount, TAG_FACTION_RELATIONS);
        int[] relationReputations =
                requiredIntArray(factionTag, "Reputations", relationCount, TAG_FACTION_RELATIONS);
        long[] relationRevisions =
                requiredLongArray(factionTag, "Revisions", relationCount, TAG_FACTION_RELATIONS);
        PackedFactionState factions = new PackedFactionState(relationCount);
        LongSetTable relationPairs = new LongSetTable(relationCount);
        for (int relationRow = 0; relationRow < relationCount; relationRow++) {
            int reputation = relationReputations[relationRow];
            if (reputation < Short.MIN_VALUE || reputation > Short.MAX_VALUE) {
                throw new IllegalArgumentException("Persisted faction reputation outside short range: " + reputation);
            }
            long pair = (long) relationSources[relationRow] << 32 | relationTargets[relationRow] & 0xffffffffL;
            if (!relationPairs.addNonZero(pair)) {
                throw new IllegalArgumentException("Duplicate persisted faction relation");
            }
            factions.restore(
                    relationSources[relationRow],
                    relationTargets[relationRow],
                    relationAllegiances[relationRow],
                    (short) reputation,
                    relationRevisions[relationRow]);
        }
        factions.restoreNextRevision(factionTag.getLong("NextRevision"));

        CompoundTag armyTag = root.getCompound(TAG_ARMIES);
        int armyCount = checkedCount(armyTag, TAG_ARMIES, MAX_ECS_ROWS);
        long armyRevision = armyTag.getLong("Revision");
        if (armyRevision < 0L) {
            throw new IllegalArgumentException("Army revision must be non-negative");
        }
        int[] armyFactions = requiredIntArray(armyTag, "Factions", armyCount, TAG_ARMIES);
        int[] armyOrders = requiredIntArray(armyTag, "Orders", armyCount, TAG_ARMIES);
        int[] armyStates = requiredIntArray(armyTag, "States", armyCount, TAG_ARMIES);
        long[] armyTargets = requiredLongArray(armyTag, "Targets", armyCount, TAG_ARMIES);

        CompoundTag unitTag = root.getCompound(TAG_UNITS);
        int unitCount = checkedCount(unitTag, TAG_UNITS, MAX_ECS_ROWS);
        int[] unitArmyRows = requiredIntArray(unitTag, "ArmyRows", unitCount, TAG_UNITS);
        int[] unitOrders = requiredIntArray(unitTag, "Orders", unitCount, TAG_UNITS);
        int[] unitStates = requiredIntArray(unitTag, "States", unitCount, TAG_UNITS);
        long[] unitPositions = requiredLongArray(unitTag, "Positions", unitCount, TAG_UNITS);
        byte[] unitIdentityPresent = requiredByteArray(unitTag, "IdentityPresent", unitCount, TAG_UNITS);
        long[] unitUuidMost = requiredLongArray(unitTag, "UuidMost", unitCount, TAG_UNITS);
        long[] unitUuidLeast = requiredLongArray(unitTag, "UuidLeast", unitCount, TAG_UNITS);

        PackedArmyEcs ecs = new PackedArmyEcs(armyCount, unitCount);
        PackedUnitMembership memberships = new PackedUnitMembership();
        if (memberships.usesPrimitiveIndex()) {
            memberships.reserve(unitCount);
        }
        int[] restoredArmyHandles = new int[armyCount];
        for (int armyRow = 0; armyRow < armyCount; armyRow++) {
            restoredArmyHandles[armyRow] = ecs.createArmy(
                    armyFactions[armyRow], armyOrders[armyRow], armyStates[armyRow], armyTargets[armyRow]);
        }

        CompoundTag controllerTag = root.getCompound(TAG_CONTROLLERS);
        int controllerCount = checkedCount(controllerTag, TAG_CONTROLLERS, armyCount);
        int[] controllerArmyRows = requiredIntArray(controllerTag, "ArmyRows", controllerCount, TAG_CONTROLLERS);
        long[] controllerUuidMost = requiredLongArray(controllerTag, "UuidMost", controllerCount, TAG_CONTROLLERS);
        long[] controllerUuidLeast = requiredLongArray(controllerTag, "UuidLeast", controllerCount, TAG_CONTROLLERS);
        byte[] controllerPresent = requiredByteArray(controllerTag, "Present", controllerCount, TAG_CONTROLLERS);
        PackedArmyControllers controllers = new PackedArmyControllers(controllerCount);
        byte[] controllerArmySeen = new byte[armyCount];
        for (int controllerRow = 0; controllerRow < controllerCount; controllerRow++) {
            int armyRow = controllerArmyRows[controllerRow];
            int armyHandle = restoredArmyHandle(armyRow, restoredArmyHandles, "controller");
            if (armyHandle == PackedArmyEcs.NO_ARMY
                    || controllerArmySeen[armyRow] != 0
                    || controllerPresent[controllerRow] < 0
                    || controllerPresent[controllerRow] > 1) {
                throw new IllegalArgumentException("Invalid persisted army controller row " + controllerRow);
            }
            controllerArmySeen[armyRow] = 1;
            controllers.put(
                    armyHandle,
                    controllerUuidMost[controllerRow],
                    controllerUuidLeast[controllerRow],
                    controllerPresent[controllerRow] != 0);
        }
        for (int unitRow = 0; unitRow < unitCount; unitRow++) {
            int armyHandle = restoredArmyHandle(unitArmyRows[unitRow], restoredArmyHandles, "unit");
            int unitHandle = ecs.createUnit(armyHandle, unitOrders[unitRow], unitStates[unitRow], unitPositions[unitRow]);
            if (unitIdentityPresent[unitRow] == 1) {
                memberships.bind(unitHandle, unitUuidMost[unitRow], unitUuidLeast[unitRow]);
            } else if (unitIdentityPresent[unitRow] != 0) {
                throw new IllegalArgumentException("Persisted unit identity flag is not 0/1");
            }
        }

        CompoundTag commandTag = root.getCompound(TAG_COMMANDS);
        int commandCount = checkedCount(commandTag, TAG_COMMANDS, maxCommands);
        long[] orderIds = requiredLongArray(commandTag, "OrderIds", commandCount, TAG_COMMANDS);
        int[] commandArmyRows = requiredIntArray(commandTag, "ArmyRows", commandCount, TAG_COMMANDS);
        int[] issuerFactionIds = requiredIntArray(commandTag, "IssuerFactions", commandCount, TAG_COMMANDS);
        byte[] typeCodes = requiredByteArray(commandTag, "Types", commandCount, TAG_COMMANDS);
        int[] dimensionIds = requiredIntArray(commandTag, "Dimensions", commandCount, TAG_COMMANDS);
        long[] primaryPositions = requiredLongArray(commandTag, "PrimaryPositions", commandCount, TAG_COMMANDS);
        long[] secondaryPositions = requiredLongArray(commandTag, "SecondaryPositions", commandCount, TAG_COMMANDS);
        long[] subjectUuidMost = requiredLongArray(commandTag, "SubjectUuidMost", commandCount, TAG_COMMANDS);
        long[] subjectUuidLeast = requiredLongArray(commandTag, "SubjectUuidLeast", commandCount, TAG_COMMANDS);
        long[] issuedGameTimes = requiredLongArray(commandTag, "IssuedGameTimes", commandCount, TAG_COMMANDS);
        byte[] flags = requiredByteArray(commandTag, "Flags", commandCount, TAG_COMMANDS);

        PackedCommandState commands = new PackedCommandState(commandCount);
        LongSetTable persistedOrderIds = new LongSetTable(commandCount);
        for (int commandRow = 0; commandRow < commandCount; commandRow++) {
            if (!persistedOrderIds.addPositive(orderIds[commandRow])) {
                throw new IllegalArgumentException("Duplicate persisted order id " + orderIds[commandRow]);
            }
            int armyHandle = restoredArmyHandle(commandArmyRows[commandRow], restoredArmyHandles, "command");
            requireDimensionId(dimensions, dimensionIds[commandRow], "command");
            commands.restore(
                    orderIds[commandRow],
                    armyHandle,
                    issuerFactionIds[commandRow],
                    typeCodes[commandRow],
                    dimensionIds[commandRow],
                    primaryPositions[commandRow],
                    secondaryPositions[commandRow],
                    subjectUuidMost[commandRow],
                    subjectUuidLeast[commandRow],
                    issuedGameTimes[commandRow],
                    flags[commandRow]);
        }
        commands.restoreNextOrderId(commandTag.getLong("NextOrderId"));
        commands.restoreRevision(commandTag.getLong("Revision"));

        CompoundTag logisticsTag = root.getCompound(TAG_LOGISTICS);
        int logisticsCount = checkedCount(logisticsTag, TAG_LOGISTICS, maxLogisticsRequests);
        long[] requestIds = requiredLongArray(logisticsTag, "RequestIds", logisticsCount, TAG_LOGISTICS);
        int[] logisticsFactions = requiredIntArray(logisticsTag, "Factions", logisticsCount, TAG_LOGISTICS);
        int[] requesterArmyRows = requiredIntArray(logisticsTag, "ArmyRows", logisticsCount, TAG_LOGISTICS);
        int[] logisticsItems = requiredIntArray(logisticsTag, "Items", logisticsCount, TAG_LOGISTICS);
        int[] requiredAmounts =
                requiredIntArray(logisticsTag, "RequiredAmounts", logisticsCount, TAG_LOGISTICS);
        int[] fulfilledAmounts =
                requiredIntArray(logisticsTag, "FulfilledAmounts", logisticsCount, TAG_LOGISTICS);
        int[] logisticsDimensions =
                requiredIntArray(logisticsTag, "Dimensions", logisticsCount, TAG_LOGISTICS);
        long[] destinations = requiredLongArray(logisticsTag, "Destinations", logisticsCount, TAG_LOGISTICS);
        long[] createdGameTimes =
                requiredLongArray(logisticsTag, "CreatedGameTimes", logisticsCount, TAG_LOGISTICS);
        byte[] priorities = requiredByteArray(logisticsTag, "Priorities", logisticsCount, TAG_LOGISTICS);
        byte[] statuses = requiredByteArray(logisticsTag, "Statuses", logisticsCount, TAG_LOGISTICS);
        long[] logisticsRevisions =
                requiredLongArray(logisticsTag, "Revisions", logisticsCount, TAG_LOGISTICS);

        PackedLogisticsState logistics = new PackedLogisticsState(logisticsCount);
        LongSetTable persistedRequestIds = new LongSetTable(logisticsCount);
        for (int logisticsRow = 0; logisticsRow < logisticsCount; logisticsRow++) {
            if (!persistedRequestIds.addPositive(requestIds[logisticsRow])) {
                throw new IllegalArgumentException("Duplicate persisted logistics request id " + requestIds[logisticsRow]);
            }
            int itemKey = logisticsItems[logisticsRow];
            requireItemId(items, itemKey);
            requireDimensionId(dimensions, logisticsDimensions[logisticsRow], "logistics request");
            int armyHandle =
                    restoredArmyHandle(requesterArmyRows[logisticsRow], restoredArmyHandles, "logistics request");
            logistics.restore(
                    requestIds[logisticsRow],
                    logisticsFactions[logisticsRow],
                    armyHandle,
                    itemKey,
                    requiredAmounts[logisticsRow],
                    fulfilledAmounts[logisticsRow],
                    logisticsDimensions[logisticsRow],
                    destinations[logisticsRow],
                    createdGameTimes[logisticsRow],
                    priorities[logisticsRow],
                    statuses[logisticsRow],
                    logisticsRevisions[logisticsRow]);
        }
        logistics.restoreCounters(logisticsTag.getLong("NextRequestId"), logisticsTag.getLong("NextRevision"));
        return new LoadedState(
                dimensions, items, factions, ecs, memberships, controllers, armyRevision, commands, logistics);
    }

    private static void requireDimensionId(StableDimensionTable dimensions, int dimensionId, String owner) {
        if (dimensionId < 0 || dimensionId >= dimensions.size()) {
            throw new IllegalArgumentException(owner + " references unknown dimension dictionary id " + dimensionId);
        }
    }

    private static void requireItemId(StableItemTable items, int itemId) {
        if (itemId < 0 || itemId >= items.size()) {
            throw new IllegalArgumentException("Logistics request references unknown item dictionary id " + itemId);
        }
    }

    private static int persistentArmyRow(int armyHandle, IntIntTable armyRows, String owner) {
        if (armyHandle == PackedArmyEcs.NO_ARMY) {
            return NO_ARMY_ROW;
        }
        int row = armyRows.get(armyHandle);
        if (row < 0) {
            throw new IllegalStateException("Cannot save " + owner + " referencing stale army handle " + armyHandle);
        }
        return row;
    }

    private static int restoredArmyHandle(int armyRow, int[] restoredArmyHandles, String owner) {
        if (armyRow == NO_ARMY_ROW) {
            return PackedArmyEcs.NO_ARMY;
        }
        if (armyRow < 0 || armyRow >= restoredArmyHandles.length) {
            throw new IllegalArgumentException("Persisted " + owner + " has invalid army row " + armyRow);
        }
        return restoredArmyHandles[armyRow];
    }

    private static int checkedCount(CompoundTag tag, String section, int maximum) {
        int count = tag.getInt(TAG_COUNT);
        if (count < 0 || count > maximum) {
            throw new IllegalArgumentException(section + " count outside 0.." + maximum + ": " + count);
        }
        return count;
    }

    private static int[] requiredIntArray(CompoundTag tag, String key, int count, String section) {
        int[] values = tag.getIntArray(key);
        requireLength(values.length, count, section, key);
        return values;
    }

    private static long[] requiredLongArray(CompoundTag tag, String key, int count, String section) {
        long[] values = tag.getLongArray(key);
        requireLength(values.length, count, section, key);
        return values;
    }

    private static byte[] requiredByteArray(CompoundTag tag, String key, int count, String section) {
        byte[] values = tag.getByteArray(key);
        requireLength(values.length, count, section, key);
        return values;
    }

    private static void requireLength(int actual, int expected, String section, String key) {
        if (actual != expected) {
            throw new IllegalArgumentException(
                    section + '.' + key + " length " + actual + " does not match count " + expected);
        }
    }

    record LoadedState(
            StableDimensionTable dimensions,
            StableItemTable items,
            PackedFactionState factions,
            PackedArmyEcs ecs,
            PackedUnitMembership memberships,
            PackedArmyControllers controllers,
            long armyRevision,
            PackedCommandState commands,
            PackedLogisticsState logistics) {
    }

    /** Small primitive open-addressed table used only while serializing an ECS snapshot. */
    private static final class IntIntTable {
        private final int[] keys;
        private final int[] values;
        private final int mask;

        IntIntTable(int expectedSize) {
            int capacity = 1;
            int required = Math.max(2, expectedSize * 2);
            while (capacity < required) {
                capacity <<= 1;
            }
            keys = new int[capacity];
            values = new int[capacity];
            mask = capacity - 1;
        }

        void put(int key, int value) {
            if (key == 0) {
                throw new IllegalArgumentException("Zero is reserved as an empty army handle");
            }
            int index = mix(key) & mask;
            while (keys[index] != 0 && keys[index] != key) {
                index = index + 1 & mask;
            }
            keys[index] = key;
            values[index] = value;
        }

        int get(int key) {
            int index = mix(key) & mask;
            while (keys[index] != 0) {
                if (keys[index] == key) {
                    return values[index];
                }
                index = index + 1 & mask;
            }
            return -1;
        }

        private static int mix(int value) {
            int mixed = value * 0x9E3779B9;
            return mixed ^ mixed >>> 16;
        }
    }

    /** Small primitive set used to reject duplicate command identities while loading. */
    private static final class LongSetTable {
        private final long[] keys;
        private final int mask;

        LongSetTable(int expectedSize) {
            int capacity = 1;
            int required = Math.max(2, expectedSize * 2);
            while (capacity < required) {
                capacity <<= 1;
            }
            keys = new long[capacity];
            mask = capacity - 1;
        }

        boolean addPositive(long key) {
            if (key <= 0L) {
                throw new IllegalArgumentException("Persisted order id must be positive: " + key);
            }
            return addNonZero(key);
        }

        boolean addNonZero(long key) {
            if (key == 0L) {
                throw new IllegalArgumentException("Zero cannot be stored in the primitive set");
            }
            int index = mix(key) & mask;
            while (keys[index] != 0L) {
                if (keys[index] == key) {
                    return false;
                }
                index = index + 1 & mask;
            }
            keys[index] = key;
            return true;
        }

        private static int mix(long value) {
            value ^= value >>> 33;
            value *= 0xff51afd7ed558ccdL;
            value ^= value >>> 33;
            return (int) (value ^ value >>> 32);
        }
    }
}
