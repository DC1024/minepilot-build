package dev.mcai.companion.skills.sleeping;

/**
 * Conservative freshness, health, danger, reach, and liveness bounds.
 */
public record SleepSkillPolicy(
        int maximumObservationAgeTicks,
        double maximumBedDistance,
        int sleepStartConfirmationTicks,
        int maximumTotalTicks,
        double normalMinimumHealthFraction,
        double hardcoreMinimumHealthFraction,
        double normalMaximumDanger,
        double hardcoreMaximumDanger
) {
    public SleepSkillPolicy {
        if (maximumObservationAgeTicks < 1
                || maximumObservationAgeTicks > 100
                || !positive(maximumBedDistance)
                || maximumBedDistance > 6.0
                || sleepStartConfirmationTicks < 1
                || sleepStartConfirmationTicks > 100
                || maximumTotalTicks
                        <= sleepStartConfirmationTicks
                || maximumTotalTicks > 24_000
                || !unit(normalMinimumHealthFraction)
                || !unit(hardcoreMinimumHealthFraction)
                || hardcoreMinimumHealthFraction
                        < normalMinimumHealthFraction
                || !unit(normalMaximumDanger)
                || !unit(hardcoreMaximumDanger)
                || hardcoreMaximumDanger > normalMaximumDanger) {
            throw new IllegalArgumentException(
                    "Sleep skill policy is outside hard bounds"
            );
        }
    }

    public static SleepSkillPolicy defaults() {
        return new SleepSkillPolicy(
                10,
                3.25,
                20,
                15_500,
                0.25,
                0.50,
                0.49,
                0.0
        );
    }

    public double minimumHealthFraction(boolean hardcore) {
        return hardcore
                ? hardcoreMinimumHealthFraction
                : normalMinimumHealthFraction;
    }

    public double maximumDanger(boolean hardcore) {
        return hardcore
                ? hardcoreMaximumDanger
                : normalMaximumDanger;
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static boolean unit(double value) {
        return Double.isFinite(value)
                && value >= 0.0
                && value <= 1.0;
    }
}
