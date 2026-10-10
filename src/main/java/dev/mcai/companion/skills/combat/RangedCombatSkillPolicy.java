package dev.mcai.companion.skills.combat;

public record RangedCombatSkillPolicy(
        int maximumObservationAgeTicks,
        int maximumSkillTicks,
        int betweenShotTicks,
        double aimAlignmentDegrees,
        double minimumEndCrystalDistance,
        double normalMinimumHealthFraction,
        double hardcoreMinimumHealthFraction,
        double normalMaximumDanger,
        double hardcoreMaximumDanger
) {
    public RangedCombatSkillPolicy {
        if (maximumObservationAgeTicks < 1
                || maximumObservationAgeTicks > 40
                || maximumSkillTicks < 40
                || maximumSkillTicks > 12_000
                || betweenShotTicks < 1
                || betweenShotTicks > 40
                || !positive(aimAlignmentDegrees)
                || aimAlignmentDegrees > 10.0
                || !positive(minimumEndCrystalDistance)
                || minimumEndCrystalDistance > 16.0
                || !fraction(normalMinimumHealthFraction)
                || !fraction(hardcoreMinimumHealthFraction)
                || hardcoreMinimumHealthFraction
                        < normalMinimumHealthFraction
                || !fraction(normalMaximumDanger)
                || !fraction(hardcoreMaximumDanger)
                || hardcoreMaximumDanger > normalMaximumDanger) {
            throw new IllegalArgumentException(
                    "Invalid ranged combat policy"
            );
        }
    }

    public static RangedCombatSkillPolicy defaults() {
        return new RangedCombatSkillPolicy(
                12,
                2_400,
                5,
                3.0,
                EndCrystalStandOffPlanner.MINIMUM_FIRE_DISTANCE,
                0.25,
                0.50,
                0.95,
                0.75
        );
    }

    public double minimumHealth(final boolean hardcore) {
        return hardcore
                ? hardcoreMinimumHealthFraction
                : normalMinimumHealthFraction;
    }

    public double maximumDanger(final boolean hardcore) {
        return hardcore
                ? hardcoreMaximumDanger
                : normalMaximumDanger;
    }

    private static boolean positive(final double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static boolean fraction(final double value) {
        return Double.isFinite(value)
                && value >= 0.0
                && value <= 1.0;
    }
}
