package dev.mcai.companion.skills.sleeping;

import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

public record SleepInObservedBedParameters(
        DimensionRef dimension,
        ObservedBlockTarget target
) {
    public SleepInObservedBedParameters {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(target, "target");
    }
}
