package dev.mcai.companion.skills.parkour;

import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

public record ParkourToParameters(
        DimensionRef dimension,
        double x,
        double y,
        double z,
        double arrivalRadius,
        int maxJumps,
        int maxGap
) {
    public ParkourToParameters {
        Objects.requireNonNull(dimension, "dimension");
        if (!Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)
                || !Double.isFinite(arrivalRadius)
                || arrivalRadius < 0.45
                || arrivalRadius > 1.5
                || maxJumps < 1
                || maxJumps > 16
                || maxGap < 1
                || maxGap > 2) {
            throw new IllegalArgumentException("Invalid parkour target");
        }
    }

    public PerceptionVec3 target() {
        return new PerceptionVec3(x, y, z);
    }
}
