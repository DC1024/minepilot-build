package dev.mcai.companion.skills.transport;

import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;
import java.util.regex.Pattern;

public record EnterObservedBoatParameters(
        DimensionRef dimension,
        long sampleSequence,
        String observationId
) {
    private static final Pattern OBSERVATION_ID =
            Pattern.compile("visible-(?:0|[1-9][0-9]{0,5})");

    public EnterObservedBoatParameters {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(observationId, "observationId");
        if (sampleSequence < 0
                || !OBSERVATION_ID.matcher(observationId).matches()) {
            throw new IllegalArgumentException(
                    "Invalid semantic boat reference"
            );
        }
    }

    public int observationIndex() {
        return Integer.parseInt(
                observationId.substring("visible-".length())
        );
    }
}
