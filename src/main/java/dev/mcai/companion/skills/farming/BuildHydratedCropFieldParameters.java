package dev.mcai.companion.skills.farming;

import dev.mcai.companion.mechanism.CropFieldVariant;
import dev.mcai.companion.mechanism.HydratedCropFieldRequest;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

/** Scale/material request for a field whose layout is generated on site. */
public record BuildHydratedCropFieldParameters(
        DimensionRef dimension,
        CropFieldVariant crop,
        int minimumPlots,
        boolean requireSingleChunk
) {
    public BuildHydratedCropFieldParameters {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(crop, "crop");
        // Reuse the mechanism contract as the single range authority.
        new HydratedCropFieldRequest(
                crop,
                minimumPlots,
                requireSingleChunk
        );
    }

    public HydratedCropFieldRequest request() {
        return new HydratedCropFieldRequest(
                crop,
                minimumPlots,
                requireSingleChunk
        );
    }
}
