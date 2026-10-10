package dev.mcai.companion.model;

import java.util.concurrent.CompletionStage;

public interface ModelGateway extends AutoCloseable {
    CompletionStage<ModelOutcome> decide(PlannerInput input);

    void cancelForGoalRevision(long currentGoalRevision);

    GatewayStatus status();

    /**
     * Whether a provider delegate is installed and the setup/runtime may
     * accept conversations. The brain must not infer this from
     * {@link #status()}, because an unconfigured switchable gateway is
     * intentionally reported as IDLE.
     */
    default boolean configured() {
        return false;
    }

    /**
     * Whether this gateway may author a high-level gameplay decision.
     * Test-only holding gateways can be installed so ordinary chat and goal
     * persistence are exercised while deliberately keeping the model lane
     * inert. Production verified providers inherit the configured value.
     */
    default boolean highLevelDecisionReady() {
        return configured();
    }

    /**
     * Invalidates a cached provider delegate after the provider has rejected
     * its credential.  The default is deliberately a no-op so small test and
     * compatibility gateways remain source-compatible; the production
     * switchable gateway clears its verified delegate and cached capability
     * profile through this hook.
     */
    default void invalidateAfterAuthenticationFailure() {
        // Optional for non-persistent test gateways.
    }

    @Override
    void close();
}
