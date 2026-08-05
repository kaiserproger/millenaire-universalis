package ru.kaiserroman.millenaire.realm;

import java.util.Arrays;

/** Persistable packed constitutions and historical path-dependence for existing Realms. */
public final class RealmInstitutionLedger {
    private final int maximumRealms;
    private int size;
    private long revision;

    private long[] realmIds;
    private byte[] governments;
    private int[] centralization;
    private int[] bureaucracy;
    private int[] noblePower;
    private int[] merchantPower;
    private int[] citizenPower;
    private int[] marketFreedom;
    private int[] landConcentration;
    private int[] militarization;
    private int[] legitimacy;
    private int[] stableCycles;
    private long[] lastEvaluationCycles;

    public RealmInstitutionLedger(int maximumRealms) {
        if (maximumRealms <= 0) throw new IllegalArgumentException("maximumRealms must be positive");
        this.maximumRealms = maximumRealms;
        int capacity = Math.min(8, maximumRealms);
        realmIds = new long[capacity];
        governments = new byte[capacity];
        centralization = new int[capacity];
        bureaucracy = new int[capacity];
        noblePower = new int[capacity];
        merchantPower = new int[capacity];
        citizenPower = new int[capacity];
        marketFreedom = new int[capacity];
        landConcentration = new int[capacity];
        militarization = new int[capacity];
        legitimacy = new int[capacity];
        stableCycles = new int[capacity];
        lastEvaluationCycles = new long[capacity];
    }

    public int ensureRealm(long realmId, Constitution initial, long cycle) {
        if (realmId <= 0L || initial == null || cycle < 0L) {
            throw new IllegalArgumentException("Invalid Realm institution input");
        }
        int row = find(realmId);
        if (row >= 0) return row;
        if (size == maximumRealms) return -1;
        ensureCapacity(size + 1);
        row = size++;
        realmIds[row] = realmId;
        write(row, initial, 0, cycle);
        changed();
        return row;
    }

    public boolean update(
            long realmId,
            Constitution constitution,
            int stableCycleCount,
            long evaluationCycle) {
        if (constitution == null || stableCycleCount < 0 || evaluationCycle < 0L) {
            throw new IllegalArgumentException("Invalid institution update");
        }
        int row = find(realmId);
        if (row < 0) return false;
        boolean mutated = !constitutionAt(row).equals(constitution)
                || stableCycles[row] != stableCycleCount
                || lastEvaluationCycles[row] != evaluationCycle;
        if (mutated) {
            write(row, constitution, stableCycleCount, evaluationCycle);
            changed();
        }
        return true;
    }

    public boolean removeRealm(long realmId) {
        int row = find(realmId);
        if (row < 0) return false;
        removeAt(row);
        changed();
        return true;
    }

    public Constitution constitution(long realmId) {
        int row = find(realmId);
        return row < 0 ? null : constitutionAt(row);
    }

    /** Schema-3 semantic: thousandths of a historical year. Kept for source compatibility. */
    public int stableCycles(long realmId) {
        return stableMilliYears(realmId);
    }

    public int stableMilliYears(long realmId) {
        int row = find(realmId);
        return row < 0 ? 0 : stableCycles[row];
    }

    /** Schema-3 semantic: historical milli-year. Kept for source compatibility. */
    public long lastEvaluationCycle(long realmId) {
        return lastEvaluationMilliYear(realmId);
    }

    public long lastEvaluationMilliYear(long realmId) {
        int row = find(realmId);
        return row < 0 ? -1L : lastEvaluationCycles[row];
    }

    public int size() { return size; }
    public long revision() { return revision; }

    public void visit(Visitor visitor) {
        if (visitor == null) throw new NullPointerException("visitor");
        for (int row = 0; row < size; row++) {
            visitor.accept(
                    realmIds[row],
                    constitutionAt(row),
                    stableCycles[row],
                    lastEvaluationCycles[row]);
        }
    }

    public void restore(
            long realmId,
            Constitution constitution,
            int stableCycleCount,
            long evaluationCycle) {
        if (realmId <= 0L || constitution == null || stableCycleCount < 0 || evaluationCycle < 0L
                || find(realmId) >= 0 || size == maximumRealms) {
            throw new IllegalArgumentException("Invalid restored Realm institution row");
        }
        ensureCapacity(size + 1);
        int row = size++;
        realmIds[row] = realmId;
        write(row, constitution, stableCycleCount, evaluationCycle);
    }

    public void restoreRevision(long value) {
        if (value < 0L) throw new IllegalArgumentException("Negative institution revision");
        revision = value;
    }

    public int estimatedPrimitiveBytes() {
        return realmIds.length * Long.BYTES
                + governments.length
                + centralization.length * Integer.BYTES
                + bureaucracy.length * Integer.BYTES
                + noblePower.length * Integer.BYTES
                + merchantPower.length * Integer.BYTES
                + citizenPower.length * Integer.BYTES
                + marketFreedom.length * Integer.BYTES
                + landConcentration.length * Integer.BYTES
                + militarization.length * Integer.BYTES
                + legitimacy.length * Integer.BYTES
                + stableCycles.length * Integer.BYTES
                + lastEvaluationCycles.length * Long.BYTES;
    }

    private Constitution constitutionAt(int row) {
        return new Constitution(
                GovernmentForm.values()[Byte.toUnsignedInt(governments[row])],
                centralization[row],
                bureaucracy[row],
                noblePower[row],
                merchantPower[row],
                citizenPower[row],
                marketFreedom[row],
                landConcentration[row],
                militarization[row],
                legitimacy[row]);
    }

    private void write(
            int row,
            Constitution constitution,
            int stableCycleCount,
            long evaluationCycle) {
        governments[row] = (byte) constitution.government().ordinal();
        centralization[row] = constitution.centralization();
        bureaucracy[row] = constitution.bureaucracy();
        noblePower[row] = constitution.noblePower();
        merchantPower[row] = constitution.merchantPower();
        citizenPower[row] = constitution.citizenPower();
        marketFreedom[row] = constitution.marketFreedom();
        landConcentration[row] = constitution.landConcentration();
        militarization[row] = constitution.militarization();
        legitimacy[row] = constitution.legitimacy();
        stableCycles[row] = stableCycleCount;
        lastEvaluationCycles[row] = evaluationCycle;
    }

    private int find(long realmId) {
        for (int row = 0; row < size; row++) {
            if (realmIds[row] == realmId) return row;
        }
        return -1;
    }

    private void removeAt(int row) {
        int last = --size;
        if (row != last) {
            realmIds[row] = realmIds[last];
            governments[row] = governments[last];
            centralization[row] = centralization[last];
            bureaucracy[row] = bureaucracy[last];
            noblePower[row] = noblePower[last];
            merchantPower[row] = merchantPower[last];
            citizenPower[row] = citizenPower[last];
            marketFreedom[row] = marketFreedom[last];
            landConcentration[row] = landConcentration[last];
            militarization[row] = militarization[last];
            legitimacy[row] = legitimacy[last];
            stableCycles[row] = stableCycles[last];
            lastEvaluationCycles[row] = lastEvaluationCycles[last];
        }
        realmIds[last] = 0L;
        governments[last] = 0;
        centralization[last] = 0;
        bureaucracy[last] = 0;
        noblePower[last] = 0;
        merchantPower[last] = 0;
        citizenPower[last] = 0;
        marketFreedom[last] = 0;
        landConcentration[last] = 0;
        militarization[last] = 0;
        legitimacy[last] = 0;
        stableCycles[last] = 0;
        lastEvaluationCycles[last] = 0L;
    }

    private void changed() {
        if (revision == Long.MAX_VALUE) throw new IllegalStateException("Institution revision exhausted");
        revision++;
    }

    private void ensureCapacity(int required) {
        if (required <= realmIds.length) return;
        int capacity = Math.min(
                maximumRealms,
                Math.max(required, realmIds.length + Math.max(1, realmIds.length >>> 1)));
        realmIds = Arrays.copyOf(realmIds, capacity);
        governments = Arrays.copyOf(governments, capacity);
        centralization = Arrays.copyOf(centralization, capacity);
        bureaucracy = Arrays.copyOf(bureaucracy, capacity);
        noblePower = Arrays.copyOf(noblePower, capacity);
        merchantPower = Arrays.copyOf(merchantPower, capacity);
        citizenPower = Arrays.copyOf(citizenPower, capacity);
        marketFreedom = Arrays.copyOf(marketFreedom, capacity);
        landConcentration = Arrays.copyOf(landConcentration, capacity);
        militarization = Arrays.copyOf(militarization, capacity);
        legitimacy = Arrays.copyOf(legitimacy, capacity);
        stableCycles = Arrays.copyOf(stableCycles, capacity);
        lastEvaluationCycles = Arrays.copyOf(lastEvaluationCycles, capacity);
    }

    @FunctionalInterface
    public interface Visitor {
        void accept(
                long realmId,
                Constitution constitution,
                int stableCycles,
                long lastEvaluationCycle);
    }
}
