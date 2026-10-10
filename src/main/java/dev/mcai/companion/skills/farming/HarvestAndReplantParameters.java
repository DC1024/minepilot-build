package dev.mcai.companion.skills.farming;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.skills.interaction.ObservedBlockTarget;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;
import java.util.Set;

public record HarvestAndReplantParameters(
        DimensionRef dimension,
        CropKind crop,
        ObservedBlockTarget target,
        Set<GridPos> authorizedPickupCells
) {
    public HarvestAndReplantParameters {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(crop, "crop");
        Objects.requireNonNull(target, "target");
        authorizedPickupCells = Set.copyOf(
                Objects.requireNonNull(
                        authorizedPickupCells,
                        "authorizedPickupCells"
                )
        );
    }

    public HarvestAndReplantParameters(
            final DimensionRef dimension,
            final CropKind crop,
            final ObservedBlockTarget target
    ) {
        this(dimension, crop, target, Set.of());
    }
}
