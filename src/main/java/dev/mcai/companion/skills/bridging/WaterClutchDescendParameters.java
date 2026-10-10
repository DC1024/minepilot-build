package dev.mcai.companion.skills.bridging;

import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

/**
 * One deliberately bounded water-clutch descent to a previously observed
 * landing cell.
 */
public record WaterClutchDescendParameters(
        DimensionRef dimension,
        double x,
        double y,
        double z,
        double arrivalRadius,
        int maximumDropBlocks
) {
    public WaterClutchDescendParameters {
        Objects.requireNonNull(dimension, "dimension");
        if (!Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000.0
                || y < -2_048.0
                || y > 2_048.0
                || Math.abs(z) > 30_000_000.0
                || !Double.isFinite(arrivalRadius)
                || arrivalRadius < 0.25
                || arrivalRadius > 0.9
                || maximumDropBlocks < 4
                || maximumDropBlocks > 32) {
            throw new IllegalArgumentException(
                    "Invalid water-clutch descent target"
            );
        }
    }
}
