package dev.mcai.companion.skills.mining;

/**
 * Conservative liveness and survival bounds for fair tunnel excavation.
 */
public record MiningSkillPolicy(
        int maximumObservationAgeTicks,
        int maximumEvidenceRevisionLag,
        int maximumAimTicks,
        int maximumMiningTicks,
        int maximumClearanceWaitTicks,
        int maximumMoveTicks,
        int maximumTorchVerificationTicks,
        int durabilityReserve,
        int normalMinimumFood,
        int hardcoreMinimumFood,
        double normalMinimumHealthFraction,
        double hardcoreMinimumHealthFraction,
        double normalMaximumDanger,
        double hardcoreMaximumDanger
) {
    public MiningSkillPolicy {
        if (maximumObservationAgeTicks < 1
                || maximumObservationAgeTicks > 40
                || maximumEvidenceRevisionLag < 0
                || maximumEvidenceRevisionLag > 20
                || maximumAimTicks < 1
                || maximumAimTicks > 100
                || maximumMiningTicks < 1
                || maximumMiningTicks > 2_400
                || maximumClearanceWaitTicks < 1
                || maximumClearanceWaitTicks > 100
                || maximumMoveTicks < 1
                || maximumMoveTicks > 160
                || maximumTorchVerificationTicks < 1
                || maximumTorchVerificationTicks > 100
                || durabilityReserve < 2
                || durabilityReserve > 32
                || normalMinimumFood < 0
                || normalMinimumFood > 20
                || hardcoreMinimumFood < normalMinimumFood
                || hardcoreMinimumFood > 20
                || !fraction(normalMinimumHealthFraction)
                || !fraction(hardcoreMinimumHealthFraction)
                || hardcoreMinimumHealthFraction
                        < normalMinimumHealthFraction
                || !fraction(normalMaximumDanger)
                || !fraction(hardcoreMaximumDanger)
                || hardcoreMaximumDanger > normalMaximumDanger) {
            throw new IllegalArgumentException(
                    "Mining skill policy is outside hard bounds"
            );
        }
    }

    public static MiningSkillPolicy defaults() {
        return new MiningSkillPolicy(
                10,
                6,
                30,
                1_200,
                30,
                80,
                30,
                4,
                10,
                14,
                0.65,
                0.85,
                0.15,
                0.06
        );
    }

    private static boolean fraction(final double value) {
        return Double.isFinite(value)
                && value >= 0.0
                && value <= 1.0;
    }
}
