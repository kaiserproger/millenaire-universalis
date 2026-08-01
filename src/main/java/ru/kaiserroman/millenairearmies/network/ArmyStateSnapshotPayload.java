package ru.kaiserroman.millenairearmies.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import ru.kaiserroman.millenairearmies.SarvarMillenaireArmies;

/**
 * Compact server projection used by strategic screens.
 *
 * <p>Rows are ordered factions, armies, units, relations, logistics, orders. Every row has eight raw
 * VarInt columns, two fixed longs and two bytes. Column zero is always an opaque handle/id and
 * column one is its generation/data revision. Remaining columns are interpreted by row kind.
 * This uniform shape lets the client retain primitive SoA storage and apply deltas without object
 * graphs. Snapshots are scoped and pageable; they are not world/NBT dumps.
 */
public record ArmyStateSnapshotPayload(
        long revision,
        int playerFactionId,
        byte sectionMask,
        int factionCount,
        int armyCount,
        int unitCount,
        int relationCount,
        int logisticsCount,
        int orderCount,
        int[] intColumns,
        long[] longColumns,
        byte[] byteColumns)
        implements CustomPacketPayload {
    public static final Type<ArmyStateSnapshotPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
            SarvarMillenaireArmies.MOD_ID, "state_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ArmyStateSnapshotPayload> STREAM_CODEC = StreamCodec.of(
            ArmyStateSnapshotPayload::encode, ArmyStateSnapshotPayload::decode);

    public ArmyStateSnapshotPayload {
        if (revision < 0) {
            throw new IllegalArgumentException("Snapshot revision must be non-negative");
        }
        if (playerFactionId < -1) {
            throw new IllegalArgumentException("Player faction id must be -1 or non-negative");
        }
        if (!ArmiesProtocol.validSectionMask(sectionMask)) {
            throw new IllegalArgumentException("Invalid snapshot section mask: " + sectionMask);
        }
        checkCount(sectionMask, ArmiesProtocol.SECTION_FACTIONS, factionCount,
                ArmiesProtocol.MAX_FACTIONS_PER_SNAPSHOT, "factions");
        checkCount(sectionMask, ArmiesProtocol.SECTION_ARMIES, armyCount,
                ArmiesProtocol.MAX_ARMIES_PER_SNAPSHOT, "armies");
        checkCount(sectionMask, ArmiesProtocol.SECTION_UNITS, unitCount,
                ArmiesProtocol.MAX_UNITS_PER_SNAPSHOT, "units");
        checkCount(sectionMask, ArmiesProtocol.SECTION_RELATIONS, relationCount,
                ArmiesProtocol.MAX_RELATIONS_PER_SNAPSHOT, "relations");
        checkCount(sectionMask, ArmiesProtocol.SECTION_LOGISTICS, logisticsCount,
                ArmiesProtocol.MAX_LOGISTICS_PER_SNAPSHOT, "logistics");
        checkCount(sectionMask, ArmiesProtocol.SECTION_ORDERS, orderCount,
                ArmiesProtocol.MAX_ORDERS_PER_SNAPSHOT, "orders");
        int rows = checkedRows(factionCount, armyCount, unitCount, relationCount, logisticsCount, orderCount);
        checkColumns(intColumns, longColumns, byteColumns, rows);
    }

    public int rowCount() {
        return factionCount + armyCount + unitCount + relationCount + logisticsCount + orderCount;
    }

    public int rowOffset(byte kind) {
        return switch (kind) {
            case ArmiesProtocol.KIND_FACTION -> 0;
            case ArmiesProtocol.KIND_ARMY -> factionCount;
            case ArmiesProtocol.KIND_UNIT -> factionCount + armyCount;
            case ArmiesProtocol.KIND_RELATION -> factionCount + armyCount + unitCount;
            case ArmiesProtocol.KIND_LOGISTICS -> factionCount + armyCount + unitCount + relationCount;
            case ArmiesProtocol.KIND_ORDER -> factionCount + armyCount + unitCount + relationCount + logisticsCount;
            default -> throw new IllegalArgumentException("Unknown row kind: " + kind);
        };
    }

    public int count(byte kind) {
        return switch (kind) {
            case ArmiesProtocol.KIND_FACTION -> factionCount;
            case ArmiesProtocol.KIND_ARMY -> armyCount;
            case ArmiesProtocol.KIND_UNIT -> unitCount;
            case ArmiesProtocol.KIND_RELATION -> relationCount;
            case ArmiesProtocol.KIND_LOGISTICS -> logisticsCount;
            case ArmiesProtocol.KIND_ORDER -> orderCount;
            default -> throw new IllegalArgumentException("Unknown row kind: " + kind);
        };
    }

    private static void encode(RegistryFriendlyByteBuf buffer, ArmyStateSnapshotPayload payload) {
        BoundedCodecs.writeRevision(buffer, payload.revision, "revision");
        BoundedCodecs.writeSignedVarInt(buffer, payload.playerFactionId);
        buffer.writeByte(payload.sectionMask);
        writeCountIfPresent(buffer, payload.sectionMask, ArmiesProtocol.SECTION_FACTIONS,
                payload.factionCount, ArmiesProtocol.MAX_FACTIONS_PER_SNAPSHOT, "factions");
        writeCountIfPresent(buffer, payload.sectionMask, ArmiesProtocol.SECTION_ARMIES,
                payload.armyCount, ArmiesProtocol.MAX_ARMIES_PER_SNAPSHOT, "armies");
        writeCountIfPresent(buffer, payload.sectionMask, ArmiesProtocol.SECTION_UNITS,
                payload.unitCount, ArmiesProtocol.MAX_UNITS_PER_SNAPSHOT, "units");
        writeCountIfPresent(buffer, payload.sectionMask, ArmiesProtocol.SECTION_RELATIONS,
                payload.relationCount, ArmiesProtocol.MAX_RELATIONS_PER_SNAPSHOT, "relations");
        writeCountIfPresent(buffer, payload.sectionMask, ArmiesProtocol.SECTION_LOGISTICS,
                payload.logisticsCount, ArmiesProtocol.MAX_LOGISTICS_PER_SNAPSHOT, "logistics");
        writeCountIfPresent(buffer, payload.sectionMask, ArmiesProtocol.SECTION_ORDERS,
                payload.orderCount, ArmiesProtocol.MAX_ORDERS_PER_SNAPSHOT, "orders");
        BoundedCodecs.writeRawVarInts(buffer, payload.intColumns);
        BoundedCodecs.writeLongs(buffer, payload.longColumns);
        BoundedCodecs.writeBytes(buffer, payload.byteColumns);
    }

    private static ArmyStateSnapshotPayload decode(RegistryFriendlyByteBuf buffer) {
        long revision = BoundedCodecs.readRevision(buffer, "revision");
        int playerFactionId = BoundedCodecs.readSignedVarInt(buffer);
        byte sections = buffer.readByte();
        if (!ArmiesProtocol.validSectionMask(sections)) {
            throw new IllegalArgumentException("Invalid snapshot section mask: " + sections);
        }
        int factions = readCountIfPresent(buffer, sections, ArmiesProtocol.SECTION_FACTIONS,
                ArmiesProtocol.MAX_FACTIONS_PER_SNAPSHOT, "factions");
        int armies = readCountIfPresent(buffer, sections, ArmiesProtocol.SECTION_ARMIES,
                ArmiesProtocol.MAX_ARMIES_PER_SNAPSHOT, "armies");
        int units = readCountIfPresent(buffer, sections, ArmiesProtocol.SECTION_UNITS,
                ArmiesProtocol.MAX_UNITS_PER_SNAPSHOT, "units");
        int relations = readCountIfPresent(buffer, sections, ArmiesProtocol.SECTION_RELATIONS,
                ArmiesProtocol.MAX_RELATIONS_PER_SNAPSHOT, "relations");
        int logistics = readCountIfPresent(buffer, sections, ArmiesProtocol.SECTION_LOGISTICS,
                ArmiesProtocol.MAX_LOGISTICS_PER_SNAPSHOT, "logistics");
        int orders = readCountIfPresent(buffer, sections, ArmiesProtocol.SECTION_ORDERS,
                ArmiesProtocol.MAX_ORDERS_PER_SNAPSHOT, "orders");
        int rows = checkedRows(factions, armies, units, relations, logistics, orders);
        return new ArmyStateSnapshotPayload(
                revision,
                playerFactionId,
                sections,
                factions,
                armies,
                units,
                relations,
                logistics,
                orders,
                BoundedCodecs.readRawVarInts(buffer, rows, ArmiesProtocol.INT_COLUMNS),
                BoundedCodecs.readLongs(buffer, rows, ArmiesProtocol.LONG_COLUMNS),
                BoundedCodecs.readBytes(buffer, rows, ArmiesProtocol.BYTE_COLUMNS));
    }

    private static int readCountIfPresent(
            RegistryFriendlyByteBuf buffer, byte mask, byte section, int maximum, String field) {
        return (mask & section) != 0 ? BoundedCodecs.readCount(buffer, maximum, field) : 0;
    }

    private static void writeCountIfPresent(
            RegistryFriendlyByteBuf buffer, byte mask, byte section, int count, int maximum, String field) {
        if ((mask & section) != 0) {
            BoundedCodecs.writeCount(buffer, count, maximum, field);
        }
    }

    private static void checkCount(byte mask, byte section, int count, int maximum, String field) {
        if (count < 0 || count > maximum || ((mask & section) == 0 && count != 0)) {
            throw new IllegalArgumentException("Invalid " + field + " row count: " + count);
        }
    }

    private static int checkedRows(
            int factions, int armies, int units, int relations, int logistics, int orders) {
        try {
            return Math.addExact(
                    Math.addExact(
                            Math.addExact(Math.addExact(factions, armies), Math.addExact(units, relations)),
                            logistics),
                    orders);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Snapshot row count overflow", exception);
        }
    }

    private static void checkColumns(int[] ints, long[] longs, byte[] bytes, int rows) {
        if (ints == null || longs == null || bytes == null) {
            throw new IllegalArgumentException("Snapshot primitive columns must not be null");
        }
        if (ints.length != BoundedCodecs.checkedLength(rows, ArmiesProtocol.INT_COLUMNS)
                || longs.length != BoundedCodecs.checkedLength(rows, ArmiesProtocol.LONG_COLUMNS)
                || bytes.length != BoundedCodecs.checkedLength(rows, ArmiesProtocol.BYTE_COLUMNS)) {
            throw new IllegalArgumentException("Snapshot primitive column lengths do not match row count");
        }
    }

    @Override
    public Type<ArmyStateSnapshotPayload> type() {
        return TYPE;
    }
}
