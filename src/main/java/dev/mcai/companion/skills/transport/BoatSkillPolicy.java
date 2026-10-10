package dev.mcai.companion.skills.transport;

public record BoatSkillPolicy(
        int maximumObservationAgeTicks,
        int mountTimeoutTicks,
        int hardMaximumTravelTicks,
        int stuckTicks,
        int recoveryTicks,
        int maximumRecoveries,
        int maximumBrakeTicks,
        int maximumDismountWaitTicks,
        double progressEpsilon,
        double turnDeadbandDegrees,
        double forwardTurnLimitDegrees,
        double stoppedSpeed,
        double maximumDismountSurfaceDistance,
        double maximumNormalDanger,
        double maximumHardcoreDanger
) {
    public BoatSkillPolicy {
        if (maximumObservationAgeTicks < 1
                || mountTimeoutTicks < 1
                || hardMaximumTravelTicks < 40
                || hardMaximumTravelTicks
                > BoatTravelToParameters.HARD_MAXIMUM_TIMEOUT_TICKS
                || stuckTicks < 2
                || recoveryTicks < 1
                || maximumRecoveries < 1
                || maximumBrakeTicks < 1
                || maximumDismountWaitTicks < 1
                || !positiveFinite(progressEpsilon)
                || !positiveFinite(turnDeadbandDegrees)
                || !positiveFinite(forwardTurnLimitDegrees)
                || forwardTurnLimitDegrees >= 180.0
                || turnDeadbandDegrees >= forwardTurnLimitDegrees
                || !positiveFinite(stoppedSpeed)
                || !positiveFinite(maximumDismountSurfaceDistance)
                || !unitInterval(maximumNormalDanger)
                || !unitInterval(maximumHardcoreDanger)
                || maximumHardcoreDanger > maximumNormalDanger) {
            throw new IllegalArgumentException("Invalid boat skill policy");
        }
    }

    public static BoatSkillPolicy defaults() {
        return new BoatSkillPolicy(
                40,
                30,
                72_000,
                45,
                18,
                6,
                30,
                40,
                0.12,
                4.0,
                72.0,
                0.035,
                5.0,
                0.82,
                0.55
        );
    }

    public double maximumDanger(boolean hardcore) {
        return hardcore
                ? maximumHardcoreDanger
                : maximumNormalDanger;
    }

    private static boolean positiveFinite(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static boolean unitInterval(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }
}
