package dev.mcai.companion.skills.interaction;

import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

public record ConsumeOwnedFoodParameters(
        DimensionRef dimension,
        String itemId
) {
    public ConsumeOwnedFoodParameters {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(itemId, "itemId");
    }
}
