package ru.kaiserroman.millenairearmies.client.state;

import java.util.Arrays;
import ru.kaiserroman.millenairearmies.network.ArmiesProtocol;

/**
 * Reusable primitive client table. Growth is allowed only while applying network state; reads and
 * render ticks do not allocate.
 */
public final class PackedMirrorTable {
    private int size;
    private int[] intColumns = new int[0];
    private long[] longColumns = new long[0];
    private byte[] byteColumns = new byte[0];
    private final IntRowIndex index = new IntRowIndex();

    public int size() {
        return size;
    }

    public int findRow(int opaqueHandle) {
        return index.get(opaqueHandle);
    }

    public int intValue(int row, int column) {
        checkRow(row);
        if (column < 0 || column >= ArmiesProtocol.INT_COLUMNS) {
            throw new IndexOutOfBoundsException("Integer column: " + column);
        }
        return intColumns[row * ArmiesProtocol.INT_COLUMNS + column];
    }

    public long longValue(int row, int column) {
        checkRow(row);
        if (column < 0 || column >= ArmiesProtocol.LONG_COLUMNS) {
            throw new IndexOutOfBoundsException("Long column: " + column);
        }
        return longColumns[row * ArmiesProtocol.LONG_COLUMNS + column];
    }

    public byte byteValue(int row, int column) {
        checkRow(row);
        if (column < 0 || column >= ArmiesProtocol.BYTE_COLUMNS) {
            throw new IndexOutOfBoundsException("Byte column: " + column);
        }
        return byteColumns[row * ArmiesProtocol.BYTE_COLUMNS + column];
    }

    public int handle(int row) {
        return intValue(row, ArmiesProtocol.COLUMN_HANDLE);
    }

    public long unsignedHandle(int row) {
        return Integer.toUnsignedLong(handle(row));
    }

    void replace(
            int[] sourceInts,
            long[] sourceLongs,
            byte[] sourceBytes,
            int sourceRow,
            int rows) {
        ensureCapacity(rows);
        System.arraycopy(
                sourceInts,
                sourceRow * ArmiesProtocol.INT_COLUMNS,
                intColumns,
                0,
                rows * ArmiesProtocol.INT_COLUMNS);
        System.arraycopy(
                sourceLongs,
                sourceRow * ArmiesProtocol.LONG_COLUMNS,
                longColumns,
                0,
                rows * ArmiesProtocol.LONG_COLUMNS);
        System.arraycopy(
                sourceBytes,
                sourceRow * ArmiesProtocol.BYTE_COLUMNS,
                byteColumns,
                0,
                rows * ArmiesProtocol.BYTE_COLUMNS);
        size = rows;
        index.rebuild(intColumns, size);
    }

    void upsert(
            int[] sourceInts,
            long[] sourceLongs,
            byte[] sourceBytes,
            int sourceRow) {
        int sourceIntOffset = sourceRow * ArmiesProtocol.INT_COLUMNS;
        int handle = sourceInts[sourceIntOffset + ArmiesProtocol.COLUMN_HANDLE];
        int row = index.get(handle);
        if (row < 0) {
            row = size;
            ensureCapacity(size + 1);
            size++;
        }
        System.arraycopy(
                sourceInts,
                sourceIntOffset,
                intColumns,
                row * ArmiesProtocol.INT_COLUMNS,
                ArmiesProtocol.INT_COLUMNS);
        System.arraycopy(
                sourceLongs,
                sourceRow * ArmiesProtocol.LONG_COLUMNS,
                longColumns,
                row * ArmiesProtocol.LONG_COLUMNS,
                ArmiesProtocol.LONG_COLUMNS);
        System.arraycopy(
                sourceBytes,
                sourceRow * ArmiesProtocol.BYTE_COLUMNS,
                byteColumns,
                row * ArmiesProtocol.BYTE_COLUMNS,
                ArmiesProtocol.BYTE_COLUMNS);
        index.put(handle, row);
    }

    boolean remove(int handle) {
        int row = index.remove(handle);
        if (row < 0) {
            return false;
        }
        int last = --size;
        if (row != last) {
            System.arraycopy(
                    intColumns,
                    last * ArmiesProtocol.INT_COLUMNS,
                    intColumns,
                    row * ArmiesProtocol.INT_COLUMNS,
                    ArmiesProtocol.INT_COLUMNS);
            System.arraycopy(
                    longColumns,
                    last * ArmiesProtocol.LONG_COLUMNS,
                    longColumns,
                    row * ArmiesProtocol.LONG_COLUMNS,
                    ArmiesProtocol.LONG_COLUMNS);
            System.arraycopy(
                    byteColumns,
                    last * ArmiesProtocol.BYTE_COLUMNS,
                    byteColumns,
                    row * ArmiesProtocol.BYTE_COLUMNS,
                    ArmiesProtocol.BYTE_COLUMNS);
            index.put(intColumns[row * ArmiesProtocol.INT_COLUMNS], row);
        }
        return true;
    }

    void clear() {
        size = 0;
        index.clear();
    }

    private void ensureCapacity(int requiredRows) {
        int currentRows = intColumns.length / ArmiesProtocol.INT_COLUMNS;
        if (requiredRows > currentRows) {
            int grown = Math.max(requiredRows, Math.max(8, currentRows + (currentRows >>> 1)));
            intColumns = Arrays.copyOf(intColumns, grown * ArmiesProtocol.INT_COLUMNS);
            longColumns = Arrays.copyOf(longColumns, grown * ArmiesProtocol.LONG_COLUMNS);
            byteColumns = Arrays.copyOf(byteColumns, grown * ArmiesProtocol.BYTE_COLUMNS);
        }
        index.ensureCapacity(requiredRows);
    }

    private void checkRow(int row) {
        if (row < 0 || row >= size) {
            throw new IndexOutOfBoundsException("Row " + row + " outside 0.." + size);
        }
    }

    /** Open-addressed int-to-row map using row+1 as the occupied marker. */
    private static final class IntRowIndex {
        private int[] keys = new int[16];
        private int[] rows = new int[16];
        private int mask = 15;

        int get(int key) {
            int slot = mix(key) & mask;
            while (rows[slot] != 0) {
                if (keys[slot] == key) {
                    return rows[slot] - 1;
                }
                slot = (slot + 1) & mask;
            }
            return -1;
        }

        void put(int key, int row) {
            int slot = mix(key) & mask;
            while (rows[slot] != 0 && keys[slot] != key) {
                slot = (slot + 1) & mask;
            }
            keys[slot] = key;
            rows[slot] = row + 1;
        }

        int remove(int key) {
            int slot = mix(key) & mask;
            while (rows[slot] != 0 && keys[slot] != key) {
                slot = (slot + 1) & mask;
            }
            if (rows[slot] == 0) {
                return -1;
            }
            int removedRow = rows[slot] - 1;
            rows[slot] = 0;
            int next = (slot + 1) & mask;
            while (rows[next] != 0) {
                int movedKey = keys[next];
                int movedRow = rows[next] - 1;
                rows[next] = 0;
                put(movedKey, movedRow);
                next = (next + 1) & mask;
            }
            return removedRow;
        }

        void ensureCapacity(int expectedRows) {
            if (expectedRows * 2 < rows.length) {
                return;
            }
            int capacity = rows.length;
            do {
                capacity <<= 1;
            } while (expectedRows * 2 >= capacity);
            rehash(capacity);
        }

        void rebuild(int[] columns, int size) {
            ensureCapacity(size);
            Arrays.fill(rows, 0);
            for (int row = 0; row < size; row++) {
                put(columns[row * ArmiesProtocol.INT_COLUMNS], row);
            }
        }

        void clear() {
            Arrays.fill(rows, 0);
        }

        private void rehash(int capacity) {
            int[] oldKeys = keys;
            int[] oldRows = rows;
            keys = new int[capacity];
            rows = new int[capacity];
            mask = capacity - 1;
            for (int slot = 0; slot < oldRows.length; slot++) {
                if (oldRows[slot] != 0) {
                    put(oldKeys[slot], oldRows[slot] - 1);
                }
            }
        }

        private static int mix(int value) {
            value ^= value >>> 16;
            value *= 0x7feb352d;
            value ^= value >>> 15;
            value *= 0x846ca68b;
            return value ^ (value >>> 16);
        }
    }
}
