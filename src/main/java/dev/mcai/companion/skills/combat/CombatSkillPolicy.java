package dev.mcai.companion.skills.combat;

/**
 * Bounded local policy for one-target melee engagements.
 */
public record CombatSkillPolicy(
        int maximumObservationAgeTicks,
        int maximumEngagementTicks,
        int normalLostGraceTicks,
        int hardcoreLostGraceTicks,
        int scanIntervalTicks,
        int maximumScanTurns,
        float scanYawDegrees,
        double attackCooldownThreshold,
        double attackReach,
        double preferredMaximumDistance,
        double tooCloseDistance,
        double guardDistance,
        double normalRetreatHealthFraction,
        double hardcoreRetreatHealthFraction,
        double normalMaximumStepDanger,
        double hardcoreMaximumStepDanger,
        double attackAlignmentDegrees,
        double movementAlignmentDegrees
) {
    public static final int HARD_MAXIMUM_ENGAGEMENT_TICKS = 12_000;

    public CombatSkillPolicy {
        if (maximumObservationAgeTicks < 1
                || maximumObservationAgeTicks > 100
                || maximumEngagementTicks < 20
                || maximumEngagementTicks
                > HARD_MAXIMUM_ENGAGEMENT_TICKS
                || normalLostGraceTicks < 4
                || normalLostGraceTicks > 600
                || hardcoreLostGraceTicks < 4
                || hardcoreLostGraceTicks > normalLostGraceTicks
                || scanIntervalTicks < 1
                || scanIntervalTicks > 40
                || maximumScanTurns < 2
                || maximumScanTurns > 64
                || !Float.isFinite(scanYawDegrees)
                || scanYawDegrees < 5.0F
                || scanYawDegrees > 90.0F
                || !unitFraction(attackCooldownThreshold)
                || attackCooldownThreshold < 0.5
                || !positive(attackReach)
                || attackReach > 6.0
                || !positive(preferredMaximumDistance)
                || preferredMaximumDistance > attackReach
                || !positive(tooCloseDistance)
                || tooCloseDistance >= preferredMaximumDistance
                || !positive(guardDistance)
                || guardDistance < attackReach
                || guardDistance > 8.0
                || !unitFraction(normalRetreatHealthFraction)
                || !unitFraction(hardcoreRetreatHealthFraction)
                || hardcoreRetreatHealthFraction
                < normalRetreatHealthFraction
                || !unitFraction(normalMaximumStepDanger)
                || !unitFraction(hardcoreMaximumStepDanger)
                || hardcoreMaximumStepDanger
                > normalMaximumStepDanger
                || !positive(attackAlignmentDegrees)
                || attackAlignmentDegrees > 15.0
                || !positive(movementAlignmentDegrees)
                || movementAlignmentDegrees > 45.0) {
            throw new IllegalArgumentException(
                    "Combat skill policy is outside hard bounds"
            );
        }
    }

    public static CombatSkillPolicy defaults() {
        return new CombatSkillPolicy(
                12,
                2_400,
                60,
                30,
                4,
                12,
                30.0F,
                0.92,
                3.0,
                2.65,
                1.65,
                4.5,
                0.30,
                0.50,
                0.40,
                0.10,
                5.0,
                15.0
        );
    }

    public int lostGraceTicks(boolean hardcore) {
        return hardcore
                ? hardcoreLostGraceTicks
                : normalLostGraceTicks;
    }

    public double retreatHealthFraction(boolean hardcore) {
        return hardcore
                ? hardcoreRetreatHealthFraction
                : normalRetreatHealthFraction;
    }

    public double maximumStepDanger(boolean hardcore) {
        return hardcore
                ? hardcoreMaximumStepDanger
                : normalMaximumStepDanger;
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static boolean unitFraction(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }
}
