package ru.kaiserroman.millenairearmies.persistence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import ru.kaiserroman.millenairearmies.ecs.PackedArmyEcs;
import ru.kaiserroman.millenairearmies.model.ArmyOrder;
import ru.kaiserroman.millenairearmies.model.ArmyOrderType;
import ru.kaiserroman.millenairearmies.model.FactionAllegiance;
import ru.kaiserroman.millenairearmies.server.unit.PackedUnitRoleState;
import ru.kaiserroman.millenairearmies.server.service.PackedArmyControllers;

/** Run with assertions enabled; exercises an in-memory NBT save/load/save round trip. */
public final class ArmyPersistenceSelfTest {
    private ArmyPersistenceSelfTest() {
    }

    public static void main(String[] args) {
        roundTripRemapsHandlesAndPreservesPrimitiveState();
        signedGenerationHandlesRoundTrip();
        corruptLengthsAndUnknownVersionsAreRejected();
        System.out.println("Army persistence self-test passed");
    }

    private static void roundTripRemapsHandlesAndPreservesPrimitiveState() {
        StableDimensionTable dimensions = new StableDimensionTable();
        int overworld = dimensions.intern(Level.OVERWORLD.location());
        int nether = dimensions.intern(Level.NETHER.location());
        PackedArmyEcs sourceEcs = new PackedArmyEcs(4, 8);
        int removed = sourceEcs.createArmy(10, 1, 2, overworld, PackedArmyEcs.packBlockPos(1, 64, 2));
        int blue = sourceEcs.createArmy(20, 3, 4, overworld, PackedArmyEcs.packBlockPos(3, 65, 4));
        sourceEcs.removeArmy(removed);
        int red = sourceEcs.createArmy(30, 5, 6, nether, PackedArmyEcs.packBlockPos(5, 66, 6));

        int blueUnit = sourceEcs.createUnit(blue, 11, 21, PackedArmyEcs.packBlockPos(7, 67, 8));
        int redUnit = sourceEcs.createUnit(red, 12, 22, PackedArmyEcs.packBlockPos(9, 68, 10));
        sourceEcs.createUnit(PackedArmyEcs.NO_ARMY, 13, 23, PackedArmyEcs.packBlockPos(11, 69, 12));
        PackedUnitRoleState sourceRoles = new PackedUnitRoleState();
        sourceRoles.assign(
                blueUnit, 10_001, 20_001, 30_001, PackedUnitRoleState.TROOP_CLASS_REGULAR);
        sourceRoles.assign(
                redUnit, 10_002, 20_002, 30_002, PackedUnitRoleState.TROOP_CLASS_NOBLE);
        sourceRoles.assignLoadoutOnly(redUnit, 30_003);
        sourceRoles.recordUpkeepMissed(redUnit);
        sourceRoles.recordUpkeepMissed(redUnit);
        sourceRoles.markEquipmentProjected(blueUnit);
        long sourceRoleRevision = sourceRoles.revision();
        PackedArmySupplyState sourceSupplies = new PackedArmySupplyState();
        sourceSupplies.assign(blue, overworld, PackedArmyEcs.packBlockPos(12, 64, 13));
        long sourceSupplyRevision = sourceSupplies.revision();

        StableItemTable items = new StableItemTable();
        int ironKey = items.intern(ResourceLocation.parse("minecraft:iron_ingot"));
        PackedFactionState factions = new PackedFactionState(2);
        factions.put(20, 30, FactionAllegiance.HOSTILE.code(), (short) -250);
        PackedUnitMembership memberships = new PackedUnitMembership();
        memberships.bind(blueUnit, 0x1111L, 0x2222L);
        memberships.bind(redUnit, 0x3333L, 0x4444L);
        PackedArmyControllers controllers = new PackedArmyControllers(2);
        controllers.put(blue, 0xaaaaL, 0xbbbbL, true);

        PackedCommandState sourceCommands = new PackedCommandState(4);
        long moveId = sourceCommands.add(
                blue,
                20,
                ArmyOrderType.MOVE.code(),
                overworld,
                PackedArmyEcs.packBlockPos(100, 70, 101),
                0L,
                0L,
                0L,
                1_000L,
                (byte) 0);
        long escortId = sourceCommands.add(
                red,
                30,
                ArmyOrderType.ESCORT.code(),
                nether,
                PackedArmyEcs.packBlockPos(102, 71, 103),
                PackedArmyEcs.packBlockPos(104, 72, 105),
                0x7777L,
                0x8888L,
                1_001L,
                (byte) (ArmyOrder.FLAG_HAS_SECONDARY_POSITION | ArmyOrder.FLAG_HAS_SUBJECT_ENTITY));

        PackedLogisticsState logistics = new PackedLogisticsState(1);
        logistics.add(20, blue, ironKey, 64, overworld, PackedArmyEcs.packBlockPos(8, 70, 9), 999L, (byte) 3);
        int breadKey = items.intern(ResourceLocation.parse("minecraft:bread"));
        int leatherKey = items.intern(ResourceLocation.parse("minecraft:leather"));
        int arrowKey = items.intern(ResourceLocation.parse("minecraft:arrow"));
        PackedSettlementEconomyState economy = new PackedSettlementEconomyState();
        economy.configureCommodityKeys(breadKey, ironKey, leatherKey, arrowKey);
        int producer = economy.upsertSettlement(0x101L, 0x201L, 20, overworld, 1L, 200L);
        int consumer = economy.upsertSettlement(0x102L, 0x202L, 20, overworld, 2L, 200L);
        economy.configureRates(producer, 0, 16, 3, 1);
        economy.configureRates(consumer, 0, 32, 0, 2);
        economy.observePhysicalStock(producer, 0, 64);
        economy.observePhysicalStock(consumer, 0, 0);
        check(economy.tryDebit(producer, 0, 24), "economy test shipment debited");
        economy.addShipment(producer, consumer, 0, 24, 400L);
        PackedGarrisonState garrisons = new PackedGarrisonState(1);
        check(garrisons.assign(
                blue,
                0x101L,
                0x201L,
                overworld,
                PackedArmyEcs.packBlockPos(10, 64, 11),
                32,
                1_200L), "garrison binding created");
        check(garrisons.recordUpkeep(blue, false, 2_400L), "garrison upkeep state changed");

        ArmySavedData source = new ArmySavedData(
                dimensions,
                items,
                factions,
                sourceEcs,
                memberships,
                controllers,
                7L,
                sourceCommands,
                logistics,
                economy,
                garrisons,
                sourceRoles,
                sourceSupplies);
        CompoundTag encoded = source.save(new CompoundTag(), null);
        check(encoded.getInt("SchemaVersion") == ArmyNbtCodec.SCHEMA_VERSION, "schema written");
        check(encoded.getCompound("Armies").getInt("Count") == 2, "army count written");
        check(encoded.getCompound("Units").getInt("Count") == 3, "unit count written");
        check(encoded.getCompound("Commands").getInt("Count") == 2, "command count written");
        check(encoded.getCompound("Garrisons").getInt("Count") == 1, "garrison count written");

        ArmySavedData restored = ArmySavedData.load(binaryRoundTrip(encoded), null);
        check(restored.ecs().armySize() == 2, "army count restored");
        check(restored.ecs().unitSize() == 3, "unit count restored");
        check(restored.commands().size() == 2, "command count restored");
        check(restored.factions().size() == 1, "faction relation restored");
        check(restored.memberships().size() == 2, "unit memberships restored");
        check(restored.controllers().size() == 1, "controller restored");
        check(restored.logistics().size() == 1, "logistics restored");
        check(restored.settlementEconomy().settlementCount() == 2, "settlements restored");
        check(restored.settlementEconomy().shipmentCount() == 1, "shipment WAL restored");
        check(restored.garrisons().size() == 1, "garrison binding restored");
        check(restored.settlementEconomy().shipmentStatusAt(0)
                        == PackedSettlementEconomyState.SHIPMENT_IN_TRANSIT,
                "in-transit settlement shipment restored");
        check(restored.armyRevision() == 7L, "army revision restored");
        check(restored.dimensions().name(nether).equals(Level.NETHER.location()), "dimension dictionary restored");
        check(restored.items().name(ironKey).equals(ResourceLocation.parse("minecraft:iron_ingot")),
                "item dictionary restored");

        int restoredBlue = armyByFaction(restored.ecs(), 20);
        int restoredRed = armyByFaction(restored.ecs(), 30);
        check(restoredBlue != PackedArmyEcs.NO_ARMY, "blue restored");
        check(restoredRed != PackedArmyEcs.NO_ARMY, "red restored");
        check(restored.ecs().armyOrder(restoredBlue) == 3, "blue order restored");
        check(restored.ecs().armyState(restoredRed) == 6, "red state restored");
        check(restored.ecs().armyTargetDimension(restoredBlue) == overworld,
                "blue target dimension restored");
        check(restored.ecs().armyTargetDimension(restoredRed) == nether,
                "red target dimension restored");
        check(restored.ecs().armyUnitCount(restoredBlue) == 1, "blue membership restored");
        check(restored.ecs().armyUnitCount(restoredRed) == 1, "red membership restored");
        check(countUnassigned(restored.ecs()) == 1, "unassigned unit restored");
        int restoredBlueUnit = unitForArmy(restored.ecs(), restoredBlue);
        PackedUnitRoleState.View roleView = restored.unitRoles().newView();
        check(restored.unitRoles().read(restoredBlueUnit, roleView), "blue role row restored");
        check(roleView.roleToken() == 10_001 && roleView.rankToken() == 20_001 && roleView.loadoutToken() == 30_001,
                "blue role payload restored");
        check(roleView.troopClass() == PackedUnitRoleState.TROOP_CLASS_REGULAR
                        && roleView.unpaidCycles() == 0,
                "blue regular class and paid state restored");
        check(!restored.unitRoles().isEquipmentDirty(restoredBlueUnit), "blue role dirty cleared persisted");
        int restoredRedUnit = unitForArmy(restored.ecs(), restoredRed);
        PackedUnitRoleState.View redRoleView = restored.unitRoles().newView();
        check(restored.unitRoles().read(restoredRedUnit, redRoleView), "red role row restored");
        check(redRoleView.roleToken() == 10_002 && redRoleView.rankToken() == 20_002 && redRoleView.loadoutToken() == 30_003,
                "red role payload restored");
        check(redRoleView.troopClass() == PackedUnitRoleState.TROOP_CLASS_NOBLE
                        && redRoleView.unpaidCycles() == 2,
                "red noble class and unpaid cycles restored");
        check(restored.unitRoles().isEquipmentDirty(restoredRedUnit), "red role dirtiness restored");
        check(restored.unitRoles().revision() == sourceRoleRevision, "unit role revision restored");
        int restoredSupplyRow = restored.armySupplies().findArmy(restoredBlue);
        check(restoredSupplyRow >= 0, "supply chest army handle remapped");
        check(restored.armySupplies().dimensionIdAt(restoredSupplyRow) == overworld
                        && restored.armySupplies().chestPositionAt(restoredSupplyRow)
                                == PackedArmyEcs.packBlockPos(12, 64, 13),
                "supply chest payload restored");
        check(restored.armySupplies().revision() == sourceSupplyRevision,
                "supply chest revision restored");
        PackedUnitMembership.UuidBits unitIdentity = restored.memberships().newUuidBits();
        check(restored.memberships().read(restoredBlueUnit, unitIdentity), "blue unit identity found");
        check(unitIdentity.most() == 0x1111L && unitIdentity.least() == 0x2222L,
                "blue MillVillager UUID restored");
        check(restored.controllers().matches(restoredBlue, 0xaaaaL, 0xbbbbL), "controller handle remapped");
        PackedGarrisonState.View garrisonView = restored.garrisons().newView();
        check(restored.garrisons().readArmy(restoredBlue, garrisonView), "garrison army handle remapped");
        check(garrisonView.villageMost() == 0x101L && garrisonView.villageLeast() == 0x201L,
                "garrison settlement identity restored");
        check(garrisonView.guardRadius() == 32
                        && garrisonView.supplyPercent() == 82
                        && garrisonView.readinessPercent() == 90,
                "garrison radius and coarse upkeep restored");

        PackedFactionState.Cursor factionCursor = restored.factions().newCursor();
        check(factionCursor.advance(), "faction relation cursor restored");
        check(factionCursor.sourceFactionId() == 20
                        && factionCursor.targetFactionId() == 30
                        && factionCursor.reputation() == -250,
                "faction relation contents restored");

        PackedLogisticsState.Cursor logisticsCursor = restored.logistics().newCursor();
        check(logisticsCursor.advance(), "logistics cursor restored");
        check(logisticsCursor.requesterArmyHandle() == restoredBlue, "logistics army handle remapped");
        check(logisticsCursor.itemKey() == ironKey && logisticsCursor.requiredAmount() == 64,
                "logistics item and amount restored");

        PackedCommandState.Cursor cursor = restored.commands().newCursor();
        int observed = 0;
        for (cursor.reset(); cursor.advance(); ) {
            if (cursor.orderId() == moveId) {
                check(cursor.armyHandle() == restoredBlue, "move command handle remapped");
                check(cursor.typeCode() == ArmyOrderType.MOVE.code(), "move command type restored");
                observed |= 1;
            } else if (cursor.orderId() == escortId) {
                check(cursor.armyHandle() == restoredRed, "escort command handle remapped");
                check(cursor.subjectUuidMost() == 0x7777L && cursor.subjectUuidLeast() == 0x8888L,
                        "escort subject UUID restored");
                check(cursor.flags() == 3, "escort flags restored");
                observed |= 2;
            }
        }
        check(observed == 3, "both commands restored");

        long nextId = restored.commands().add(
                PackedArmyEcs.NO_ARMY,
                overworld,
                ArmyOrderType.HOLD.code(),
                0,
                0L,
                0L,
                0L,
                0L,
                1_002L,
                (byte) 0);
        check(nextId == 3L, "next order id restored");

        CompoundTag encodedAgain = restored.save(new CompoundTag(), null);
        ArmySavedData secondRestore = ArmySavedData.load(encodedAgain, null);
        check(secondRestore.ecs().armySize() == 2, "second round-trip armies");
        check(secondRestore.ecs().unitSize() == 3, "second round-trip units");
        check(secondRestore.commands().size() == 3, "second round-trip commands");
        check(secondRestore.logistics().size() == 1, "second round-trip logistics");
        check(secondRestore.garrisons().size() == 1, "second round-trip garrison");
        int secondBlue = armyByFaction(secondRestore.ecs(), 20);
        PackedUnitRoleState.View secondRoleView = secondRestore.unitRoles().newView();
        check(secondBlue != PackedArmyEcs.NO_ARMY
                        && secondRestore.unitRoles().read(unitForArmy(secondRestore.ecs(), secondBlue), secondRoleView),
                "second round-trip role remap still resolvable");
        check(secondRestore.unitRoles().revision() == sourceRoleRevision, "second round-trip role revision parity");
        int secondSupplyRow = secondRestore.armySupplies().findArmy(secondBlue);
        check(secondSupplyRow >= 0
                        && secondRestore.armySupplies().revision() == sourceSupplyRevision,
                "second round-trip supply binding parity");
        check(secondRestore.settlementEconomy().deterministicHash()
                        == restored.settlementEconomy().deterministicHash(),
                "second round-trip settlement economy parity");
    }

    private static void corruptLengthsAndUnknownVersionsAreRejected() {
        ArmySavedData empty = new ArmySavedData();
        CompoundTag wrongVersion = empty.save(new CompoundTag(), null);
        wrongVersion.putInt("SchemaVersion", ArmyNbtCodec.SCHEMA_VERSION + 1);
        expectIllegalArgument(() -> ArmySavedData.load(wrongVersion, null), "future schema rejected");

        CompoundTag missingRoleRows = empty.save(new CompoundTag(), null);
        missingRoleRows.putInt("SchemaVersion", 4);
        CompoundTag roleTag = missingRoleRows.getCompound("UnitRoles");
        roleTag.putInt("Count", 1);
        roleTag.putIntArray("UnitRows", new int[]{0});
        roleTag.putIntArray("RoleTokens", new int[]{10});
        roleTag.putIntArray("RankTokens", new int[]{20});
        roleTag.putIntArray("LoadoutTokens", new int[]{30});
        roleTag.putByteArray("Flags", new byte[]{1});
        expectIllegalArgument(() -> ArmySavedData.load(missingRoleRows, null),
                "invalid persisted unit role row rejected");

        CompoundTag wrongLength = empty.save(new CompoundTag(), null);
        CompoundTag armies = wrongLength.getCompound("Armies");
        armies.putInt("Count", 1);
        expectIllegalArgument(() -> ArmySavedData.load(wrongLength, null), "column mismatch rejected");

        StableDimensionTable corruptDimensions = new StableDimensionTable();
        int corruptOverworld = corruptDimensions.intern(Level.OVERWORLD.location());
        PackedArmyEcs corruptEcs = new PackedArmyEcs(1, 1);
        int corruptArmy = corruptEcs.createArmy(
                1, 5, 0, corruptOverworld, PackedArmyEcs.packBlockPos(0, 64, 0));
        int corruptUnit = corruptEcs.createUnit(
                corruptArmy, 6, 7, PackedArmyEcs.packBlockPos(1, 64, 1));
        PackedUnitRoleState corruptRoles = new PackedUnitRoleState();
        corruptRoles.assign(corruptUnit, 11, 12, 13);
        PackedArmySupplyState corruptSupplies = new PackedArmySupplyState();
        corruptSupplies.assign(corruptArmy, corruptOverworld, PackedArmyEcs.packBlockPos(2, 64, 2));
        PackedGarrisonState corruptGarrisons = new PackedGarrisonState(1);
        corruptGarrisons.assign(
                corruptArmy, 1L, 2L, corruptOverworld,
                PackedArmyEcs.packBlockPos(0, 64, 0), 32, 100L);
        ArmySavedData corruptSource = new ArmySavedData(
                corruptDimensions,
                new StableItemTable(),
                new PackedFactionState(),
                corruptEcs,
                new PackedUnitMembership(),
                new PackedArmyControllers(),
                1L,
                new PackedCommandState(),
                new PackedLogisticsState(),
                new PackedSettlementEconomyState(),
                corruptGarrisons,
                corruptRoles,
                corruptSupplies);
        CompoundTag invalidGarrison = corruptSource.save(new CompoundTag(), null);
        invalidGarrison.getCompound("Garrisons").putIntArray("GuardRadii", new int[] {0});
        expectIllegalArgument(() -> ArmySavedData.load(invalidGarrison, null),
                "invalid persisted garrison radius rejected");
        CompoundTag invalidStatus = corruptSource.save(new CompoundTag(), null);
        invalidStatus.getCompound("Garrisons").putByteArray("Statuses", new byte[] {9});
        expectIllegalArgument(() -> ArmySavedData.load(invalidStatus, null),
                "invalid persisted garrison status rejected");
        CompoundTag duplicateRoleRows = corruptSource.save(new CompoundTag(), null);
        CompoundTag badRoleRows = duplicateRoleRows.getCompound("UnitRoles");
        badRoleRows.putInt("Count", 2);
        badRoleRows.putIntArray("RoleTokens", new int[] {1, 2});
        badRoleRows.putIntArray("RankTokens", new int[] {3, 4});
        badRoleRows.putIntArray("LoadoutTokens", new int[] {5, 6});
        badRoleRows.putIntArray("UnitRows", new int[] {0, 0});
        badRoleRows.putByteArray("Flags", new byte[] {1, 1});
        expectIllegalArgument(() -> ArmySavedData.load(duplicateRoleRows, null),
                "duplicate persisted unit role row rejected");
        CompoundTag invalidRoleFlags = corruptSource.save(new CompoundTag(), null);
        invalidRoleFlags.getCompound("UnitRoles").putByteArray("Flags", new byte[] {2});
        expectIllegalArgument(() -> ArmySavedData.load(invalidRoleFlags, null),
                "unknown persisted unit role flags rejected");
        CompoundTag invalidRoleLength = corruptSource.save(new CompoundTag(), null);
        invalidRoleLength.getCompound("UnitRoles").putIntArray("RankTokens", new int[0]);
        expectIllegalArgument(() -> ArmySavedData.load(invalidRoleLength, null),
                "persisted unit role column length mismatch rejected");
        CompoundTag invalidRoleRevision = corruptSource.save(new CompoundTag(), null);
        invalidRoleRevision.getCompound("UnitRoles").putLong("Revision", 0L);
        expectIllegalArgument(() -> ArmySavedData.load(invalidRoleRevision, null),
                "persisted unit role revision below row count rejected");
        CompoundTag invalidSupplyLength = corruptSource.save(new CompoundTag(), null);
        invalidSupplyLength.getCompound("ArmySupplies").putIntArray("Dimensions", new int[0]);
        expectIllegalArgument(() -> ArmySavedData.load(invalidSupplyLength, null),
                "persisted supply column length mismatch rejected");
        CompoundTag invalidSupplyRevision = corruptSource.save(new CompoundTag(), null);
        invalidSupplyRevision.getCompound("ArmySupplies").putLong("Revision", 0L);
        expectIllegalArgument(() -> ArmySavedData.load(invalidSupplyRevision, null),
                "persisted supply revision below row count rejected");
        CompoundTag duplicateSupply = corruptSource.save(new CompoundTag(), null);
        CompoundTag duplicateSupplyTag = duplicateSupply.getCompound("ArmySupplies");
        duplicateSupplyTag.putInt("Count", 2);
        duplicateSupplyTag.putIntArray("ArmyRows", new int[] {0, 0});
        duplicateSupplyTag.putIntArray("Dimensions", new int[] {0, 0});
        duplicateSupplyTag.putLongArray("ChestPositions", new long[] {1L, 2L});
        duplicateSupplyTag.putLong("Revision", 2L);
        expectIllegalArgument(() -> ArmySavedData.load(duplicateSupply, null),
                "duplicate persisted supply army row rejected");

        CompoundTag schemaFour = corruptSource.save(new CompoundTag(), null);
        schemaFour.putInt("SchemaVersion", 4);
        schemaFour.remove("ArmySupplies");
        ArmySavedData migratedFour = ArmySavedData.load(schemaFour, null);
        check(migratedFour.armySupplies().size() == 0,
                "schema-4 migrates to an empty army supply store");

        CompoundTag schemaThree = corruptSource.save(new CompoundTag(), null);
        schemaThree.putInt("SchemaVersion", 3);
        schemaThree.remove("UnitRoles");
        ArmySavedData migratedThree = ArmySavedData.load(schemaThree, null);
        check(migratedThree.unitRoles().size() == 0 && migratedThree.unitRoles().revision() == 0L,
                "schema-3 migrates to an empty unit role store");
        CompoundTag schemaTwo = corruptSource.save(new CompoundTag(), null);
        schemaTwo.putInt("SchemaVersion", 2);
        schemaTwo.remove("UnitRoles");
        ArmySavedData migratedTwo = ArmySavedData.load(schemaTwo, null);
        check(migratedTwo.unitRoles().size() == 0,
                "schema-2 remains supported and migrates to an empty unit role store");

        PackedArmyEcs legacyEcs = new PackedArmyEcs(1, 0);
        legacyEcs.createArmy(1, 1, 0, 0, PackedArmyEcs.packBlockPos(1, 64, 1));
        CompoundTag legacy = new ArmySavedData(legacyEcs, new PackedCommandState())
                .save(new CompoundTag(), null);
        legacy.putInt("SchemaVersion", 1);
        legacy.getCompound("Armies").remove("TargetDimensions");
        ArmySavedData migrated = ArmySavedData.load(legacy, null);
        int migratedArmy = armyByFaction(migrated.ecs(), 1);
        check(migrated.ecs().armyTargetDimension(migratedArmy) == PackedArmyEcs.UNKNOWN_DIMENSION,
                "schema-1 target dimension migrates fail-closed");
    }

    private static void signedGenerationHandlesRoundTrip() {
        PackedArmyEcs ecs = new PackedArmyEcs(1, 0);
        int army = ecs.createArmy(1, 2, 3, 4L);
        for (int reuse = 0; reuse < 2_047; reuse++) {
            check(ecs.removeArmy(army), "churned army removed");
            army = ecs.createArmy(1, 2, 3, 4L);
        }
        check(army < 0, "high-generation ECS handle uses the signed int range");

        PackedCommandState commands = new PackedCommandState(1);
        commands.add(army, 1, ArmyOrderType.HOLD.code(), 0, 0L, 0L, 0L, 0L, 5L, (byte) 0);
        ArmySavedData restored = ArmySavedData.load(new ArmySavedData(ecs, commands).save(new CompoundTag(), null), null);
        PackedCommandState.Cursor cursor = restored.commands().newCursor();
        check(cursor.advance(), "signed-handle command restored");
        check(restored.ecs().isArmyAlive(cursor.armyHandle()), "signed handle remapped to live army");
    }

    private static int armyByFaction(PackedArmyEcs ecs, int faction) {
        PackedArmyEcs.ArmyCursor cursor = ecs.newArmyCursor();
        for (cursor.reset(); cursor.advance(); ) {
            if (cursor.faction() == faction) {
                return cursor.handle();
            }
        }
        return PackedArmyEcs.NO_ARMY;
    }

    private static int countUnassigned(PackedArmyEcs ecs) {
        int count = 0;
        PackedArmyEcs.UnitCursor cursor = ecs.newUnitCursor();
        for (cursor.reset(); cursor.advance(); ) {
            if (cursor.army() == PackedArmyEcs.NO_ARMY) {
                count++;
            }
        }
        return count;
    }

    private static int unitForArmy(PackedArmyEcs ecs, int armyHandle) {
        PackedArmyEcs.UnitCursor cursor = ecs.newUnitCursor();
        for (cursor.reset(); cursor.advance(); ) {
            if (cursor.army() == armyHandle) {
                return cursor.handle();
            }
        }
        return 0;
    }

    private static CompoundTag binaryRoundTrip(CompoundTag source) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.write(source, new DataOutputStream(bytes));
            return NbtIo.read(
                    new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),
                    NbtAccounter.unlimitedHeap());
        } catch (IOException exception) {
            throw new AssertionError("NBT binary round-trip failed", exception);
        }
    }

    private static void expectIllegalArgument(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError(message);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
