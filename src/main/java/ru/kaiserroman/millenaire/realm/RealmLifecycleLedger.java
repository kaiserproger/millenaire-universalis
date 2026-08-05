package ru.kaiserroman.millenaire.realm;

import java.util.Arrays;

/** Persistable hysteresis counters for autonomous Realm formation and dissolution. */
public final class RealmLifecycleLedger {
    private final int maximumFormationCandidates;
    private final int maximumRealmCrises;

    private int formationSize;
    private long[] formationRegions;
    private int[] formationCultures;
    private int[] formationQualifyingCycles;
    private int[] formationPressures;
    private long[] formationLastSeenCycles;

    private int crisisSize;
    private long[] crisisRealmIds;
    private int[] crisisQualifyingCycles;
    private int[] crisisPressures;
    private long[] crisisLastSeenCycles;

    private long revision;

    public RealmLifecycleLedger(int maximumFormationCandidates, int maximumRealmCrises) {
        if (maximumFormationCandidates <= 0 || maximumRealmCrises <= 0) {
            throw new IllegalArgumentException("Realm lifecycle limits must be positive");
        }
        this.maximumFormationCandidates = maximumFormationCandidates;
        this.maximumRealmCrises = maximumRealmCrises;
        int formationCapacity = Math.min(16, maximumFormationCandidates);
        formationRegions = new long[formationCapacity];
        formationCultures = new int[formationCapacity];
        formationQualifyingCycles = new int[formationCapacity];
        formationPressures = new int[formationCapacity];
        formationLastSeenCycles = new long[formationCapacity];
        int crisisCapacity = Math.min(8, maximumRealmCrises);
        crisisRealmIds = new long[crisisCapacity];
        crisisQualifyingCycles = new int[crisisCapacity];
        crisisPressures = new int[crisisCapacity];
        crisisLastSeenCycles = new long[crisisCapacity];
    }

    public int recordFormation(
            long regionKey,
            int cultureKey,
            int pressure,
            int threshold,
            int qualifyingDelta,
            long cycle) {
        if (cultureKey <= 0 || pressure < 0 || pressure > 1000 || threshold < 0 || threshold > 1000
                || qualifyingDelta <= 0 || cycle < 0L) {
            throw new IllegalArgumentException("Invalid Realm formation candidate");
        }
        int row = findFormation(regionKey, cultureKey);
        if (row < 0) {
            if (formationSize == maximumFormationCandidates) return -1;
            ensureFormationCapacity(formationSize + 1);
            row = formationSize++;
            formationRegions[row] = regionKey;
            formationCultures[row] = cultureKey;
        }
        int nextQualifying = pressure >= threshold
                ? saturatedAdd(formationQualifyingCycles[row], qualifyingDelta)
                : 0;
        if (formationQualifyingCycles[row] != nextQualifying
                || formationPressures[row] != pressure
                || formationLastSeenCycles[row] != cycle) {
            formationQualifyingCycles[row] = nextQualifying;
            formationPressures[row] = pressure;
            formationLastSeenCycles[row] = cycle;
            changed();
        }
        return nextQualifying;
    }

    public int recordCrisis(
            long realmId,
            int pressure,
            int threshold,
            int qualifyingDelta,
            long cycle) {
        if (realmId <= 0L || pressure < 0 || pressure > 1000 || threshold < 0 || threshold > 1000
                || qualifyingDelta <= 0 || cycle < 0L) {
            throw new IllegalArgumentException("Invalid Realm crisis row");
        }
        int row = findCrisis(realmId);
        if (row < 0) {
            if (crisisSize == maximumRealmCrises) return -1;
            ensureCrisisCapacity(crisisSize + 1);
            row = crisisSize++;
            crisisRealmIds[row] = realmId;
        }
        int nextQualifying = pressure >= threshold
                ? saturatedAdd(crisisQualifyingCycles[row], qualifyingDelta)
                : 0;
        if (crisisQualifyingCycles[row] != nextQualifying
                || crisisPressures[row] != pressure
                || crisisLastSeenCycles[row] != cycle) {
            crisisQualifyingCycles[row] = nextQualifying;
            crisisPressures[row] = pressure;
            crisisLastSeenCycles[row] = cycle;
            changed();
        }
        return nextQualifying;
    }

    public int finishFormationSweep(long cycle) {
        int removed = 0;
        for (int row = formationSize - 1; row >= 0; row--) {
            if (formationLastSeenCycles[row] == cycle) continue;
            removeFormationAt(row);
            removed++;
        }
        if (removed > 0) changed();
        return removed;
    }

    public int finishCrisisSweep(long cycle) {
        int removed = 0;
        for (int row = crisisSize - 1; row >= 0; row--) {
            if (crisisLastSeenCycles[row] == cycle) continue;
            removeCrisisAt(row);
            removed++;
        }
        if (removed > 0) changed();
        return removed;
    }

    public boolean removeFormation(long regionKey, int cultureKey) {
        int row = findFormation(regionKey, cultureKey);
        if (row < 0) return false;
        removeFormationAt(row);
        changed();
        return true;
    }

    public boolean removeCrisis(long realmId) {
        int row = findCrisis(realmId);
        if (row < 0) return false;
        removeCrisisAt(row);
        changed();
        return true;
    }

    public int formationQualifyingCycles(long regionKey, int cultureKey) {
        int row = findFormation(regionKey, cultureKey);
        return row < 0 ? 0 : formationQualifyingCycles[row];
    }

    public int crisisQualifyingCycles(long realmId) {
        int row = findCrisis(realmId);
        return row < 0 ? 0 : crisisQualifyingCycles[row];
    }

    public int formationSize() { return formationSize; }
    public int crisisSize() { return crisisSize; }
    public long revision() { return revision; }

    public void visitFormations(FormationVisitor visitor) {
        if (visitor == null) throw new NullPointerException("visitor");
        for (int row = 0; row < formationSize; row++) {
            visitor.accept(
                    formationRegions[row],
                    formationCultures[row],
                    formationQualifyingCycles[row],
                    formationPressures[row],
                    formationLastSeenCycles[row]);
        }
    }

    public void visitCrises(CrisisVisitor visitor) {
        if (visitor == null) throw new NullPointerException("visitor");
        for (int row = 0; row < crisisSize; row++) {
            visitor.accept(
                    crisisRealmIds[row],
                    crisisQualifyingCycles[row],
                    crisisPressures[row],
                    crisisLastSeenCycles[row]);
        }
    }

    public void restoreFormation(
            long regionKey,
            int cultureKey,
            int qualifyingCycles,
            int pressure,
            long lastSeenCycle) {
        if (cultureKey <= 0 || qualifyingCycles < 0 || pressure < 0 || pressure > 1000
                || lastSeenCycle < 0L || findFormation(regionKey, cultureKey) >= 0
                || formationSize == maximumFormationCandidates) {
            throw new IllegalArgumentException("Invalid restored Realm formation candidate");
        }
        ensureFormationCapacity(formationSize + 1);
        int row = formationSize++;
        formationRegions[row] = regionKey;
        formationCultures[row] = cultureKey;
        formationQualifyingCycles[row] = qualifyingCycles;
        formationPressures[row] = pressure;
        formationLastSeenCycles[row] = lastSeenCycle;
    }

    public void restoreCrisis(
            long realmId,
            int qualifyingCycles,
            int pressure,
            long lastSeenCycle) {
        if (realmId <= 0L || qualifyingCycles < 0 || pressure < 0 || pressure > 1000
                || lastSeenCycle < 0L || findCrisis(realmId) >= 0
                || crisisSize == maximumRealmCrises) {
            throw new IllegalArgumentException("Invalid restored Realm crisis row");
        }
        ensureCrisisCapacity(crisisSize + 1);
        int row = crisisSize++;
        crisisRealmIds[row] = realmId;
        crisisQualifyingCycles[row] = qualifyingCycles;
        crisisPressures[row] = pressure;
        crisisLastSeenCycles[row] = lastSeenCycle;
    }

    public void restoreRevision(long value) {
        if (value < 0L) throw new IllegalArgumentException("Negative lifecycle revision");
        revision = value;
    }

    public int estimatedPrimitiveBytes() {
        return formationRegions.length * Long.BYTES
                + formationCultures.length * Integer.BYTES
                + formationQualifyingCycles.length * Integer.BYTES
                + formationPressures.length * Integer.BYTES
                + formationLastSeenCycles.length * Long.BYTES
                + crisisRealmIds.length * Long.BYTES
                + crisisQualifyingCycles.length * Integer.BYTES
                + crisisPressures.length * Integer.BYTES
                + crisisLastSeenCycles.length * Long.BYTES;
    }

    private int findFormation(long regionKey, int cultureKey) {
        for (int row = 0; row < formationSize; row++) {
            if (formationRegions[row] == regionKey && formationCultures[row] == cultureKey) return row;
        }
        return -1;
    }

    private int findCrisis(long realmId) {
        for (int row = 0; row < crisisSize; row++) {
            if (crisisRealmIds[row] == realmId) return row;
        }
        return -1;
    }

    private void removeFormationAt(int row) {
        int last = --formationSize;
        if (row != last) {
            formationRegions[row] = formationRegions[last];
            formationCultures[row] = formationCultures[last];
            formationQualifyingCycles[row] = formationQualifyingCycles[last];
            formationPressures[row] = formationPressures[last];
            formationLastSeenCycles[row] = formationLastSeenCycles[last];
        }
        formationRegions[last] = 0L;
        formationCultures[last] = 0;
        formationQualifyingCycles[last] = 0;
        formationPressures[last] = 0;
        formationLastSeenCycles[last] = 0L;
    }

    private void removeCrisisAt(int row) {
        int last = --crisisSize;
        if (row != last) {
            crisisRealmIds[row] = crisisRealmIds[last];
            crisisQualifyingCycles[row] = crisisQualifyingCycles[last];
            crisisPressures[row] = crisisPressures[last];
            crisisLastSeenCycles[row] = crisisLastSeenCycles[last];
        }
        crisisRealmIds[last] = 0L;
        crisisQualifyingCycles[last] = 0;
        crisisPressures[last] = 0;
        crisisLastSeenCycles[last] = 0L;
    }

    private void ensureFormationCapacity(int required) {
        if (required <= formationRegions.length) return;
        int capacity = Math.min(
                maximumFormationCandidates,
                Math.max(required, formationRegions.length + Math.max(1, formationRegions.length >>> 1)));
        formationRegions = Arrays.copyOf(formationRegions, capacity);
        formationCultures = Arrays.copyOf(formationCultures, capacity);
        formationQualifyingCycles = Arrays.copyOf(formationQualifyingCycles, capacity);
        formationPressures = Arrays.copyOf(formationPressures, capacity);
        formationLastSeenCycles = Arrays.copyOf(formationLastSeenCycles, capacity);
    }

    private void ensureCrisisCapacity(int required) {
        if (required <= crisisRealmIds.length) return;
        int capacity = Math.min(
                maximumRealmCrises,
                Math.max(required, crisisRealmIds.length + Math.max(1, crisisRealmIds.length >>> 1)));
        crisisRealmIds = Arrays.copyOf(crisisRealmIds, capacity);
        crisisQualifyingCycles = Arrays.copyOf(crisisQualifyingCycles, capacity);
        crisisPressures = Arrays.copyOf(crisisPressures, capacity);
        crisisLastSeenCycles = Arrays.copyOf(crisisLastSeenCycles, capacity);
    }

    private void changed() {
        if (revision == Long.MAX_VALUE) throw new IllegalStateException("Realm lifecycle revision exhausted");
        revision++;
    }

    private static int saturatedAdd(int left, int right) {
        return right > Integer.MAX_VALUE - left ? Integer.MAX_VALUE : left + right;
    }

    @FunctionalInterface
    public interface FormationVisitor {
        void accept(
                long regionKey,
                int cultureKey,
                int qualifyingCycles,
                int pressure,
                long lastSeenCycle);
    }

    @FunctionalInterface
    public interface CrisisVisitor {
        void accept(
                long realmId,
                int qualifyingCycles,
                int pressure,
                long lastSeenCycle);
    }
}
