package dev.mcai.companion.skills.farming;

/**
 * Hard freshness, reach, and liveness limits for one field operation.
 */
public record FarmingSkillPolicy(
        int maximumObservationAgeTicks,
        int totalTimeoutTicks,
        int replantConfirmationTicks,
        double maximumCandidateDistance
) {
    public FarmingSkillPolicy {
        if (maximumObservationAgeTicks < 1
                || maximumObservationAgeTicks > 100
                || totalTimeoutTicks < 2
                || totalTimeoutTicks > 6_000
                || replantConfirmationTicks < 1
                || replantConfirmationTicks > totalTimeoutTicks
                || !Double.isFinite(maximumCandidateDistance)
                || maximumCandidateDistance <= 0.0
                || maximumCandidateDistance > 16.0) {
            throw new IllegalArgumentException(
                    "Farming policy is outside hard bounds"
            );
        }
    }

    public static FarmingSkillPolicy defaults() {
        /*
         * A real ServerPlayer may need to walk around one irrigation edge,
         * wait for a fresh centre-ray observation, and then complete the
         * vanilla use packet.  Two hundred ticks made that fair transaction
         * time out while the body was still converging on the authorized plot.
         * Keep the bound finite, but leave enough room for one correction
         * without allowing an unbounded movement loop.
         */
        return new FarmingSkillPolicy(10, 320, 40, 6.0);
    }
}
