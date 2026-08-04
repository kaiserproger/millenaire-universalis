package ru.kaiserroman.millenairearmies.integration.millenaire;

import ru.kaiserroman.millenaire.simulation.SimulationEvent;
import ru.kaiserroman.millenaire.simulation.SimulationEventType;
import ru.kaiserroman.millenairearmies.persistence.SimulationSavedData;

/** FIFO commit, persisted retry, exception quarantine and retry exhaustion checks. */
public final class MillenaireWorldMutationServiceSelfTest {
    private MillenaireWorldMutationServiceSelfTest() {}

    public static void main(String[] args) {
        commitsAndRetriesWithoutSkippingHead();
        exceptionsFailClosedAndEventuallyExhaust();
        finalRejectionAcknowledgesEvent();
        System.out.println("Millenaire world mutation service self-test passed");
    }

    private static void commitsAndRetriesWithoutSkippingHead() {
        SimulationSavedData data = new SimulationSavedData();
        data.events().append(event(SimulationEventType.DECLINE_STARTED, 1L));
        data.events().append(event(SimulationEventType.FOUNDING_CANDIDATE, 2L));
        int[] calls = {0};
        MillenaireWorldMutationService service = new MillenaireWorldMutationService(
                data,
                (sequence, event, attempt, gameTime) -> {
                    calls[0]++;
                    if (sequence == 1L) {
                        return MillenaireWorldMutationService.MutationResult.COMMITTED;
                    }
                    return attempt == 0
                            ? MillenaireWorldMutationService.MutationResult.RETRY
                            : MillenaireWorldMutationService.MutationResult.COMMITTED;
                },
                2,
                4,
                10);

        service.tick(100L);
        check(calls[0] == 2, "commit and retry consumed one bounded tick");
        check(data.events().size() == 1, "retry event remains at FIFO head");
        check(data.mutationSequence() == 2L
                        && data.mutationAttempts() == 1
                        && data.nextMutationAttemptTick() == 110L,
                "retry state persisted");
        check(service.committedCount() == 1L && service.retryCount() == 1L,
                "commit/retry metrics");

        service.tick(109L);
        check(calls[0] == 2, "executor not called before retry deadline");
        check(data.events().size() == 1, "head retained before retry deadline");

        service.tick(110L);
        check(calls[0] == 3, "executor called at retry deadline");
        check(data.events().size() == 0, "successful retry acknowledged head");
        check(data.mutationSequence() == 0L
                        && data.mutationAttempts() == 0
                        && data.nextMutationAttemptTick() == 0L,
                "successful retry cleared persisted state");
        check(service.committedCount() == 2L, "second commit metric");
    }

    private static void exceptionsFailClosedAndEventuallyExhaust() {
        SimulationSavedData data = new SimulationSavedData();
        data.events().append(event(SimulationEventType.ABANDONMENT_CANDIDATE, 1L));
        int[] calls = {0};
        MillenaireWorldMutationService service = new MillenaireWorldMutationService(
                data,
                (sequence, event, attempt, gameTime) -> {
                    calls[0]++;
                    throw new IllegalStateException("synthetic mutation failure");
                },
                1,
                2,
                10);

        service.tick(200L);
        check(calls[0] == 1, "first failing attempt executed");
        check(data.events().size() == 1 && data.mutationAttempts() == 1,
                "first failure retained event");
        check(data.nextMutationAttemptTick() == 210L, "failure backoff scheduled");

        service.tick(210L);
        check(calls[0] == 2, "second failing attempt executed");
        check(data.events().size() == 0, "exhausted retry acknowledged event");
        check(data.mutationSequence() == 0L, "exhausted retry cleared state");
        check(service.executorFailureCount() == 2L, "executor failures counted");
        check(service.exhaustedRetryCount() == 1L, "retry exhaustion counted");
    }

    private static void finalRejectionAcknowledgesEvent() {
        SimulationSavedData data = new SimulationSavedData();
        data.events().append(event(SimulationEventType.FOUNDING_CANDIDATE, 1L));
        MillenaireWorldMutationService service = new MillenaireWorldMutationService(
                data,
                (sequence, event, attempt, gameTime) ->
                        MillenaireWorldMutationService.MutationResult.FINAL_REJECT,
                1,
                4,
                10);
        service.tick(300L);
        check(data.events().size() == 0, "final rejection acknowledged event");
        check(service.finalRejectedCount() == 1L, "final rejection metric");
        check(service.retryCount() == 0L, "final rejection did not schedule retry");
    }

    private static SimulationEvent event(SimulationEventType type, long settlementId) {
        return new SimulationEvent(
                type,
                settlementId,
                settlementId,
                1,
                0L,
                1L,
                700,
                0,
                1L);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
