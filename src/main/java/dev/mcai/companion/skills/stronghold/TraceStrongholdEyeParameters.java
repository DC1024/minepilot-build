package dev.mcai.companion.skills.stronghold;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

public record TraceStrongholdEyeParameters(
        DimensionRef dimension,
        long sampleSequence,
        ActionHand hand
) {
    public TraceStrongholdEyeParameters {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(hand, "hand");
        if (sampleSequence < 0) {
            throw new IllegalArgumentException(
                    "sampleSequence must be non-negative"
            );
        }
    }
}
