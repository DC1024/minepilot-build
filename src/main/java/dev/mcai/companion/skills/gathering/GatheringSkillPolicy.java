package dev.mcai.companion.skills.gathering;

/**
 * Conservative liveness, inventory, and survival limits for long gathering.
 */
public record GatheringSkillPolicy(
        int maximumObservationAgeTicks,
        int maximumBlockMiningTicks,
        int collectionTicks,
        int scanIntervalTicks,
        int maximumScanTurns,
        int safeCheckpointIntervalTicks,
        int stuckWindowTicks,
        int maximumStuckRecoveries,
        int durabilityReserve,
        double miningApproachDistance,
        double movementAlignmentDegrees,
        double maximumNormalDanger,
        double maximumHardcoreDanger,
        double minimumNormalHealthFraction,
        double minimumHardcoreHealthFraction
) {
    public GatheringSkillPolicy {
        if (maximumObservationAgeTicks < 1
                || maximumObservationAgeTicks > 100
                || maximumBlockMiningTicks < 1
                || maximumBlockMiningTicks > 72_000
                || collectionTicks < 0
                || collectionTicks > 200
                || scanIntervalTicks < 1
                || scanIntervalTicks > 40
                || maximumScanTurns < 12
                || maximumScanTurns > 72
                || safeCheckpointIntervalTicks < 1
                || safeCheckpointIntervalTicks > 40
                || stuckWindowTicks < 5
                || stuckWindowTicks > 200
                || maximumStuckRecoveries < 0
                || maximumStuckRecoveries > 8
                || durabilityReserve < 1
                || durabilityReserve > 64
                || !inRange(miningApproachDistance, 2.0, 5.0)
                || !inRange(movementAlignmentDegrees, 2.0, 45.0)
                || !probability(maximumNormalDanger)
                || !probability(maximumHardcoreDanger)
                || maximumHardcoreDanger > maximumNormalDanger
                || !inRange(minimumNormalHealthFraction, 0.05, 1.0)
                || !inRange(minimumHardcoreHealthFraction, 0.05, 1.0)
                || minimumHardcoreHealthFraction
                        < minimumNormalHealthFraction) {
            throw new IllegalArgumentException(
                    "Gathering policy is outside hard bounds"
            );
        }
    }

    public static GatheringSkillPolicy defaults() {
        return new GatheringSkillPolicy(
                10,
                1_200,
                80,
                4,
                36,
                10,
                30,
                3,
                3,
                4.25,
                12.0,
                0.20,
                0.10,
                0.35,
                0.60
        );
    }

    private static boolean probability(double value) {
        return inRange(value, 0.0, 1.0);
    }

    private static boolean inRange(
            double value,
            double minimum,
            double maximum
    ) {
        return Double.isFinite(value)
                && value >= minimum
                && value <= maximum;
    }
}
