package dev.mcai.companion.mechanism;

/** Hard-bounded retention policy for a first-person mechanism-site survey. */
public record MechanismSurveyPolicy(
        long maximumEvidenceAgeTicks,
        double retentionRadius,
        int maximumSurfaceObservations,
        int maximumVoxelObservations
) {
    public static final long HARD_MAXIMUM_EVIDENCE_AGE_TICKS = 24_000;
    public static final double HARD_MAXIMUM_RETENTION_RADIUS = 32.0;
    public static final int HARD_MAXIMUM_SURFACES = 2_048;
    public static final int HARD_MAXIMUM_VOXELS = 32_768;

    public MechanismSurveyPolicy {
        if (maximumEvidenceAgeTicks < 1
                || maximumEvidenceAgeTicks
                        > HARD_MAXIMUM_EVIDENCE_AGE_TICKS) {
            throw new IllegalArgumentException(
                    "Mechanism survey age is outside hard bounds"
            );
        }
        if (!Double.isFinite(retentionRadius)
                || retentionRadius < 8.0
                || retentionRadius > HARD_MAXIMUM_RETENTION_RADIUS) {
            throw new IllegalArgumentException(
                    "Mechanism survey radius is outside hard bounds"
            );
        }
        if (maximumSurfaceObservations < 81
                || maximumSurfaceObservations
                        > HARD_MAXIMUM_SURFACES) {
            throw new IllegalArgumentException(
                    "Mechanism surface budget is outside hard bounds"
            );
        }
        if (maximumVoxelObservations < 256
                || maximumVoxelObservations > HARD_MAXIMUM_VOXELS) {
            throw new IllegalArgumentException(
                    "Mechanism voxel budget is outside hard bounds"
            );
        }
    }

    public static MechanismSurveyPolicy defaults() {
        return new MechanismSurveyPolicy(12_000, 16.0, 512, 8_192);
    }
}
