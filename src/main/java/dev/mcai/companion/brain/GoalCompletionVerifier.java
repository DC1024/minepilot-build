package dev.mcai.companion.brain;

import dev.mcai.companion.control.GoalSnapshot;
import java.util.Optional;

/**
 * Independent completion guard for goals with locally verifiable outcomes.
 */
@FunctionalInterface
public interface GoalCompletionVerifier {
    GoalCompletionVerifier ALLOW_ORDINARY_GOALS =
            ignored -> GoalCompletionVerification.approved();

    GoalCompletionVerification verify(GoalSnapshot goal);

    /**
     * Whether a model may self-certify an external gameplay goal before any
     * local skill has started. Test/recovery verifiers may keep the historical
     * permissive behavior; the production server verifier disables it for
     * player and MCP tasks so a provider cannot turn an acknowledgement into
     * a completed-but-motionless goal.
     */
    default boolean allowModelCompletionWithoutAction(
            final GoalSnapshot goal
    ) {
        return true;
    }

    /**
     * Offers completion without waiting for a model to restate facts already
     * proven by the server.
     *
     * <p>Ordinary conversational goals deliberately return empty: their
     * semantic completion still belongs to the high-level planner. A
     * verifier for an explicit acceptance route may return its current
     * authoritative result so the orchestrator can close an already
     * satisfied goal without spending tokens on repeated no-op replans.</p>
     */
    default Optional<GoalCompletionVerification>
            verifyAutonomousCompletion(final GoalSnapshot goal) {
        return Optional.empty();
    }
}
