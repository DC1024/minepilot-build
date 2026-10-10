package dev.mcai.companion.navigation;

import dev.mcai.companion.perception.TopSupportAffordance;
import java.util.Objects;

/**
 * One voxel supplied by the observation boundary. Absence from a snapshot is
 * unknown, never assumed to be air.
 */
public record ObservedVoxel(
    GridPos position,
    VoxelKind kind,
    double danger,
    long observationRevision,
    OccupancyEvidence occupancyEvidence,
    TopSupportAffordance topSupportAffordance
) {
    public ObservedVoxel {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(occupancyEvidence, "occupancyEvidence");
        Objects.requireNonNull(
                topSupportAffordance,
                "topSupportAffordance"
        );
        if (!Double.isFinite(danger) || danger < 0.0 || danger > 1.0) {
            throw new IllegalArgumentException("Danger must be finite and in [0, 1]");
        }
        if (observationRevision < 0) {
            throw new IllegalArgumentException("Observation revision must be non-negative");
        }
    }

    /**
     * Compatibility constructor.  Hand-authored and legacy voxels carry no
     * safety-grade occupancy or support proof until their producer opts in.
     */
    public ObservedVoxel(
            final GridPos position,
            final VoxelKind kind,
            final double danger,
            final long observationRevision
    ) {
        this(
                position,
                kind,
                danger,
                observationRevision,
                OccupancyEvidence.UNKNOWN,
                TopSupportAffordance.UNKNOWN
        );
    }

    public double effectiveDanger() {
        return Math.max(danger, kind.intrinsicDanger());
    }
}
