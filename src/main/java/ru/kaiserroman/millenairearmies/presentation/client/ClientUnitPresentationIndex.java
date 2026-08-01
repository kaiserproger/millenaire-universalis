package ru.kaiserroman.millenairearmies.presentation.client;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Client-thread-confined, allocation-free-on-read unit lookup by UUID or primitive mirror id.
 * Keys live in packed primitive columns; the index retains no Entity, Level, capability, UUID
 * object, or callback reference.
 */
public final class ClientUnitPresentationIndex {
    private static final int EMPTY = 0;
    private static final int DELETED = -1;
    private static final int MIN_CAPACITY = 16;
    private static final int LOAD_NUMERATOR = 3;
    private static final int LOAD_DENOMINATOR = 5;

    private long[] uuidMost = new long[0];
    private long[] uuidLeast = new long[0];
    private int[] mirrorIds = new int[0];
    private ClientUnitPresentation[] presentations = new ClientUnitPresentation[0];
    private int size;

    /** Hash-table values are dense-row + 1; zero is empty and -1 is a tombstone. */
    private int[] uuidRows = new int[0];
    private int[] mirrorRows = new int[0];
    private int uuidOccupied;
    private int mirrorOccupied;
    private int resizeAt;

    public ClientUnitPresentationIndex() {
        rehashTables(MIN_CAPACITY);
    }

    public int size() {
        return size;
    }

    /** Preallocates dense and hash columns; render-time reads never grow or allocate. */
    public void reserve(int expectedEntries) {
        if (expectedEntries < 0) {
            throw new IllegalArgumentException("expectedEntries must be non-negative");
        }
        ensureDenseCapacity(expectedEntries);
        int tableCapacity = MIN_CAPACITY;
        while (threshold(tableCapacity) < expectedEntries) {
            tableCapacity = Math.multiplyExact(tableCapacity, 2);
        }
        if (tableCapacity > uuidRows.length) {
            rehashTables(tableCapacity);
        }
    }

    public ClientUnitPresentation get(UUID villagerId) {
        Objects.requireNonNull(villagerId, "villagerId");
        return get(villagerId.getMostSignificantBits(), villagerId.getLeastSignificantBits());
    }

    public ClientUnitPresentation get(long uuidMostBits, long uuidLeastBits) {
        int row = findUuidRow(uuidMostBits, uuidLeastBits);
        return row < 0 ? null : presentations[row];
    }

    public ClientUnitPresentation getByMirrorId(int mirrorId) {
        if (mirrorId < 0) {
            return null;
        }
        int row = findMirrorRow(mirrorId);
        return row < 0 ? null : presentations[row];
    }

    public void put(UUID villagerId, int mirrorId, ClientUnitPresentation presentation) {
        Objects.requireNonNull(villagerId, "villagerId");
        put(villagerId.getMostSignificantBits(), villagerId.getLeastSignificantBits(), mirrorId, presentation);
    }

    /**
     * Adds or replaces the one-to-one UUID/mirror-id mapping. Existing rows that own either key
     * are removed, so stale update ordering cannot leave two aliases for one mirror row.
     */
    public void put(
            long uuidMostBits,
            long uuidLeastBits,
            int mirrorId,
            ClientUnitPresentation presentation) {
        if (mirrorId < 0) {
            throw new IllegalArgumentException("mirrorId must be non-negative");
        }
        Objects.requireNonNull(presentation, "presentation");

        int uuidRow = findUuidRow(uuidMostBits, uuidLeastBits);
        int mirrorRow = findMirrorRow(mirrorId);
        if (uuidRow >= 0) {
            if (mirrorRow >= 0 && mirrorRow != uuidRow) {
                removeDense(mirrorRow);
                // swap-remove may have moved the UUID row
                uuidRow = findUuidRow(uuidMostBits, uuidLeastBits);
            }
            if (mirrorIds[uuidRow] != mirrorId) {
                deleteMirrorMapping(mirrorIds[uuidRow], uuidRow);
                mirrorIds[uuidRow] = mirrorId;
                insertMirrorMapping(uuidRow);
            }
            presentations[uuidRow] = presentation;
            return;
        }

        if (mirrorRow >= 0) {
            removeDense(mirrorRow);
        }
        ensureInsertCapacity();
        ensureDenseCapacity(size + 1);
        int row = size++;
        uuidMost[row] = uuidMostBits;
        uuidLeast[row] = uuidLeastBits;
        mirrorIds[row] = mirrorId;
        presentations[row] = presentation;
        insertUuidMapping(row);
        insertMirrorMapping(row);
    }

    public boolean remove(UUID villagerId) {
        Objects.requireNonNull(villagerId, "villagerId");
        return remove(villagerId.getMostSignificantBits(), villagerId.getLeastSignificantBits());
    }

    public boolean remove(long uuidMostBits, long uuidLeastBits) {
        int row = findUuidRow(uuidMostBits, uuidLeastBits);
        if (row < 0) {
            return false;
        }
        removeDense(row);
        return true;
    }

    public boolean removeByMirrorId(int mirrorId) {
        int row = findMirrorRow(mirrorId);
        if (row < 0) {
            return false;
        }
        removeDense(row);
        return true;
    }

    public void clear() {
        if (size == 0 && uuidOccupied == 0 && mirrorOccupied == 0) {
            return;
        }
        Arrays.fill(presentations, 0, size, null);
        Arrays.fill(uuidRows, EMPTY);
        Arrays.fill(mirrorRows, EMPTY);
        size = 0;
        uuidOccupied = 0;
        mirrorOccupied = 0;
    }

    /** Rebuilds cached Components after a resource reload; never retains the old catalog. */
    public void refreshDefinitions(ClientPresentationCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        for (int row = 0; row < size; row++) {
            presentations[row] = presentations[row].resolveAgain(catalog);
        }
    }

    void checkInvariants() {
        int uuidMappings = 0;
        int mirrorMappings = 0;
        for (int slot = 0; slot < uuidRows.length; slot++) {
            int encoded = uuidRows[slot];
            if (encoded > 0) {
                if (encoded > size) {
                    throw new IllegalStateException("UUID mapping points outside dense rows");
                }
                uuidMappings++;
            }
        }
        for (int slot = 0; slot < mirrorRows.length; slot++) {
            int encoded = mirrorRows[slot];
            if (encoded > 0) {
                if (encoded > size) {
                    throw new IllegalStateException("Mirror mapping points outside dense rows");
                }
                mirrorMappings++;
            }
        }
        if (uuidMappings != size || mirrorMappings != size) {
            throw new IllegalStateException("Presentation index mapping count differs from dense size");
        }
        for (int row = 0; row < size; row++) {
            if (presentations[row] == null
                    || findUuidRow(uuidMost[row], uuidLeast[row]) != row
                    || findMirrorRow(mirrorIds[row]) != row) {
                throw new IllegalStateException("Presentation index row is not reachable by both keys");
            }
        }
    }

    private void removeDense(int row) {
        deleteUuidMapping(uuidMost[row], uuidLeast[row], row);
        deleteMirrorMapping(mirrorIds[row], row);

        int last = --size;
        if (row != last) {
            uuidMost[row] = uuidMost[last];
            uuidLeast[row] = uuidLeast[last];
            mirrorIds[row] = mirrorIds[last];
            presentations[row] = presentations[last];

            int uuidSlot = findUuidSlot(uuidMost[row], uuidLeast[row]);
            int mirrorSlot = findMirrorSlot(mirrorIds[row]);
            if (uuidSlot < 0 || mirrorSlot < 0) {
                throw new IllegalStateException("Presentation index lost a moved row");
            }
            uuidRows[uuidSlot] = row + 1;
            mirrorRows[mirrorSlot] = row + 1;
        }
        presentations[last] = null;
    }

    private void ensureInsertCapacity() {
        if (uuidOccupied + 1 >= resizeAt || mirrorOccupied + 1 >= resizeAt) {
            // Churn leaves tombstones behind. Clean those in-place instead of growing a mostly
            // empty client table forever as units enter and leave tracking range.
            rehashTables(size + 1 >= resizeAt ? uuidRows.length << 1 : uuidRows.length);
        }
    }

    private void ensureDenseCapacity(int required) {
        if (required <= uuidMost.length) {
            return;
        }
        int capacity = Math.max(MIN_CAPACITY, uuidMost.length);
        while (capacity < required) {
            capacity = Math.multiplyExact(capacity, 2);
        }
        uuidMost = Arrays.copyOf(uuidMost, capacity);
        uuidLeast = Arrays.copyOf(uuidLeast, capacity);
        mirrorIds = Arrays.copyOf(mirrorIds, capacity);
        presentations = Arrays.copyOf(presentations, capacity);
    }

    private void rehashTables(int requestedCapacity) {
        int capacity = tableSize(requestedCapacity);
        uuidRows = new int[capacity];
        mirrorRows = new int[capacity];
        uuidOccupied = 0;
        mirrorOccupied = 0;
        resizeAt = threshold(capacity);
        for (int row = 0; row < size; row++) {
            insertUuidMapping(row);
            insertMirrorMapping(row);
        }
    }

    private int findUuidRow(long most, long least) {
        int slot = findUuidSlot(most, least);
        return slot < 0 ? -1 : uuidRows[slot] - 1;
    }

    private int findMirrorRow(int mirrorId) {
        int slot = findMirrorSlot(mirrorId);
        return slot < 0 ? -1 : mirrorRows[slot] - 1;
    }

    private int findUuidSlot(long most, long least) {
        int mask = uuidRows.length - 1;
        int slot = mixUuid(most, least) & mask;
        while (uuidRows[slot] != EMPTY) {
            int encodedRow = uuidRows[slot];
            if (encodedRow > 0) {
                int row = encodedRow - 1;
                if (uuidMost[row] == most && uuidLeast[row] == least) {
                    return slot;
                }
            }
            slot = (slot + 1) & mask;
        }
        return -1;
    }

    private int findMirrorSlot(int mirrorId) {
        int mask = mirrorRows.length - 1;
        int slot = mixInt(mirrorId) & mask;
        while (mirrorRows[slot] != EMPTY) {
            int encodedRow = mirrorRows[slot];
            if (encodedRow > 0 && mirrorIds[encodedRow - 1] == mirrorId) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }
        return -1;
    }

    private void insertUuidMapping(int row) {
        int mask = uuidRows.length - 1;
        int slot = mixUuid(uuidMost[row], uuidLeast[row]) & mask;
        int deleted = -1;
        while (uuidRows[slot] != EMPTY) {
            if (uuidRows[slot] == DELETED && deleted < 0) {
                deleted = slot;
            }
            slot = (slot + 1) & mask;
        }
        if (deleted >= 0) {
            slot = deleted;
        } else {
            uuidOccupied++;
        }
        uuidRows[slot] = row + 1;
    }

    private void insertMirrorMapping(int row) {
        int mask = mirrorRows.length - 1;
        int slot = mixInt(mirrorIds[row]) & mask;
        int deleted = -1;
        while (mirrorRows[slot] != EMPTY) {
            if (mirrorRows[slot] == DELETED && deleted < 0) {
                deleted = slot;
            }
            slot = (slot + 1) & mask;
        }
        if (deleted >= 0) {
            slot = deleted;
        } else {
            mirrorOccupied++;
        }
        mirrorRows[slot] = row + 1;
    }

    private void deleteUuidMapping(long most, long least, int expectedRow) {
        int slot = findUuidSlot(most, least);
        if (slot >= 0 && uuidRows[slot] == expectedRow + 1) {
            uuidRows[slot] = DELETED;
        }
    }

    private void deleteMirrorMapping(int mirrorId, int expectedRow) {
        int slot = findMirrorSlot(mirrorId);
        if (slot >= 0 && mirrorRows[slot] == expectedRow + 1) {
            mirrorRows[slot] = DELETED;
        }
    }

    private static int tableSize(int requested) {
        int capacity = MIN_CAPACITY;
        while (capacity < requested) {
            capacity = Math.multiplyExact(capacity, 2);
        }
        return capacity;
    }

    private static int threshold(int capacity) {
        return capacity * LOAD_NUMERATOR / LOAD_DENOMINATOR;
    }

    private static int mixUuid(long most, long least) {
        long value = most ^ Long.rotateLeft(least, 29);
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        value *= 0xc4ceb9fe1a85ec53l;
        value ^= value >>> 33;
        return (int) value;
    }

    private static int mixInt(int value) {
        value ^= value >>> 16;
        value *= 0x7feb352d;
        value ^= value >>> 15;
        value *= 0x846ca68b;
        return value ^ value >>> 16;
    }
}
