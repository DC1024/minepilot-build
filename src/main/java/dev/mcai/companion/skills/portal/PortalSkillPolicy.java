package dev.mcai.companion.skills.portal;

/**
 * Conservative bounds for a short, directly observed portal entry.
 */
public record PortalSkillPolicy(
        int maximumObservationAgeTicks,
        double maximumApproachDistance,
        double normalMaximumDanger,
        double hardcoreMaximumDanger,
        int targetLostGraceTicks,
        int maximumCommittedFrameGapTicks,
        int maximumTotalTicks,
        int maximumPortalWaitTicks,
        int entryPulseTicks,
        int stuckWindowTicks,
        int maximumRecoveryJumps,
        double alignmentToleranceDegrees,
        double committedHorizontalDistance,
        double committedVerticalDistance,
        double gatewayMinimumDisplacement
) {
    public PortalSkillPolicy {
        if (maximumObservationAgeTicks < 1
                || maximumObservationAgeTicks > 200
                || !positive(maximumApproachDistance)
                || maximumApproachDistance > 16.0
                || !unit(normalMaximumDanger)
                || !unit(hardcoreMaximumDanger)
                || hardcoreMaximumDanger > normalMaximumDanger
                || targetLostGraceTicks < 0
                || targetLostGraceTicks > 100
                || maximumCommittedFrameGapTicks < 1
                || maximumCommittedFrameGapTicks > 100
                || maximumTotalTicks < 1
                || maximumTotalTicks > 2_400
                || maximumPortalWaitTicks < 1
                || maximumPortalWaitTicks > maximumTotalTicks
                || entryPulseTicks < 1
                || entryPulseTicks > 40
                || stuckWindowTicks < 1
                || stuckWindowTicks > 200
                || maximumRecoveryJumps < 0
                || maximumRecoveryJumps > 8
                || !positive(alignmentToleranceDegrees)
                || alignmentToleranceDegrees > 90.0
                || !positive(committedHorizontalDistance)
                || committedHorizontalDistance > 2.0
                || !positive(committedVerticalDistance)
                || committedVerticalDistance > 4.0
                || !positive(gatewayMinimumDisplacement)
                || gatewayMinimumDisplacement < 4.0) {
            throw new IllegalArgumentException(
                    "Invalid portal skill policy"
            );
        }
    }

    public static PortalSkillPolicy defaults() {
        return new PortalSkillPolicy(
                30,
                16.0,
                0.75,
                0.45,
                10,
                10,
                800,
                360,
                8,
                24,
                3,
                14.0,
                0.62,
                2.25,
                8.0
        );
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static boolean unit(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }
}
