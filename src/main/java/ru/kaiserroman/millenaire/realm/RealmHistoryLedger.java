package ru.kaiserroman.millenaire.realm;

import java.util.Arrays;

/** Persistable packed historical state for canonical Realms. */
public final class RealmHistoryLedger {
    private final int maximumRealms;
    private int size;
    private long revision;
    private long[] realmIds;
    private byte[] phases;
    private byte[] scales;
    private int[] capacities;
    private int[] burdens;
    private int[] viabilities;
    private int[] expansionReadiness;
    private int[] crisisMomentum;
    private int[] recoveryMomentum;
    private int[] crisisRates;
    private int[] recoveryRates;
    private int[] reasonMasks;
    private long[] foundedMilliYears;
    private long[] phaseSinceMilliYears;
    private long[] lastEvaluationMilliYears;
    private long[] lastSecessionMilliYears;

    public RealmHistoryLedger(int maximumRealms) {
        if (maximumRealms <= 0) throw new IllegalArgumentException("History limit must be positive");
        this.maximumRealms = maximumRealms;
        int capacity = Math.min(8, maximumRealms);
        realmIds = new long[capacity];
        phases = new byte[capacity];
        scales = new byte[capacity];
        capacities = new int[capacity];
        burdens = new int[capacity];
        viabilities = new int[capacity];
        expansionReadiness = new int[capacity];
        crisisMomentum = new int[capacity];
        recoveryMomentum = new int[capacity];
        crisisRates = new int[capacity];
        recoveryRates = new int[capacity];
        reasonMasks = new int[capacity];
        foundedMilliYears = new long[capacity];
        phaseSinceMilliYears = new long[capacity];
        lastEvaluationMilliYears = new long[capacity];
        lastSecessionMilliYears = new long[capacity];
        Arrays.fill(lastEvaluationMilliYears, -1L);
        Arrays.fill(lastSecessionMilliYears, -1L);
    }

    public int ensureRealm(long realmId, RealmHistoricalAssessment assessment, long foundedMilliYear) {
        if (realmId <= 0L || assessment == null || foundedMilliYear < 0L) {
            throw new IllegalArgumentException("Invalid Realm history");
        }
        int row = find(realmId);
        if (row >= 0) return row;
        if (size == maximumRealms) return -1;
        ensureCapacity(size + 1);
        row = size++;
        realmIds[row] = realmId;
        foundedMilliYears[row] = foundedMilliYear;
        apply(row, assessment, foundedMilliYear);
        changed();
        return row;
    }

    public boolean update(long realmId, RealmHistoricalAssessment assessment, long evaluationMilliYear) {
        if (assessment == null || evaluationMilliYear < 0L) {
            throw new IllegalArgumentException("Invalid historical update");
        }
        int row = find(realmId);
        if (row < 0) return false;
        apply(row, assessment, evaluationMilliYear);
        changed();
        return true;
    }

    public boolean removeRealm(long realmId) {
        int row = find(realmId);
        if (row < 0) return false;
        int last = --size;
        if (row != last) copy(last, row);
        clear(last);
        changed();
        return true;
    }

    public RealmHistoricalPhase phase(long realmId) {
        int row = find(realmId);
        return row < 0 ? null : RealmHistoricalPhase.values()[Byte.toUnsignedInt(phases[row])];
    }

    public RealmScale scale(long realmId) {
        int row = find(realmId);
        return row < 0 ? null : RealmScale.values()[Byte.toUnsignedInt(scales[row])];
    }

    public int stateCapacity(long realmId) { int row = find(realmId); return row < 0 ? 0 : capacities[row]; }
    public int crisisBurden(long realmId) { int row = find(realmId); return row < 0 ? 0 : burdens[row]; }
    public int viability(long realmId) { int row = find(realmId); return row < 0 ? 0 : viabilities[row]; }
    public int expansionReadiness(long realmId) { int row = find(realmId); return row < 0 ? 0 : expansionReadiness[row]; }
    public int crisisMomentum(long realmId) { int row = find(realmId); return row < 0 ? 0 : crisisMomentum[row]; }
    public int recoveryMomentum(long realmId) { int row = find(realmId); return row < 0 ? 0 : recoveryMomentum[row]; }
    public int crisisRatePerYear(long realmId) { int row = find(realmId); return row < 0 ? 0 : crisisRates[row]; }
    public int recoveryRatePerYear(long realmId) { int row = find(realmId); return row < 0 ? 0 : recoveryRates[row]; }
    public int reasonMask(long realmId) { int row = find(realmId); return row < 0 ? 0 : reasonMasks[row]; }
    public long foundedMilliYear(long realmId) { int row = find(realmId); return row < 0 ? -1L : foundedMilliYears[row]; }
    public long phaseSinceMilliYear(long realmId) { int row = find(realmId); return row < 0 ? -1L : phaseSinceMilliYears[row]; }
    public long lastEvaluationMilliYear(long realmId) { int row = find(realmId); return row < 0 ? -1L : lastEvaluationMilliYears[row]; }
    public long lastSecessionMilliYear(long realmId) { int row = find(realmId); return row < 0 ? -1L : lastSecessionMilliYears[row]; }

    public boolean markSecession(long realmId, long milliYear) {
        if (milliYear < 0L) throw new IllegalArgumentException("Negative secession year");
        int row = find(realmId);
        if (row < 0) return false;
        if (lastSecessionMilliYears[row] != milliYear) {
            lastSecessionMilliYears[row] = milliYear;
            changed();
        }
        return true;
    }

    public int size() { return size; }
    public long revision() { return revision; }

    public void visit(Visitor visitor) {
        if (visitor == null) throw new NullPointerException("visitor");
        for (int row = 0; row < size; row++) {
            visitor.accept(
                    realmIds[row],
                    RealmHistoricalPhase.values()[Byte.toUnsignedInt(phases[row])],
                    RealmScale.values()[Byte.toUnsignedInt(scales[row])],
                    capacities[row], burdens[row], viabilities[row], expansionReadiness[row],
                    crisisMomentum[row], recoveryMomentum[row], crisisRates[row], recoveryRates[row],
                    reasonMasks[row], foundedMilliYears[row], phaseSinceMilliYears[row],
                    lastEvaluationMilliYears[row]);
        }
    }

    public void restore(
            long realmId,
            RealmHistoricalPhase phase,
            RealmScale scale,
            int capacity,
            int burden,
            int viability,
            int expansion,
            int crisis,
            int recovery,
            int crisisRate,
            int recoveryRate,
            int reasons,
            long founded,
            long phaseSince,
            long lastEvaluation) {
        restore(
                realmId, phase, scale, capacity, burden, viability, expansion, crisis, recovery,
                crisisRate, recoveryRate, reasons, founded, phaseSince, lastEvaluation, -1L);
    }

    public void restore(
            long realmId,
            RealmHistoricalPhase phase,
            RealmScale scale,
            int capacity,
            int burden,
            int viability,
            int expansion,
            int crisis,
            int recovery,
            int crisisRate,
            int recoveryRate,
            int reasons,
            long founded,
            long phaseSince,
            long lastEvaluation,
            long lastSecession) {
        if (realmId <= 0L || phase == null || scale == null || find(realmId) >= 0
                || size == maximumRealms || founded < 0L || phaseSince < 0L
                || lastEvaluation < -1L || lastSecession < -1L) {
            throw new IllegalArgumentException("Invalid restored Realm history");
        }
        validateIndex(capacity);
        validateIndex(burden);
        validateIndex(viability);
        validateIndex(expansion);
        validateMomentum(crisis);
        validateMomentum(recovery);
        if (crisisRate < 0 || recoveryRate < 0) throw new IllegalArgumentException("Negative rate");
        ensureCapacity(size + 1);
        int row = size++;
        realmIds[row] = realmId;
        phases[row] = (byte) phase.ordinal();
        scales[row] = (byte) scale.ordinal();
        capacities[row] = capacity;
        burdens[row] = burden;
        viabilities[row] = viability;
        expansionReadiness[row] = expansion;
        crisisMomentum[row] = crisis;
        recoveryMomentum[row] = recovery;
        crisisRates[row] = crisisRate;
        recoveryRates[row] = recoveryRate;
        reasonMasks[row] = reasons;
        foundedMilliYears[row] = founded;
        phaseSinceMilliYears[row] = phaseSince;
        lastEvaluationMilliYears[row] = lastEvaluation;
        lastSecessionMilliYears[row] = lastSecession;
    }

    public void restoreRevision(long value) {
        if (value < 0L) throw new IllegalArgumentException("Negative history revision");
        revision = value;
    }

    public int estimatedPrimitiveBytes() {
        return realmIds.length * Long.BYTES
                + phases.length + scales.length
                + (capacities.length + burdens.length + viabilities.length + expansionReadiness.length
                        + crisisMomentum.length + recoveryMomentum.length + crisisRates.length
                        + recoveryRates.length + reasonMasks.length) * Integer.BYTES
                + (foundedMilliYears.length + phaseSinceMilliYears.length
                        + lastEvaluationMilliYears.length + lastSecessionMilliYears.length)
                        * Long.BYTES;
    }

    private void apply(int row, RealmHistoricalAssessment assessment, long evaluationMilliYear) {
        phases[row] = (byte) assessment.phase().ordinal();
        scales[row] = (byte) assessment.scale().ordinal();
        capacities[row] = assessment.stateCapacity();
        burdens[row] = assessment.crisisBurden();
        viabilities[row] = assessment.viability();
        expansionReadiness[row] = assessment.expansionReadiness();
        crisisMomentum[row] = assessment.crisisMomentum();
        recoveryMomentum[row] = assessment.recoveryMomentum();
        crisisRates[row] = assessment.crisisRatePerYear();
        recoveryRates[row] = assessment.recoveryRatePerYear();
        reasonMasks[row] = assessment.reasonMask();
        phaseSinceMilliYears[row] = assessment.phaseSinceMilliYear();
        lastEvaluationMilliYears[row] = evaluationMilliYear;
    }

    private int find(long realmId) {
        for (int row = 0; row < size; row++) if (realmIds[row] == realmId) return row;
        return -1;
    }

    private void ensureCapacity(int required) {
        if (required <= realmIds.length) return;
        int old = realmIds.length;
        int capacity = Math.min(maximumRealms, Math.max(required, old + Math.max(1, old >>> 1)));
        realmIds = Arrays.copyOf(realmIds, capacity);
        phases = Arrays.copyOf(phases, capacity);
        scales = Arrays.copyOf(scales, capacity);
        capacities = Arrays.copyOf(capacities, capacity);
        burdens = Arrays.copyOf(burdens, capacity);
        viabilities = Arrays.copyOf(viabilities, capacity);
        expansionReadiness = Arrays.copyOf(expansionReadiness, capacity);
        crisisMomentum = Arrays.copyOf(crisisMomentum, capacity);
        recoveryMomentum = Arrays.copyOf(recoveryMomentum, capacity);
        crisisRates = Arrays.copyOf(crisisRates, capacity);
        recoveryRates = Arrays.copyOf(recoveryRates, capacity);
        reasonMasks = Arrays.copyOf(reasonMasks, capacity);
        foundedMilliYears = Arrays.copyOf(foundedMilliYears, capacity);
        phaseSinceMilliYears = Arrays.copyOf(phaseSinceMilliYears, capacity);
        lastEvaluationMilliYears = Arrays.copyOf(lastEvaluationMilliYears, capacity);
        lastSecessionMilliYears = Arrays.copyOf(lastSecessionMilliYears, capacity);
        Arrays.fill(lastEvaluationMilliYears, old, capacity, -1L);
        Arrays.fill(lastSecessionMilliYears, old, capacity, -1L);
    }

    private void copy(int from, int to) {
        realmIds[to] = realmIds[from];
        phases[to] = phases[from];
        scales[to] = scales[from];
        capacities[to] = capacities[from];
        burdens[to] = burdens[from];
        viabilities[to] = viabilities[from];
        expansionReadiness[to] = expansionReadiness[from];
        crisisMomentum[to] = crisisMomentum[from];
        recoveryMomentum[to] = recoveryMomentum[from];
        crisisRates[to] = crisisRates[from];
        recoveryRates[to] = recoveryRates[from];
        reasonMasks[to] = reasonMasks[from];
        foundedMilliYears[to] = foundedMilliYears[from];
        phaseSinceMilliYears[to] = phaseSinceMilliYears[from];
        lastEvaluationMilliYears[to] = lastEvaluationMilliYears[from];
        lastSecessionMilliYears[to] = lastSecessionMilliYears[from];
    }

    private void clear(int row) {
        realmIds[row] = 0L;
        phases[row] = 0;
        scales[row] = 0;
        capacities[row] = burdens[row] = viabilities[row] = expansionReadiness[row] = 0;
        crisisMomentum[row] = recoveryMomentum[row] = crisisRates[row] = recoveryRates[row] = 0;
        reasonMasks[row] = 0;
        foundedMilliYears[row] = phaseSinceMilliYears[row] = 0L;
        lastEvaluationMilliYears[row] = -1L;
        lastSecessionMilliYears[row] = -1L;
    }

    private void changed() {
        if (revision == Long.MAX_VALUE) throw new IllegalStateException("History revision exhausted");
        revision++;
    }

    private static void validateIndex(int value) {
        if (value < 0 || value > 1000) throw new IllegalArgumentException("Historical index outside 0..1000");
    }

    private static void validateMomentum(int value) {
        if (value < 0 || value > RealmHistoricalPolicy.MOMENTUM_THRESHOLD) {
            throw new IllegalArgumentException("Historical momentum outside bounds");
        }
    }

    @FunctionalInterface
    public interface Visitor {
        void accept(
                long realmId,
                RealmHistoricalPhase phase,
                RealmScale scale,
                int capacity,
                int burden,
                int viability,
                int expansion,
                int crisisMomentum,
                int recoveryMomentum,
                int crisisRate,
                int recoveryRate,
                int reasonMask,
                long foundedMilliYear,
                long phaseSinceMilliYear,
                long lastEvaluationMilliYear);
    }
}
