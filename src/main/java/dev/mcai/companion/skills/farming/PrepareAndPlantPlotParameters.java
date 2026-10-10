package dev.mcai.companion.skills.farming;

import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

/** One fairly observed bare plot to till and plant. */
public record PrepareAndPlantPlotParameters(
        DimensionRef dimension,
        CropKind crop,
        ObservedBlockTarget ground
) {
    public PrepareAndPlantPlotParameters {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(crop, "crop");
        Objects.requireNonNull(ground, "ground");
        if (!"minecraft:farmland".equals(crop.substrateBlockId())) {
            throw new IllegalArgumentException(
                    "Only hydrated farmland crops can prepare a plot"
            );
        }
    }
}
