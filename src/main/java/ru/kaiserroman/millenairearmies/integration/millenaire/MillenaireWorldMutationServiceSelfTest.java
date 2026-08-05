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
        deferralDoesNotConsumeAttempts();
        deferralDemotesToRetryAfterBudget();
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
                10,
                5,
                3);

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
                10,
                5,
                3);

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
                10,
                5,
                3);
        service.tick(300L);
        check(data.events().size() == 0, "final rejection acknowledged event");
        check(service.finalRejectedCount() == 1L, "final rejection metric");
        check(service.retryCount() == 0L, "final rejection did not schedule retry");
    }

    /** An unloaded world must not burn the retry budget that would eventually drop the event. */
    private static void deferralDoesNotConsumeAttempts() {
        SimulationSavedData data = new SimulationSavedData();
        data.events().append(event(SimulationEventType.REFUGEE_FLOW, 1L));
        int[] calls = {0};
        MillenaireWorldMutationService service = new MillenaireWorldMutationService(
                data,
                (sequence, event, attempt, gameTime) -> {
                    calls[0]++;
                    return calls[0] < 3
                            ? MillenaireWorldMutationService.MutationResult.DEFER
                            : MillenaireWorldMutationService.MutationResult.COMMITTED;
                },
                1,
                2,
                10,
                5,
                100);

        service.tick(400L);
        check(calls[0] == 1, "first deferral executed");
        check(data.events().size() == 1, "deferred event retained at head");
        check(data.mutationAttempts() == 0, "deferral consumed no retry attempt");
        check(data.nextMutationAttemptTick() == 405L, "deferral used the short fixed interval");
        check(service.deferCount() == 1L && service.retryCount() == 0L, "deferral metric");

        service.tick(404L);
        check(calls[0] == 1, "executor not called before deferral deadline");

        service.tick(405L);
        check(calls[0] == 2, "second deferral executed");
        check(data.mutationAttempts() == 0, "repeated deferral still consumed no attempt");
        check(service.exhaustedRetryCount() == 0L, "deferral never exhausts the retry budget");

        service.tick(410L);
        check(calls[0] == 3 && data.events().size() == 0, "commit after the world loaded");
        check(data.mutationSequence() == 0L, "commit cleared persisted state");
    }

    /** A head that can never load must not stall the FIFO forever. */
    private static void deferralDemotesToRetryAfterBudget() {
        SimulationSavedData data = new SimulationSavedData();
        data.events().append(event(SimulationEventType.REFUGEE_FLOW, 1L));
        MillenaireWorldMutationService service = new MillenaireWorldMutationService(
                data,
                (sequence, event, attempt, gameTime) ->
                        MillenaireWorldMutationService.MutationResult.DEFER,
                1,
                2,
                10,
                5,
                2);

        service.tick(500L);
        service.tick(505L);
        check(service.deferCount() == 2L, "deferral budget consumed");
        check(data.mutationAttempts() == 0, "budget not yet exceeded");

        service.tick(510L);
        check(service.retryCount() == 1L, "deferral demoted to retry past its budget");
        check(data.mutationAttempts() == 1, "demotion consumed a retry attempt");

        service.tick(520L);
        check(data.events().size() == 0, "demoted head eventually exhausts and unblocks");
        check(service.exhaustedRetryCount() == 1L, "exhaustion counted after demotion");
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
