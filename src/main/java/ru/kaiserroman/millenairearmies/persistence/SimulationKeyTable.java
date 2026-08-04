package ru.kaiserroman.millenairearmies.persistence;

import java.util.Arrays;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/**
 * Stable adapter-owned keys for the pure Simulation kernel. Settlement ids are one-based rows, so
 * zero remains the explicit wildcard/no-settlement value used by simulation events and shocks.
 */
public final class SimulationKeyTable {
    private final int maximumSettlements;
    private final int maximumCultures;
    private final int maximumDimensions;

    private int settlementCount;
    private long[] settlementMost = new long[16];
    private long[] settlementLeast = new long[16];
    private int cultureCount;
    private String[] cultures = new String[8];
    private int dimensionCount;
    private String[] dimensions = new String[4];

    public SimulationKeyTable(int maximumSettlements, int maximumCultures, int maximumDimensions) {
        if (maximumSettlements <= 0 || maximumCultures <= 0 || maximumDimensions <= 0) {
            throw new IllegalArgumentException("Simulation key limits must be positive");
        }
        this.maximumSettlements = maximumSettlements;
        this.maximumCultures = maximumCultures;
        this.maximumDimensions = maximumDimensions;
        settlementMost = Arrays.copyOf(settlementMost, Math.min(settlementMost.length, maximumSettlements));
        settlementLeast = Arrays.copyOf(settlementLeast, Math.min(settlementLeast.length, maximumSettlements));
        cultures = Arrays.copyOf(cultures, Math.min(cultures.length, maximumCultures));
        dimensions = Arrays.copyOf(dimensions, Math.min(dimensions.length, maximumDimensions));
    }

    public long internSettlement(UUID uuid) {
        if (uuid == null) throw new NullPointerException("uuid");
        long most = uuid.getMostSignificantBits();
        long least = uuid.getLeastSignificantBits();
        int row = findSettlement(most, least);
        if (row >= 0) return row + 1L;
        if (settlementCount == maximumSettlements) {
            throw new IllegalStateException("Simulation settlement key limit reached");
        }
        ensureSettlementCapacity(settlementCount + 1);
        row = settlementCount++;
        settlementMost[row] = most;
        settlementLeast[row] = least;
        return row + 1L;
    }

    public long findSettlement(UUID uuid) {
        if (uuid == null) return 0L;
        int row = findSettlement(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
        return row < 0 ? 0L : row + 1L;
    }

    public int internCulture(ResourceLocation culture) {
        if (culture == null) throw new NullPointerException("culture");
        String value = culture.toString();
        int row = find(cultures, cultureCount, value);
        if (row >= 0) return row + 1;
        if (cultureCount == maximumCultures) {
            throw new IllegalStateException("Simulation culture key limit reached");
        }
        ensureCultureCapacity(cultureCount + 1);
        cultures[cultureCount] = value;
        return ++cultureCount;
    }

    public int internDimension(ResourceLocation dimension) {
        if (dimension == null) throw new NullPointerException("dimension");
        String value = dimension.toString();
        int row = find(dimensions, dimensionCount, value);
        if (row >= 0) return row + 1;
        if (dimensionCount == maximumDimensions) {
            throw new IllegalStateException("Simulation dimension key limit reached");
        }
        ensureDimensionCapacity(dimensionCount + 1);
        dimensions[dimensionCount] = value;
        return ++dimensionCount;
    }

    public UUID settlement(long key) {
        int row = settlementRow(key);
        return new UUID(settlementMost[row], settlementLeast[row]);
    }

    public ResourceLocation culture(int key) {
        int row = namedRow(key, cultureCount, "culture");
        return ResourceLocation.parse(cultures[row]);
    }

    public ResourceLocation dimension(int key) {
        int row = namedRow(key, dimensionCount, "dimension");
        return ResourceLocation.parse(dimensions[row]);
    }

    public boolean validSettlement(long key) { return key > 0L && key <= settlementCount; }
    public boolean validCulture(int key) { return key > 0 && key <= cultureCount; }
    public boolean validDimension(int key) { return key > 0 && key <= dimensionCount; }
    public int settlementCount() { return settlementCount; }
    public int cultureCount() { return cultureCount; }
    public int dimensionCount() { return dimensionCount; }

    public void visitSettlements(SettlementVisitor visitor) {
        if (visitor == null) throw new NullPointerException("visitor");
        for (int row = 0; row < settlementCount; row++) {
            visitor.accept(row + 1L, settlementMost[row], settlementLeast[row]);
        }
    }

    public void visitCultures(NameVisitor visitor) {
        visitNames(cultures, cultureCount, visitor);
    }

    public void visitDimensions(NameVisitor visitor) {
        visitNames(dimensions, dimensionCount, visitor);
    }

    public long restoreSettlement(long most, long least) {
        if (findSettlement(most, least) >= 0) {
            throw new IllegalArgumentException("Duplicate restored simulation settlement UUID");
        }
        if (settlementCount == maximumSettlements) {
            throw new IllegalArgumentException("Restored simulation settlement keys exceed limit");
        }
        ensureSettlementCapacity(settlementCount + 1);
        int row = settlementCount++;
        settlementMost[row] = most;
        settlementLeast[row] = least;
        return row + 1L;
    }

    public int restoreCulture(ResourceLocation culture) {
        return restoreName(culture, true);
    }

    public int restoreDimension(ResourceLocation dimension) {
        return restoreName(dimension, false);
    }

    public int estimatedPrimitiveBytes() {
        return settlementMost.length * Long.BYTES + settlementLeast.length * Long.BYTES;
    }

    private int restoreName(ResourceLocation name, boolean culture) {
        if (name == null) throw new NullPointerException("name");
        String value = name.toString();
        String[] values = culture ? cultures : dimensions;
        int count = culture ? cultureCount : dimensionCount;
        int maximum = culture ? maximumCultures : maximumDimensions;
        if (find(values, count, value) >= 0) {
            throw new IllegalArgumentException("Duplicate restored simulation name " + value);
        }
        if (count == maximum) throw new IllegalArgumentException("Restored simulation names exceed limit");
        if (culture) {
            ensureCultureCapacity(count + 1);
            cultures[count] = value;
            cultureCount++;
            return cultureCount;
        }
        ensureDimensionCapacity(count + 1);
        dimensions[count] = value;
        dimensionCount++;
        return dimensionCount;
    }

    private int findSettlement(long most, long least) {
        for (int row = 0; row < settlementCount; row++) {
            if (settlementMost[row] == most && settlementLeast[row] == least) return row;
        }
        return -1;
    }

    private static int find(String[] values, int count, String value) {
        for (int row = 0; row < count; row++) {
            if (value.equals(values[row])) return row;
        }
        return -1;
    }

    private int settlementRow(long key) {
        if (!validSettlement(key)) throw new IndexOutOfBoundsException("settlement key=" + key);
        return (int) key - 1;
    }

    private static int namedRow(int key, int count, String kind) {
        if (key <= 0 || key > count) throw new IndexOutOfBoundsException(kind + " key=" + key);
        return key - 1;
    }

    private static void visitNames(String[] names, int count, NameVisitor visitor) {
        if (visitor == null) throw new NullPointerException("visitor");
        for (int row = 0; row < count; row++) visitor.accept(row + 1, names[row]);
    }

    private void ensureSettlementCapacity(int required) {
        if (required <= settlementMost.length) return;
        int capacity = nextCapacity(settlementMost.length, required, maximumSettlements);
        settlementMost = Arrays.copyOf(settlementMost, capacity);
        settlementLeast = Arrays.copyOf(settlementLeast, capacity);
    }

    private void ensureCultureCapacity(int required) {
        if (required <= cultures.length) return;
        cultures = Arrays.copyOf(cultures, nextCapacity(cultures.length, required, maximumCultures));
    }

    private void ensureDimensionCapacity(int required) {
        if (required <= dimensions.length) return;
        dimensions = Arrays.copyOf(dimensions, nextCapacity(dimensions.length, required, maximumDimensions));
    }

    private static int nextCapacity(int current, int required, int maximum) {
        return Math.min(maximum, Math.max(required, current + Math.max(1, current >>> 1)));
    }

    @FunctionalInterface
    public interface SettlementVisitor {
        void accept(long key, long uuidMost, long uuidLeast);
    }

    @FunctionalInterface
    public interface NameVisitor {
        void accept(int key, String name);
    }
}
