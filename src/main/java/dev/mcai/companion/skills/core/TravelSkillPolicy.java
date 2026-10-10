package dev.mcai.companion.skills.core;

/**
 * Hard bounds for one rolling same-dimension journey.
 */
public record TravelSkillPolicy(
        double maximumSegmentDistance,
        int maximumSegmentSteps,
        int maximumSegments,
        long maximumTotalTicks,
        long maximumNoProgressTicks,
        long maximumStationarySegmentTicks,
        int maximumScansWithoutGrowth,
        int scanIntervalTicks,
        float scanSpreadDegrees,
        long maximumVoxelAgeRevisions,
        double normalMaximumDanger,
        double hardcoreMaximumDanger
) {
    public TravelSkillPolicy {
        if (!Double.isFinite(maximumSegmentDistance)
                || maximumSegmentDistance < 2.0
                || maximumSegmentDistance > 16.0
                || maximumSegmentSteps < 2
                || maximumSegmentSteps > 64
                || maximumSegments < 1
                || maximumSegments > 100_000
                || maximumTotalTicks < 20
                || maximumTotalTicks > 432_000
                || maximumNoProgressTicks < 20
                || maximumNoProgressTicks > maximumTotalTicks
                || maximumStationarySegmentTicks < 10
                || maximumStationarySegmentTicks > maximumNoProgressTicks
                || maximumScansWithoutGrowth < 1
                || maximumScansWithoutGrowth > 256
                || scanIntervalTicks < 1
                || scanIntervalTicks > 40
                || !Float.isFinite(scanSpreadDegrees)
                || scanSpreadDegrees < 5.0F
                || scanSpreadDegrees > 75.0F
                || maximumVoxelAgeRevisions < 1
                || maximumVoxelAgeRevisions > 10_000
                || !probability(normalMaximumDanger)
                || !probability(hardcoreMaximumDanger)
                || hardcoreMaximumDanger > normalMaximumDanger) {
            throw new IllegalArgumentException(
                    "Travel skill policy is outside hard bounds"
            );
        }
    }

    public static TravelSkillPolicy defaults() {
        return new TravelSkillPolicy(
                8.0,
                24,
                50_000,
                144_000,
                1_200,
                100,
                48,
                4,
                30.0F,
                256,
                0.25,
                0.10
        );
    }

    /**
     * Shorter bounded legs for local first-person search compounds. A search
     * waypoint is an observation station, not a promise to spend minutes
     * circling a blocked frontier; after this budget the outer explorer
     * chooses its next fair bearing and retries with fresh evidence.
     */
    public static TravelSkillPolicy explorationDefaults() {
        return new TravelSkillPolicy(
                8.0,
                24,
                256,
                900,
                240,
                80,
                12,
                4,
                30.0F,
                256,
                0.25,
                0.10
        );
    }

    private static boolean probability(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }
}
