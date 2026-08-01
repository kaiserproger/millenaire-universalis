package ru.kaiserroman.millenairearmies.model;

/** Persisted compact state for a logistics request. */
public enum LogisticsRequestStatus {
    PENDING((byte) 0),
    ASSIGNED((byte) 1),
    IN_TRANSIT((byte) 2),
    FULFILLED((byte) 3),
    CANCELLED((byte) 4);

    private final byte code;

    LogisticsRequestStatus(byte code) {
        this.code = code;
    }

    public byte code() {
        return code;
    }

    public static boolean isValidCode(byte code) {
        return code >= PENDING.code && code <= CANCELLED.code;
    }

    public static LogisticsRequestStatus fromCode(byte code) {
        return switch (code) {
            case 0 -> PENDING;
            case 1 -> ASSIGNED;
            case 2 -> IN_TRANSIT;
            case 3 -> FULFILLED;
            case 4 -> CANCELLED;
            default -> throw new IllegalArgumentException("Unknown logistics request status: " + code);
        };
    }
}
