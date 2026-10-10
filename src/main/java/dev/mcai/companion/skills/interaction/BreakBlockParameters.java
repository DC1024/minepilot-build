package dev.mcai.companion.skills.interaction;

import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

public record BreakBlockParameters(
        DimensionRef dimension,
        ObservedBlockTarget target
) {
    public BreakBlockParameters {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(target, "target");
    }
}
