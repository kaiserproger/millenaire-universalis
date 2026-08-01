package ru.kaiserroman.millenairearmies.model;

/**
 * Mutable primitive logistics component. Fulfilment updates mutate the component instead of
 * allocating replacement records. Item keys are runtime registry ids and must be remapped by the
 * persistence boundary.
 */
public final class LogisticsRequest {
    private final long requestId;
    private final int factionId;
    private final int requesterArmyId;
    private final int itemKey;
    private final int requiredAmount;
    private final int dimensionId;
    private final long destination;
    private final long createdGameTime;
    private final byte priority;

    private int fulfilledAmount;
    private byte statusCode;

    public LogisticsRequest(
            long requestId,
            int factionId,
            int requesterArmyId,
            int itemKey,
            int requiredAmount,
            int fulfilledAmount,
            int dimensionId,
            long destination,
            long createdGameTime,
            byte priority,
            byte statusCode) {
        if (requestId < 0
                || factionId < 0
                || requesterArmyId < 0
                || itemKey < 0
                || dimensionId < 0
                || createdGameTime < 0) {
            throw new IllegalArgumentException("Logistics ids and creation time must be non-negative");
        }
        if (requiredAmount <= 0 || fulfilledAmount < 0 || fulfilledAmount > requiredAmount) {
            throw new IllegalArgumentException("Fulfilled amount must be within 0..required amount");
        }
        if (priority < 0 || priority > 7) {
            throw new IllegalArgumentException("Priority must be within 0..7");
        }
        if (!LogisticsRequestStatus.isValidCode(statusCode)) {
            throw new IllegalArgumentException("Unknown logistics request status: " + statusCode);
        }
        if (statusCode == LogisticsRequestStatus.FULFILLED.code() && fulfilledAmount != requiredAmount) {
            throw new IllegalArgumentException("A fulfilled request must contain the complete required amount");
        }
        if (fulfilledAmount == requiredAmount
                && statusCode != LogisticsRequestStatus.FULFILLED.code()
                && statusCode != LogisticsRequestStatus.CANCELLED.code()) {
            throw new IllegalArgumentException("A complete request must use the fulfilled status");
        }
        this.requestId = requestId;
        this.factionId = factionId;
        this.requesterArmyId = requesterArmyId;
        this.itemKey = itemKey;
        this.requiredAmount = requiredAmount;
        this.fulfilledAmount = fulfilledAmount;
        this.dimensionId = dimensionId;
        this.destination = destination;
        this.createdGameTime = createdGameTime;
        this.priority = priority;
        this.statusCode = statusCode;
    }

    public int addFulfilled(int amount) {
        if (amount <= 0 || statusCode == LogisticsRequestStatus.CANCELLED.code()) {
            return 0;
        }
        int accepted = Math.min(amount, requiredAmount - fulfilledAmount);
        fulfilledAmount += accepted;
        if (fulfilledAmount == requiredAmount) {
            statusCode = LogisticsRequestStatus.FULFILLED.code();
        }
        return accepted;
    }

    public boolean transitionTo(LogisticsRequestStatus next) {
        byte nextCode = next.code();
        if (!canTransition(statusCode, nextCode)) {
            return false;
        }
        statusCode = nextCode;
        return true;
    }

    private static boolean canTransition(byte current, byte next) {
        if (current == next) {
            return true;
        }
        if (next == LogisticsRequestStatus.CANCELLED.code()) {
            return current != LogisticsRequestStatus.FULFILLED.code()
                    && current != LogisticsRequestStatus.CANCELLED.code();
        }
        return (current == LogisticsRequestStatus.PENDING.code()
                        && next == LogisticsRequestStatus.ASSIGNED.code())
                || (current == LogisticsRequestStatus.ASSIGNED.code()
                        && next == LogisticsRequestStatus.IN_TRANSIT.code());
    }

    public long requestId() {
        return requestId;
    }

    public int factionId() {
        return factionId;
    }

    public int requesterArmyId() {
        return requesterArmyId;
    }

    public int itemKey() {
        return itemKey;
    }

    public int requiredAmount() {
        return requiredAmount;
    }

    public int fulfilledAmount() {
        return fulfilledAmount;
    }

    public int dimensionId() {
        return dimensionId;
    }

    public long destination() {
        return destination;
    }

    public long createdGameTime() {
        return createdGameTime;
    }

    public byte priority() {
        return priority;
    }

    public byte statusCode() {
        return statusCode;
    }

    public LogisticsRequestStatus status() {
        return LogisticsRequestStatus.fromCode(statusCode);
    }
}
