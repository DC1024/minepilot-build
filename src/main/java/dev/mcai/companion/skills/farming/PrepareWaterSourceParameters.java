package dev.mcai.companion.skills.farming;

import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

/** One fairly observed surface to excavate and fill from an owned bucket. */
public record PrepareWaterSourceParameters(
        DimensionRef dimension,
        ObservedBlockTarget ground
) {
    public PrepareWaterSourceParameters {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(ground, "ground");
    }
}
