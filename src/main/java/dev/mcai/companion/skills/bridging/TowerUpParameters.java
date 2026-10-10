package dev.mcai.companion.skills.bridging;

import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

public record TowerUpParameters(
        DimensionRef dimension,
        double targetY,
        double arrivalTolerance,
        int maxBlocks
) {
    public TowerUpParameters {
        Objects.requireNonNull(dimension, "dimension");
        if (!Double.isFinite(targetY)
                || targetY < -2_048.0
                || targetY > 2_048.0
                || !Double.isFinite(arrivalTolerance)
                || arrivalTolerance < 0.1
                || arrivalTolerance > 0.75
                || maxBlocks < 1
                || maxBlocks > 32) {
            throw new IllegalArgumentException(
                    "Invalid tower target"
            );
        }
    }
}
