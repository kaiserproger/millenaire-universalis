package ru.kaiserroman.millenairearmies.persistence;

import java.util.Random;

/** Differentially checks the optional primitive indices against the linear fallback. */
public final class PackedUnitMembershipIndexSelfTest {
    private static final int OPERATIONS = 50_000;
    private static final int MAX_ROWS = 512;

    private PackedUnitMembershipIndexSelfTest() {}

    public static void main(String[] args) {
        PackedUnitMembership linear = new PackedUnitMembership(false);
        PackedUnitMembership indexed = new PackedUnitMembership(true);
        linear.reserve(MAX_ROWS);
        indexed.reserve(MAX_ROWS);
        PackedUnitMembership.UuidBits linearBits = linear.newUuidBits();
        PackedUnitMembership.UuidBits indexedBits = indexed.newUuidBits();
        check(rejects(() -> indexed.reserve(1 << 20)), "membership capacity bound");
        Random random = new Random(0x41524D594543534CL);
        int nextHandle = 1;
        long nextUuid = 1L;
        for (int row = 0; row < MAX_ROWS; row++) {
            bindBoth(
                    linear,
                    indexed,
                    nextHandle++,
                    uuidMost(nextUuid),
                    uuidLeast(nextUuid++));
        }
        verifyParity(linear, indexed, linearBits, indexedBits);

        for (int operation = 0; operation < OPERATIONS; operation++) {
            int size = linear.size();
            int choice = size == 0 ? 0 : random.nextInt(8);
            switch (choice) {
                case 0 -> {
                    if (size < MAX_ROWS) {
                        long most = uuidMost(nextUuid);
                        long least = uuidLeast(nextUuid++);
                        bindBoth(linear, indexed, nextHandle++, most, least);
                    }
                }
                case 1 -> {
                    int row = random.nextInt(size);
                    int handle = linear.unitHandleAt(row);
                    long most = uuidMost(nextUuid);
                    long least = uuidLeast(nextUuid++);
                    bindBoth(linear, indexed, handle, most, least);
                }
                case 2 -> {
                    int handle = linear.unitHandleAt(random.nextInt(size));
                    boolean expected = linear.unbindUnit(handle);
                    boolean actual = indexed.unbindUnit(handle);
                    check(expected == actual && expected, "unbind by handle");
                }
                case 3 -> {
                    int row = random.nextInt(size);
                    long most = linear.uuidMostAt(row);
                    long least = linear.uuidLeastAt(row);
                    boolean expected = linear.unbindUuid(most, least);
                    boolean actual = indexed.unbindUuid(most, least);
                    check(expected == actual && expected, "unbind by UUID");
                }
                case 4 -> {
                    int row = random.nextInt(size);
                    long most = linear.uuidMostAt(row);
                    long least = linear.uuidLeastAt(row);
                    check(linear.unitHandleForUuid(most, least)
                                    == indexed.unitHandleForUuid(most, least),
                            "existing UUID lookup");
                }
                case 5 -> {
                    long most = uuidMost(nextUuid + 10_000_000L);
                    long least = uuidLeast(nextUuid + 10_000_000L);
                    check(linear.unitHandleForUuid(most, least) == 0, "linear UUID miss");
                    check(indexed.unitHandleForUuid(most, least) == 0, "indexed UUID miss");
                }
                case 6 -> {
                    int row = random.nextInt(size);
                    long most = linear.uuidMostAt(row);
                    long least = linear.uuidLeastAt(row);
                    expectDuplicate(linear, indexed, nextHandle++, most, least);
                }
                case 7 -> {
                    if (size > 1) {
                        int first = random.nextInt(size);
                        int second = (first + 1 + random.nextInt(size - 1)) % size;
                        int handle = linear.unitHandleAt(first);
                        expectDuplicate(
                                linear,
                                indexed,
                                handle,
                                linear.uuidMostAt(second),
                                linear.uuidLeastAt(second));
                    }
                }
                default -> throw new AssertionError("unreachable operation " + choice);
            }

            if ((operation & 63) == 0) {
                verifyParity(linear, indexed, linearBits, indexedBits);
            }
        }
        verifyParity(linear, indexed, linearBits, indexedBits);
        System.out.printf(
                "PackedUnitMembershipIndexSelfTest PASS; operations=%d finalRows=%d%n",
                OPERATIONS,
                linear.size());
    }

    private static void bindBoth(
            PackedUnitMembership linear,
            PackedUnitMembership indexed,
            int handle,
            long most,
            long least) {
        linear.bind(handle, most, least);
        indexed.bind(handle, most, least);
    }

    private static void expectDuplicate(
            PackedUnitMembership linear,
            PackedUnitMembership indexed,
            int handle,
            long most,
            long least) {
        boolean linearRejected = rejects(() -> linear.bind(handle, most, least));
        boolean indexedRejected = rejects(() -> indexed.bind(handle, most, least));
        check(linearRejected && indexedRejected, "duplicate UUID rejection");
    }

    private static boolean rejects(Action action) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    private static void verifyParity(
            PackedUnitMembership linear,
            PackedUnitMembership indexed,
            PackedUnitMembership.UuidBits linearBits,
            PackedUnitMembership.UuidBits indexedBits) {
        check(linear.size() == indexed.size(), "size parity");
        for (int row = 0; row < linear.size(); row++) {
            int handle = linear.unitHandleAt(row);
            long most = linear.uuidMostAt(row);
            long least = linear.uuidLeastAt(row);
            check(handle == indexed.unitHandleAt(row), "row handle parity");
            check(most == indexed.uuidMostAt(row), "row UUID most parity");
            check(least == indexed.uuidLeastAt(row), "row UUID least parity");
            check(linear.unitHandleForUuid(most, least) == handle, "linear lookup parity");
            check(indexed.unitHandleForUuid(most, least) == handle, "indexed lookup parity");
            check(linear.read(handle, linearBits), "linear read");
            check(indexed.read(handle, indexedBits), "indexed read");
            check(linearBits.most() == indexedBits.most()
                            && linearBits.least() == indexedBits.least(),
                    "read payload parity");
        }

        PackedUnitMembership.Cursor linearCursor = linear.newCursor();
        PackedUnitMembership.Cursor indexedCursor = indexed.newCursor();
        for (;;) {
            boolean linearAdvanced = linearCursor.advance();
            boolean indexedAdvanced = indexedCursor.advance();
            check(linearAdvanced == indexedAdvanced, "cursor length parity");
            if (!linearAdvanced) {
                break;
            }
            check(linearCursor.unitHandle() == indexedCursor.unitHandle(), "cursor handle parity");
            check(linearCursor.uuidMost() == indexedCursor.uuidMost(), "cursor UUID most parity");
            check(linearCursor.uuidLeast() == indexedCursor.uuidLeast(), "cursor UUID least parity");
        }
        linear.checkInvariants();
        indexed.checkInvariants();
    }

    private static long uuidMost(long value) {
        return value * 0x9E3779B97F4A7C15L;
    }

    private static long uuidLeast(long value) {
        return (value + 17L) * 0xD1B54A32D192ED03L;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @FunctionalInterface
    private interface Action {
        void run();
    }
}
