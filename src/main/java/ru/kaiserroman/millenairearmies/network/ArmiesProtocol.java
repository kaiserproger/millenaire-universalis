package ru.kaiserroman.millenairearmies.network;

/**
 * Wire-level constants shared by the client mirror and the authoritative server.
 *
 * <p>The wire format deliberately contains only bounded primitive columns. Runtime handles are
 * opaque 32-bit values: their sign has no meaning and must never be used as validation.
 */
public final class ArmiesProtocol {
    public static final String VERSION = "2";

    public static final byte SECTION_FACTIONS = 1;
    public static final byte SECTION_ARMIES = 1 << 1;
    public static final byte SECTION_UNITS = 1 << 2;
    public static final byte SECTION_RELATIONS = 1 << 3;
    public static final byte SECTION_LOGISTICS = 1 << 4;
    public static final byte SECTION_ORDERS = 1 << 5;
    public static final byte SECTION_ALL = SECTION_FACTIONS
            | SECTION_ARMIES
            | SECTION_UNITS
            | SECTION_RELATIONS
            | SECTION_LOGISTICS
            | SECTION_ORDERS;

    public static final byte KIND_FACTION = 0;
    public static final byte KIND_ARMY = 1;
    public static final byte KIND_UNIT = 2;
    public static final byte KIND_RELATION = 3;
    public static final byte KIND_LOGISTICS = 4;
    public static final byte KIND_ORDER = 5;
    public static final int KIND_COUNT = 6;

    public static final byte DELTA_REMOVE = 0;
    public static final byte DELTA_UPSERT = 1;

    public static final byte VIEW_STRATEGIC = 0;
    public static final byte VIEW_FACTION = 1;
    public static final byte VIEW_ARMY = 2;
    public static final byte VIEW_LOGISTICS = 3;

    public static final byte SCOPE_GLOBAL = 0;
    public static final byte SCOPE_FACTION = 1;
    public static final byte SCOPE_ARMY = 2;

    public static final int INT_COLUMNS = 8;
    public static final int LONG_COLUMNS = 2;
    public static final int BYTE_COLUMNS = 2;

    public static final int COLUMN_HANDLE = 0;
    public static final int COLUMN_GENERATION = 1;
    public static final int COLUMN_OWNER = 2;
    public static final int COLUMN_PRIMARY_KEY = 3;
    public static final int COLUMN_SECONDARY_KEY = 4;
    public static final int COLUMN_VALUE_0 = 5;
    public static final int COLUMN_VALUE_1 = 6;
    public static final int COLUMN_VALUE_2 = 7;

    public static final int MAX_FACTIONS_PER_SNAPSHOT = 512;
    public static final int MAX_ARMIES_PER_SNAPSHOT = 1_024;
    public static final int MAX_UNITS_PER_SNAPSHOT = 4_096;
    public static final int MAX_RELATIONS_PER_SNAPSHOT = 2_048;
    public static final int MAX_LOGISTICS_PER_SNAPSHOT = 2_048;
    public static final int MAX_ORDERS_PER_SNAPSHOT = 2_048;
    public static final int MAX_DELTA_ROWS = 2_048;
    public static final int MAX_REQUEST_CURSOR = 16_777_215;
    public static final int MAX_CREATE_UNITS = 1_024;
    public static final int MAX_CONTROLLED_SETTLEMENTS = 256;
    public static final int MAX_AVAILABLE_RECRUITS = 2_048;
    public static final int MAX_RECRUITS_PER_INTENT = 64;

    public static final byte ACTION_NONE = 0;
    public static final byte ACTION_CREATE_ARMY = 1;
    public static final byte ACTION_RECRUIT = 2;
    public static final byte ACTION_ISSUE_ORDER = 3;

    public static final int RESULT_NONE = 0;
    public static final int RESULT_ACCEPTED = 1;
    public static final int RESULT_STALE = 2;
    public static final int RESULT_PERMISSION_DENIED = 3;
    public static final int RESULT_NOT_FOUND = 4;
    public static final int RESULT_INVALID = 5;
    public static final int RESULT_LIMIT_REACHED = 6;
    public static final int RESULT_PARTIAL = 7;

    public static final byte EXECUTION_ACCEPTED = 0;
    public static final byte EXECUTION_EXECUTING = 1;
    public static final byte EXECUTION_ARRIVED = 2;
    public static final byte EXECUTION_BLOCKED = 3;

    public static final byte ORDER_FLAG_SECONDARY_POSITION = 1;
    public static final byte ORDER_FLAG_SUBJECT_ENTITY = 1 << 1;
    public static final byte ORDER_FLAGS = ORDER_FLAG_SECONDARY_POSITION | ORDER_FLAG_SUBJECT_ENTITY;
    public static final byte ORDER_HOLD = 0;
    public static final byte ORDER_MOVE = 1;
    public static final byte ORDER_RALLY = 2;
    public static final byte ORDER_LOGISTICS = 3;

    private ArmiesProtocol() {}

    public static boolean validSectionMask(byte mask) {
        int unsigned = Byte.toUnsignedInt(mask);
        return unsigned != 0 && (unsigned & ~Byte.toUnsignedInt(SECTION_ALL)) == 0;
    }

    public static byte sectionForKind(byte kind) {
        return switch (kind) {
            case KIND_FACTION -> SECTION_FACTIONS;
            case KIND_ARMY -> SECTION_ARMIES;
            case KIND_UNIT -> SECTION_UNITS;
            case KIND_RELATION -> SECTION_RELATIONS;
            case KIND_LOGISTICS -> SECTION_LOGISTICS;
            case KIND_ORDER -> SECTION_ORDERS;
            default -> throw new IllegalArgumentException("Unknown state row kind: " + kind);
        };
    }

    public static boolean validKind(byte kind) {
        return kind >= 0 && kind < KIND_COUNT;
    }

    public static boolean validStrategicOrder(byte order) {
        return order >= ORDER_HOLD && order <= ORDER_LOGISTICS;
    }
}
