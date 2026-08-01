package ru.kaiserroman.millenairearmies.ecs;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.Map;
import ru.kaiserroman.millenairearmies.persistence.PackedUnitMembership;

/** Measurement-only A/B gate for WEB-PERF-ARMY-ECS-SOA. */
public final class ArmyEcsSoaBenchmark {
    private static final int ARMY_COUNT = 1_000;
    private static final int UNIT_COUNT = 10_000;
    private static final int SCAN_WARMUP_PASSES = 2_000;
    private static final int SCAN_MEASURED_PASSES = 5_000;
    private static final int SMALL_WARMUP_PASSES = 20_000;
    private static final int SMALL_MEASURED_PASSES = 100_000;
    private static final long MISS_MOST = 0x123456789ABCDEFL;
    private static final long MISS_LEAST = 0x0FEDCBA987654321L;
    private static volatile long blackhole;

    private ArmyEcsSoaBenchmark() {}

    public static void main(String[] args) throws Exception {
        Fixture fixture = createFixture();
        long ecsBytes = modeledRetainedBytes(fixture.ecs);
        long linearMembershipBytes = modeledRetainedBytes(fixture.linearMemberships);
        long indexedMembershipBytes = modeledRetainedBytes(fixture.indexedMemberships);
        long linearTotal = ecsBytes + linearMembershipBytes;
        long indexedTotal = ecsBytes + indexedMembershipBytes;
        System.out.printf(
                "modeled retained: ecs=%d B linear-membership=%d B total=%d B (%.3f MiB); "
                        + "indexed-membership=%d B total=%d B (%.3f MiB)%n",
                ecsBytes,
                linearMembershipBytes,
                linearTotal,
                linearTotal / 1_048_576.0,
                indexedMembershipBytes,
                indexedTotal,
                indexedTotal / 1_048_576.0);

        PackedArmyEcs.UnitCursor unitCursor = fixture.ecs.newUnitCursor();
        PackedArmyEcs.UnitCursor emptyCursor = new PackedArmyEcs().newUnitCursor();
        Result fullScan = measure(
                () -> scanUnits(unitCursor), SCAN_WARMUP_PASSES, SCAN_MEASURED_PASSES);
        Result emptyScan = measure(
                () -> scanUnits(emptyCursor), SMALL_WARMUP_PASSES, SMALL_MEASURED_PASSES);

        Result linearHit = measure(
                () -> fixture.linearMemberships.unitHandleForUuid(
                        fixture.lastUuidMost, fixture.lastUuidLeast),
                SMALL_WARMUP_PASSES,
                SMALL_MEASURED_PASSES);
        Result linearMiss = measure(
                () -> fixture.linearMemberships.unitHandleForUuid(MISS_MOST, MISS_LEAST),
                SMALL_WARMUP_PASSES,
                SMALL_MEASURED_PASSES);
        Result indexedHit = measure(
                () -> fixture.indexedMemberships.unitHandleForUuid(
                        fixture.lastUuidMost, fixture.lastUuidLeast),
                SMALL_WARMUP_PASSES,
                SMALL_MEASURED_PASSES);
        Result indexedMiss = measure(
                () -> fixture.indexedMemberships.unitHandleForUuid(MISS_MOST, MISS_LEAST),
                SMALL_WARMUP_PASSES,
                SMALL_MEASURED_PASSES);

        System.out.printf(
                "10k unit cursor: %d B/pass, %.3f ms/pass; zero-unit=%d B/pass, %d ns/pass%n",
                fullScan.bytesPerPass(),
                fullScan.nanosPerPass() / 1_000_000.0,
                emptyScan.bytesPerPass(),
                emptyScan.nanosPerPass());
        System.out.printf(
                "UUID last-hit linear=%d B/%d ns indexed=%d B/%d ns; "
                        + "miss linear=%d B/%d ns indexed=%d B/%d ns%n",
                linearHit.bytesPerPass(),
                linearHit.nanosPerPass(),
                indexedHit.bytesPerPass(),
                indexedHit.nanosPerPass(),
                linearMiss.bytesPerPass(),
                linearMiss.nanosPerPass(),
                indexedMiss.bytesPerPass(),
                indexedMiss.nanosPerPass());

        if (indexedTotal > 5L * 1024L * 1024L) {
            throw new AssertionError("indexed attributable ECS exceeds 5 MiB target");
        }
        if (fullScan.nanosPerPass() > 500_000L) {
            throw new AssertionError("10k dormant scan exceeds 0.5 ms target");
        }
        if (fullScan.bytesPerPass() != 0L || emptyScan.bytesPerPass() != 0L) {
            throw new AssertionError("packed cursor scan allocates");
        }
        if (indexedHit.bytesPerPass() != 0L || indexedMiss.bytesPerPass() != 0L) {
            throw new AssertionError("indexed UUID lookup allocates");
        }
        if (indexedHit.nanosPerPass() >= linearHit.nanosPerPass()
                || indexedMiss.nanosPerPass() >= linearMiss.nanosPerPass()) {
            throw new AssertionError("primitive index did not improve 10k UUID lookup");
        }
        System.out.println("ArmyEcsSoaBenchmark PASS");
    }

    private static Fixture createFixture() {
        PackedArmyEcs ecs = new PackedArmyEcs(ARMY_COUNT, UNIT_COUNT);
        int[] armies = new int[ARMY_COUNT];
        for (int index = 0; index < ARMY_COUNT; index++) {
            armies[index] = ecs.createArmy(
                    index % 32,
                    index & 7,
                    index & 3,
                    PackedArmyEcs.packBlockPos(index, 64, -index));
        }

        PackedUnitMembership linearMemberships = new PackedUnitMembership(false);
        PackedUnitMembership indexedMemberships = new PackedUnitMembership(true);
        linearMemberships.reserve(UNIT_COUNT);
        indexedMemberships.reserve(UNIT_COUNT);
        long lastMost = 0L;
        long lastLeast = 0L;
        for (int index = 0; index < UNIT_COUNT; index++) {
            int handle = ecs.createUnit(
                    armies[index % ARMY_COUNT],
                    index & 7,
                    index & 15,
                    PackedArmyEcs.packBlockPos(index * 3, 64 + (index & 15), -index * 5));
            lastMost = 0x9E3779B97F4A7C15L * (index + 1L);
            lastLeast = 0xD1B54A32D192ED03L * (index + 17L);
            linearMemberships.bind(handle, lastMost, lastLeast);
            indexedMemberships.bind(handle, lastMost, lastLeast);
        }
        return new Fixture(ecs, linearMemberships, indexedMemberships, lastMost, lastLeast);
    }

    private static long scanUnits(PackedArmyEcs.UnitCursor cursor) {
        long checksum = 0L;
        for (cursor.reset(); cursor.advance(); ) {
            checksum += cursor.handle();
            checksum ^= cursor.packedPos();
            checksum += cursor.army();
            checksum ^= cursor.order();
            checksum += cursor.state();
        }
        return checksum;
    }

    private static Result measure(Kernel kernel, int warmupPasses, int measuredPasses) {
        for (int pass = 0; pass < warmupPasses; pass++) {
            blackhole ^= kernel.run();
        }
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        long beforeBytes = bean.getThreadAllocatedBytes(thread);
        long beforeNanos = System.nanoTime();
        long checksum = 0L;
        for (int pass = 0; pass < measuredPasses; pass++) {
            checksum ^= kernel.run();
        }
        long nanos = System.nanoTime() - beforeNanos;
        long allocated = bean.getThreadAllocatedBytes(thread) - beforeBytes;
        blackhole ^= checksum;
        return new Result(allocated / measuredPasses, nanos / measuredPasses);
    }

    /**
     * Conservative compressed-oops model: 16-byte object/array headers, 4-byte references,
     * natural field sizes, and 8-byte alignment. Only project-owned object graphs are followed.
     */
    private static long modeledRetainedBytes(Object root) throws IllegalAccessException {
        return modeledRetainedBytes(root, new IdentityHashMap<>());
    }

    private static long modeledRetainedBytes(Object value, Map<Object, Boolean> seen)
            throws IllegalAccessException {
        if (value == null || seen.put(value, Boolean.TRUE) != null) {
            return 0L;
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            int length = Array.getLength(value);
            Class<?> component = type.getComponentType();
            long bytes = 16L + (long) length * (component.isPrimitive() ? primitiveSize(component) : 4L);
            if (!component.isPrimitive()) {
                for (int index = 0; index < length; index++) {
                    bytes += modeledRetainedBytes(Array.get(value, index), seen);
                }
            }
            return align8(bytes);
        }
        if (!type.getName().startsWith("ru.kaiserroman.millenairearmies")) {
            return 0L;
        }

        long shallow = 16L;
        long children = 0L;
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            for (Field field : cursor.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Class<?> fieldType = field.getType();
                if (fieldType.isPrimitive()) {
                    shallow += primitiveSize(fieldType);
                } else {
                    shallow += 4L;
                    field.setAccessible(true);
                    children += modeledRetainedBytes(field.get(value), seen);
                }
            }
        }
        return align8(shallow) + children;
    }

    private static int primitiveSize(Class<?> type) {
        if (type == boolean.class || type == byte.class) return 1;
        if (type == char.class || type == short.class) return 2;
        if (type == int.class || type == float.class) return 4;
        if (type == long.class || type == double.class) return 8;
        throw new IllegalArgumentException("Unknown primitive " + type);
    }

    private static long align8(long value) {
        return (value + 7L) & ~7L;
    }

    @FunctionalInterface
    private interface Kernel {
        long run();
    }

    private record Result(long bytesPerPass, long nanosPerPass) {}

    private record Fixture(
            PackedArmyEcs ecs,
            PackedUnitMembership linearMemberships,
            PackedUnitMembership indexedMemberships,
            long lastUuidMost,
            long lastUuidLeast) {}
}
