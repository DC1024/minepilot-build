package dev.mcai.companion.skills.bridging;

import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

public record BridgeToParameters(
        DimensionRef dimension,
        double x,
        double y,
        double z,
        double arrivalRadius,
        int maxBlocks,
        boolean allowObservedAttachment
) {
    public BridgeToParameters(
            final DimensionRef dimension,
            final double x,
            final double y,
            final double z,
            final double arrivalRadius,
            final int maxBlocks
    ) {
        this(
                dimension,
                x,
                y,
                z,
                arrivalRadius,
                maxBlocks,
                false
        );
    }

    public BridgeToParameters {
        Objects.requireNonNull(dimension, "dimension");
        if (!Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)
                || !Double.isFinite(arrivalRadius)
                || arrivalRadius < 0.5
                || arrivalRadius > 2.0
                || maxBlocks < 1
                || maxBlocks > 64) {
            throw new IllegalArgumentException(
                    "Invalid bridge target"
            );
        }
    }

    public PerceptionVec3 target() {
        return new PerceptionVec3(x, y, z);
    }
}
