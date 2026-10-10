package dev.mcai.companion.skills.interaction;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

public record UseBlockParameters(
        DimensionRef dimension,
        ObservedBlockTarget target,
        ActionHand hand
) {
    public UseBlockParameters {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(hand, "hand");
    }
}
