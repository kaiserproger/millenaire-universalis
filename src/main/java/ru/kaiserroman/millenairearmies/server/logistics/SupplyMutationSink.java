package ru.kaiserroman.millenairearmies.server.logistics;

/** Server-thread transaction boundary used when an assigned army shipment leaves settlement stock. */
public interface SupplyMutationSink {
    SupplyMutationSink NONE = new SupplyMutationSink() {
        @Override
        public boolean tryDebit(int factionId, int dimensionId, int itemKey, int amount) {
            return true;
        }

        @Override
        public void credit(int factionId, int dimensionId, int itemKey, int amount) {}
    };

    boolean tryDebit(int factionId, int dimensionId, int itemKey, int amount);

    /** Compensating action if the packed logistics reservation cannot commit after a successful debit. */
    void credit(int factionId, int dimensionId, int itemKey, int amount);
}
