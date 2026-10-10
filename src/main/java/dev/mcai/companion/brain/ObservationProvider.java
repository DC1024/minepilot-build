package dev.mcai.companion.brain;

import dev.mcai.companion.control.GoalSnapshot;
import dev.mcai.companion.model.ObservationKind;
import dev.mcai.companion.model.RequestedObservation;

/**
 * Produces a server-authoritative companion observation on the server thread.
 */
@FunctionalInterface
public interface ObservationProvider {
    BrainObservation observe(GoalSnapshot goal) throws Exception;

    /**
     * Requests a future observation without widening what the provider may
     * perceive. Implementations must return UNSUPPORTED rather than inventing
     * data when a capture path is unavailable.
     */
    default ObservationRequestStatus requestObservation(
            final RequestedObservation request
    ) {
        return request.kind() == ObservationKind.NONE
                ? ObservationRequestStatus.REJECTED
                : ObservationRequestStatus.UNSUPPORTED;
    }
}
