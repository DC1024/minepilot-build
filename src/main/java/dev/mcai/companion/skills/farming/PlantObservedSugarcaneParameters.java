package dev.mcai.companion.skills.farming;

import dev.mcai.companion.action.BlockFace;
import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

/** One first-person-observed support block on which to plant one sugar cane. */
public record PlantObservedSugarcaneParameters(
        DimensionRef dimension,
        ObservedBlockTarget support
) {
    public PlantObservedSugarcaneParameters {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(support, "support");
        if (support.face() != BlockFace.UP) {
            throw new IllegalArgumentException(
                    "Sugar cane must be planted on the support top"
            );
        }
    }
}
