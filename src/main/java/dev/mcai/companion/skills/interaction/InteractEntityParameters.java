package dev.mcai.companion.skills.interaction;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;
import java.util.regex.Pattern;

public record InteractEntityParameters(
        DimensionRef dimension,
        long sampleSequence,
        String observationId,
        ActionHand hand
) {
    private static final Pattern OBSERVATION_ID =
            Pattern.compile("visible-(?:0|[1-9][0-9]{0,5})");

    public InteractEntityParameters {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(observationId, "observationId");
        Objects.requireNonNull(hand, "hand");
        if (sampleSequence < 0
                || !OBSERVATION_ID.matcher(observationId).matches()) {
            throw new IllegalArgumentException(
                    "Invalid semantic entity reference"
            );
        }
    }

    AttackEntityParameters observedTarget() {
        return new AttackEntityParameters(
                dimension,
                sampleSequence,
                observationId
        );
    }
}
