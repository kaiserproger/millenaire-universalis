package ru.kaiserroman.millenairearmies.server.logistics;

/**
 * Physical inventory boundary for the allocation-stable strategic publisher.
 *
 * <p>Implementations run only on the owning server thread. A negative stock means that the source
 * is temporarily unavailable (for example, one of its chunks is unloaded), so the previous
 * snapshot must be retained. This boundary is intentionally read-only: physical dispatch and
 * delivery require a persisted idempotent shipment/WAL and component-aware courier inventory and
 * are outside the current safe slice.</p>
 */
public interface SupplyInventoryAccess {
    int UNAVAILABLE = -1;

    /**
     * Starts a cold reconciliation revision. Implementations may use it to invalidate an upstream
     * aggregate cache once, then reuse that cache while the publisher drains several item keys.
     */
    default void beginReconciliation(long revision) {}

    int absoluteStock(int factionId, int dimensionId, int itemKey);
}
