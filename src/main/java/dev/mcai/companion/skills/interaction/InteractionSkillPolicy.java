package dev.mcai.companion.skills.interaction;

/**
 * Hard local liveness and freshness limits for fair interactions.
 */
public record InteractionSkillPolicy(
        int maximumObservationAgeTicks,
        int blockBreakTimeoutTicks,
        int oneShotTimeoutTicks,
        int maximumUseHoldTicks,
        double maximumCandidateDistance
) {
    public static final int HARD_MAXIMUM_TIMEOUT_TICKS = 72_000;

    public InteractionSkillPolicy {
        if (maximumObservationAgeTicks < 1
                || maximumObservationAgeTicks > 100
                || blockBreakTimeoutTicks < 1
                || blockBreakTimeoutTicks > HARD_MAXIMUM_TIMEOUT_TICKS
                || oneShotTimeoutTicks < 1
                || oneShotTimeoutTicks > 200
                || maximumUseHoldTicks < 0
                || maximumUseHoldTicks > HARD_MAXIMUM_TIMEOUT_TICKS
                || !Double.isFinite(maximumCandidateDistance)
                || maximumCandidateDistance <= 0.0
                || maximumCandidateDistance > 16.0) {
            throw new IllegalArgumentException(
                    "Interaction policy is outside hard bounds"
            );
        }
    }

    public static InteractionSkillPolicy defaults() {
        return new InteractionSkillPolicy(
                10,
                6_000,
                20,
                1_200,
                6.0
        );
    }
}
