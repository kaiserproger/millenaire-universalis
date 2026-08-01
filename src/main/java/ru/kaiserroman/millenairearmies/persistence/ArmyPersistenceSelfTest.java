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

        ArmySavedData source = new ArmySavedData(
                dimensions, items, factions, sourceEcs, memberships, controllers, 7L, sourceCommands, logistics);
        CompoundTag encoded = source.save(new CompoundTag(), null);
        check(encoded.getInt("SchemaVersion") == ArmyNbtCodec.SCHEMA_VERSION, "schema written");
        check(encoded.getCompound("Armies").getInt("Count") == 2, "army count written");
        check(encoded.getCompound("Units").getInt("Count") == 3, "unit count written");
        check(encoded.getCompound("Commands").getInt("Count") == 2, "command count written");

        ArmySavedData restored = ArmySavedData.load(binaryRoundTrip(encoded), null);
        check(restored.ecs().armySize() == 2, "army count restored");
        check(restored.ecs().unitSize() == 3, "unit count restored");
        check(restored.commands().size() == 2, "command count restored");
        check(restored.factions().size() == 1, "faction relation restored");
        check(restored.memberships().size() == 2, "unit memberships restored");
        check(restored.controllers().size() == 1, "controller restored");
        check(restored.logistics().size() == 1, "logistics restored");
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
        PackedUnitMembership.UuidBits unitIdentity = restored.memberships().newUuidBits();
        check(restored.memberships().read(restoredBlueUnit, unitIdentity), "blue unit identity found");
        check(unitIdentity.most() == 0x1111L && unitIdentity.least() == 0x2222L,
                "blue MillVillager UUID restored");
        check(restored.controllers().matches(restoredBlue, 0xaaaaL, 0xbbbbL), "controller handle remapped");

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
    }

    private static void corruptLengthsAndUnknownVersionsAreRejected() {
        ArmySavedData empty = new ArmySavedData();
        CompoundTag wrongVersion = empty.save(new CompoundTag(), null);
        wrongVersion.putInt("SchemaVersion", ArmyNbtCodec.SCHEMA_VERSION + 1);
        expectIllegalArgument(() -> ArmySavedData.load(wrongVersion, null), "future schema rejected");

        CompoundTag wrongLength = empty.save(new CompoundTag(), null);
        CompoundTag armies = wrongLength.getCompound("Armies");
        armies.putInt("Count", 1);
        expectIllegalArgument(() -> ArmySavedData.load(wrongLength, null), "column mismatch rejected");

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
