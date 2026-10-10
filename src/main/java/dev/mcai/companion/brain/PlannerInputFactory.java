package dev.mcai.companion.brain;

import dev.mcai.companion.control.GoalSnapshot;
import dev.mcai.companion.model.PlannerInput;

/**
 * Builds the bounded provider prompt from trusted goal metadata and one fair
 * observation. It performs no network or world mutation.
 */
@FunctionalInterface
public interface PlannerInputFactory {
    PlannerInput create(
            String requestId,
            GoalSnapshot goal,
            BrainObservation observation
    ) throws Exception;
}
