package dev.mcai.companion.skills.transport;

public record MinecartSkillPolicy(
        long maximumObservationAgeTicks,
        int mountTimeoutTicks,
        int hardMaximumTravelTicks,
        int stuckTicks,
        int reverseRecoveryTicks,
        int maximumRecoveries,
        int maximumDismountWaitTicks,
        double maximumDismountSurfaceDistance,
        double progressEpsilon,
        double normalMaximumDanger,
        double hardcoreMaximumDanger
) {
    public MinecartSkillPolicy {
        if (maximumObservationAgeTicks < 1
                || maximumObservationAgeTicks > 40
                || mountTimeoutTicks < 20
                || mountTimeoutTicks > 400
                || hardMaximumTravelTicks < 40
                || hardMaximumTravelTicks
                        > MinecartTravelToParameters
                                .HARD_MAXIMUM_TIMEOUT_TICKS
                || stuckTicks < 40
                || reverseRecoveryTicks < 5
                || maximumRecoveries < 0
                || maximumRecoveries > 8
                || maximumDismountWaitTicks < 20
                || !Double.isFinite(maximumDismountSurfaceDistance)
                || maximumDismountSurfaceDistance < 2.0
                || maximumDismountSurfaceDistance > 8.0
                || !Double.isFinite(progressEpsilon)
                || progressEpsilon <= 0.0
                || progressEpsilon > 4.0
                || !probability(normalMaximumDanger)
                || !probability(hardcoreMaximumDanger)
                || hardcoreMaximumDanger > normalMaximumDanger) {
            throw new IllegalArgumentException(
                    "Invalid minecart skill policy"
            );
        }
    }

    public static MinecartSkillPolicy defaults() {
        return new MinecartSkillPolicy(
                10,
                100,
                MinecartTravelToParameters.HARD_MAXIMUM_TIMEOUT_TICKS,
                200,
                30,
                3,
                100,
                6.0,
                0.35,
                0.65,
                0.35
        );
    }

    public double maximumDanger(final boolean hardcore) {
        return hardcore
                ? hardcoreMaximumDanger
                : normalMaximumDanger;
    }

    private static boolean probability(final double value) {
        return Double.isFinite(value)
                && value >= 0.0
                && value <= 1.0;
    }
}
