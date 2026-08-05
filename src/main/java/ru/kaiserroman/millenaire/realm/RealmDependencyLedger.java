package ru.kaiserroman.millenaire.realm;

import java.util.Arrays;

/**
 * Bounded, persistable hierarchy of dependent Realms. A subject may have one direct overlord;
 * chains are allowed, but cycles are rejected. Terms are fixed-point indices in 0..1000.
 */
public final class RealmDependencyLedger {
    private final int maximumDependencies;
    private int size;
    private long revision;

    private long[] subjectRealms;
    private long[] overlordRealms;
    private int[] autonomies;
    private int[] tributeRates;
    private int[] militaryLevies;
    private long[] sinceCycles;

    public RealmDependencyLedger(int maximumDependencies) {
        if (maximumDependencies <= 0) {
            throw new IllegalArgumentException("maximumDependencies must be positive");
        }
        this.maximumDependencies = maximumDependencies;
        int capacity = Math.min(8, maximumDependencies);
        subjectRealms = new long[capacity];
        overlordRealms = new long[capacity];
        autonomies = new int[capacity];
        tributeRates = new int[capacity];
        militaryLevies = new int[capacity];
        sinceCycles = new long[capacity];
    }

    /** Creates or updates one dependency. Returns false on capacity exhaustion or a hierarchy cycle. */
    public boolean establish(
            long subjectRealm,
            long overlordRealm,
            int autonomy,
            int tributeRate,
            int militaryLevy,
            long cycle) {
        validateTerms(subjectRealm, overlordRealm, autonomy, tributeRate, militaryLevy, cycle);
        if (wouldCreateCycle(subjectRealm, overlordRealm)) return false;

        int row = findSubject(subjectRealm);
        if (row < 0) {
            if (size == maximumDependencies) return false;
            ensureCapacity(size + 1);
            row = size++;
            subjectRealms[row] = subjectRealm;
        }
        boolean mutated = overlordRealms[row] != overlordRealm
                || autonomies[row] != autonomy
                || tributeRates[row] != tributeRate
                || militaryLevies[row] != militaryLevy
                || sinceCycles[row] != cycle;
        overlordRealms[row] = overlordRealm;
        autonomies[row] = autonomy;
        tributeRates[row] = tributeRate;
        militaryLevies[row] = militaryLevy;
        sinceCycles[row] = cycle;
        if (mutated) changed();
        return true;
    }

    public boolean release(long subjectRealm) {
        int row = findSubject(subjectRealm);
        if (row < 0) return false;
        removeAt(row);
        changed();
        return true;
    }

    /** Removes every dependency in which the dissolved Realm is either subject or overlord. */
    public int removeRealm(long realmId) {
        if (realmId <= 0L) return 0;
        int removed = 0;
        for (int row = size - 1; row >= 0; row--) {
            if (subjectRealms[row] != realmId && overlordRealms[row] != realmId) continue;
            removeAt(row);
            removed++;
        }
        if (removed != 0) changed();
        return removed;
    }

    public long overlordOf(long subjectRealm) {
        int row = findSubject(subjectRealm);
        return row < 0 ? RealmRegistry.NO_REALM : overlordRealms[row];
    }

    public boolean isSubject(long realmId) { return findSubject(realmId) >= 0; }
    public int autonomy(long subjectRealm) { return valueAt(subjectRealm, autonomies, 1000); }
    public int tributeRate(long subjectRealm) { return valueAt(subjectRealm, tributeRates, 0); }
    public int militaryLevy(long subjectRealm) { return valueAt(subjectRealm, militaryLevies, 0); }

    public long sinceCycle(long subjectRealm) {
        int row = findSubject(subjectRealm);
        return row < 0 ? 0L : sinceCycles[row];
    }

    public int directSubjectCount(long overlordRealm) {
        if (overlordRealm <= 0L) return 0;
        int count = 0;
        for (int row = 0; row < size; row++) {
            if (overlordRealms[row] == overlordRealm) count++;
        }
        return count;
    }

    /** Low-autonomy subjects cannot independently declare wars or conclude strategic treaties. */
    public boolean mayConductIndependentDiplomacy(long realmId) {
        int row = findSubject(realmId);
        return row < 0 || autonomies[row] >= 650;
    }

    public long tributeDue(long subjectRealm, long taxableRevenue) {
        if (taxableRevenue < 0L) throw new IllegalArgumentException("Negative taxable revenue");
        int rate = tributeRate(subjectRealm);
        long whole = taxableRevenue / 1000L;
        long remainder = taxableRevenue % 1000L;
        return whole * rate + remainder * rate / 1000L;
    }

    public int militaryContribution(long subjectRealm, int availableUnits) {
        if (availableUnits < 0) throw new IllegalArgumentException("Negative available units");
        return (int) Math.min(Integer.MAX_VALUE, (long) availableUnits * militaryLevy(subjectRealm) / 1000L);
    }

    public int size() { return size; }
    public long revision() { return revision; }

    public void visit(Visitor visitor) {
        if (visitor == null) throw new NullPointerException("visitor");
        for (int row = 0; row < size; row++) {
            visitor.accept(
                    subjectRealms[row],
                    overlordRealms[row],
                    autonomies[row],
                    tributeRates[row],
                    militaryLevies[row],
                    sinceCycles[row]);
        }
    }

    public void restore(
            long subjectRealm,
            long overlordRealm,
            int autonomy,
            int tributeRate,
            int militaryLevy,
            long sinceCycle) {
        validateTerms(subjectRealm, overlordRealm, autonomy, tributeRate, militaryLevy, sinceCycle);
        if (findSubject(subjectRealm) >= 0 || size == maximumDependencies
                || wouldCreateCycle(subjectRealm, overlordRealm)) {
            throw new IllegalArgumentException("Invalid restored Realm dependency");
        }
        ensureCapacity(size + 1);
        int row = size++;
        subjectRealms[row] = subjectRealm;
        overlordRealms[row] = overlordRealm;
        autonomies[row] = autonomy;
        tributeRates[row] = tributeRate;
        militaryLevies[row] = militaryLevy;
        sinceCycles[row] = sinceCycle;
    }

    public void restoreRevision(long value) {
        if (value < 0L) throw new IllegalArgumentException("Negative dependency revision");
        revision = value;
    }

    public int estimatedPrimitiveBytes() {
        return subjectRealms.length * Long.BYTES
                + overlordRealms.length * Long.BYTES
                + (autonomies.length + tributeRates.length + militaryLevies.length) * Integer.BYTES
                + sinceCycles.length * Long.BYTES;
    }

    private boolean wouldCreateCycle(long subjectRealm, long overlordRealm) {
        long cursor = overlordRealm;
        for (int depth = 0; depth <= size; depth++) {
            if (cursor == subjectRealm) return true;
            int row = findSubject(cursor);
            if (row < 0) return false;
            cursor = overlordRealms[row];
        }
        return true;
    }

    private int findSubject(long subjectRealm) {
        if (subjectRealm <= 0L) return -1;
        for (int row = 0; row < size; row++) {
            if (subjectRealms[row] == subjectRealm) return row;
        }
        return -1;
    }

    private int valueAt(long subjectRealm, int[] values, int independentValue) {
        int row = findSubject(subjectRealm);
        return row < 0 ? independentValue : values[row];
    }

    private void removeAt(int row) {
        int last = --size;
        if (row != last) {
            subjectRealms[row] = subjectRealms[last];
            overlordRealms[row] = overlordRealms[last];
            autonomies[row] = autonomies[last];
            tributeRates[row] = tributeRates[last];
            militaryLevies[row] = militaryLevies[last];
            sinceCycles[row] = sinceCycles[last];
        }
        subjectRealms[last] = 0L;
        overlordRealms[last] = 0L;
        autonomies[last] = 0;
        tributeRates[last] = 0;
        militaryLevies[last] = 0;
        sinceCycles[last] = 0L;
    }

    private void ensureCapacity(int required) {
        if (required <= subjectRealms.length) return;
        int capacity = Math.min(
                maximumDependencies,
                Math.max(required, subjectRealms.length + Math.max(1, subjectRealms.length >>> 1)));
        subjectRealms = Arrays.copyOf(subjectRealms, capacity);
        overlordRealms = Arrays.copyOf(overlordRealms, capacity);
        autonomies = Arrays.copyOf(autonomies, capacity);
        tributeRates = Arrays.copyOf(tributeRates, capacity);
        militaryLevies = Arrays.copyOf(militaryLevies, capacity);
        sinceCycles = Arrays.copyOf(sinceCycles, capacity);
    }

    private void changed() {
        if (revision == Long.MAX_VALUE) {
            throw new IllegalStateException("Realm dependency revision exhausted");
        }
        revision++;
    }

    private static void validateTerms(
            long subjectRealm,
            long overlordRealm,
            int autonomy,
            int tributeRate,
            int militaryLevy,
            long cycle) {
        if (subjectRealm <= 0L || overlordRealm <= 0L || subjectRealm == overlordRealm || cycle < 0L) {
            throw new IllegalArgumentException("Invalid Realm dependency identity");
        }
        requireIndex(autonomy, "autonomy");
        requireIndex(tributeRate, "tributeRate");
        requireIndex(militaryLevy, "militaryLevy");
    }

    private static void requireIndex(int value, String name) {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException(name + " outside 0..1000");
        }
    }

    @FunctionalInterface
    public interface Visitor {
        void accept(
                long subjectRealm,
                long overlordRealm,
                int autonomy,
                int tributeRate,
                int militaryLevy,
                long sinceCycle);
    }
}
