package dev.mcai.companion.skills.memory;

import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

public record ObservedCurrentPosition(
    DimensionRef dimension,
    double x,
    double y,
    double z,
    long sessionGeneration
) {
    public ObservedCurrentPosition {
        Objects.requireNonNull(dimension, "dimension");
        if (!Double.isFinite(x)
            || !Double.isFinite(y)
            || !Double.isFinite(z)
            || sessionGeneration < 0) {
            throw new IllegalArgumentException(
                "Observed current position is invalid"
            );
        }
    }
}
