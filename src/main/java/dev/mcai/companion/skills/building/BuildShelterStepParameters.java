package dev.mcai.companion.skills.building;

import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

public record BuildShelterStepParameters(
        DimensionRef dimension,
        long sampleSequence,
        ShelterScale scale
) {
    public BuildShelterStepParameters {
        Objects.requireNonNull(dimension, "dimension");
        if (sampleSequence < 0) {
            throw new IllegalArgumentException(
                    "sampleSequence must be non-negative"
            );
        }
        Objects.requireNonNull(scale, "scale");
    }
}
