package ru.kaiserroman.millenaire.simulation;

/**
 * Bounded persisted FIFO for world-mutation candidates and strategic notifications. The producer
 * never overwrites an unacknowledged event: a full journal fails closed and exposes a dropped count.
 */
public final class SimulationEventJournal implements SimulationEventSink {
    private final long[] sequences;
    private final byte[] types;
    private final long[] settlementIds;
    private final long[] sourceSettlementIds;
    private final int[] cultureKeys;
    private final long[] realmIds;
    private final long[] regionKeys;
    private final int[] scores;
    private final int[] reasonMasks;
    private final long[] cycles;

    private int head;
    private int size;
    private long nextSequence = 1L;
    private long droppedEvents;

    public SimulationEventJournal(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("Event journal capacity must be positive");
        sequences = new long[capacity];
        types = new byte[capacity];
        settlementIds = new long[capacity];
        sourceSettlementIds = new long[capacity];
        cultureKeys = new int[capacity];
        realmIds = new long[capacity];
        regionKeys = new long[capacity];
        scores = new int[capacity];
        reasonMasks = new int[capacity];
        cycles = new long[capacity];
    }

    @Override
    public void accept(SimulationEvent event) {
        append(event);
    }

    /** Returns the assigned sequence, or zero when the journal is full. */
    public long append(SimulationEvent event) {
        if (event == null) throw new NullPointerException("event");
        if (size == sequences.length) {
            if (droppedEvents != Long.MAX_VALUE) droppedEvents++;
            return 0L;
        }
        if (nextSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("Simulation event sequence exhausted");
        }
        long sequence = nextSequence++;
        appendRestored(sequence, event);
        return sequence;
    }

    /** Removes every event through and including {@code sequence}. */
    public int acknowledgeThrough(long sequence) {
        int removed = 0;
        while (size > 0 && sequences[head] <= sequence) {
            clear(head);
            head = (head + 1) % sequences.length;
            size--;
            removed++;
        }
        return removed;
    }

    public void visit(EventVisitor visitor) {
        if (visitor == null) throw new NullPointerException("visitor");
        for (int offset = 0; offset < size; offset++) {
            int row = (head + offset) % sequences.length;
            visitor.accept(sequences[row], eventAt(row));
        }
    }

    /** Visits only the oldest retained event and returns false when the journal is empty. */
    public boolean visitHead(EventVisitor visitor) {
        if (visitor == null) throw new NullPointerException("visitor");
        if (size == 0) return false;
        visitor.accept(sequences[head], eventAt(head));
        return true;
    }

    public void restore(long sequence, SimulationEvent event) {
        if (sequence <= 0L || event == null) throw new IllegalArgumentException("Invalid restored event");
        if (size == sequences.length) throw new IllegalArgumentException("Restored event journal exceeds capacity");
        if (size > 0) {
            int tail = (head + size - 1) % sequences.length;
            if (sequence <= sequences[tail]) {
                throw new IllegalArgumentException("Restored event sequences are not strictly increasing");
            }
        }
        appendRestored(sequence, event);
        nextSequence = Math.max(nextSequence, sequence == Long.MAX_VALUE ? Long.MAX_VALUE : sequence + 1L);
    }

    public void restoreMetadata(long restoredNextSequence, long restoredDroppedEvents) {
        if (restoredNextSequence <= 0L || restoredDroppedEvents < 0L) {
            throw new IllegalArgumentException("Invalid event journal metadata");
        }
        if (size > 0) {
            int tail = (head + size - 1) % sequences.length;
            if (restoredNextSequence <= sequences[tail]) {
                throw new IllegalArgumentException("Next sequence does not follow restored events");
            }
        }
        nextSequence = restoredNextSequence;
        droppedEvents = restoredDroppedEvents;
    }

    public int size() { return size; }
    public int capacity() { return sequences.length; }
    public long nextSequence() { return nextSequence; }
    public long droppedEventCount() { return droppedEvents; }

    public int estimatedPrimitiveBytes() {
        return sequences.length * Long.BYTES
                + types.length
                + settlementIds.length * Long.BYTES
                + sourceSettlementIds.length * Long.BYTES
                + cultureKeys.length * Integer.BYTES
                + realmIds.length * Long.BYTES
                + regionKeys.length * Long.BYTES
                + scores.length * Integer.BYTES
                + reasonMasks.length * Integer.BYTES
                + cycles.length * Long.BYTES;
    }

    private void appendRestored(long sequence, SimulationEvent event) {
        int row = (head + size) % sequences.length;
        sequences[row] = sequence;
        types[row] = (byte) event.type().ordinal();
        settlementIds[row] = event.settlementId();
        sourceSettlementIds[row] = event.sourceSettlementId();
        cultureKeys[row] = event.cultureKey();
        realmIds[row] = event.realmId();
        regionKeys[row] = event.regionKey();
        scores[row] = event.score();
        reasonMasks[row] = event.reasonMask();
        cycles[row] = event.cycle();
        size++;
    }

    private SimulationEvent eventAt(int row) {
        int type = Byte.toUnsignedInt(types[row]);
        if (type >= SimulationEventType.values().length) {
            throw new IllegalStateException("Corrupt in-memory simulation event type " + type);
        }
        return new SimulationEvent(
                SimulationEventType.values()[type],
                settlementIds[row],
                sourceSettlementIds[row],
                cultureKeys[row],
                realmIds[row],
                regionKeys[row],
                scores[row],
                reasonMasks[row],
                cycles[row]);
    }

    private void clear(int row) {
        sequences[row] = 0L;
        types[row] = 0;
        settlementIds[row] = 0L;
        sourceSettlementIds[row] = 0L;
        cultureKeys[row] = 0;
        realmIds[row] = 0L;
        regionKeys[row] = 0L;
        scores[row] = 0;
        reasonMasks[row] = 0;
        cycles[row] = 0L;
    }

    @FunctionalInterface
    public interface EventVisitor {
        void accept(long sequence, SimulationEvent event);
    }
}
