package dev.mcai.companion.mechanism;

import java.util.Objects;

/** High-level scale/material request; no coordinate or block array. */
public record HydratedCropFieldRequest(
        CropFieldVariant crop,
        int minimumPlots,
        boolean requireSingleChunk
) {
    public HydratedCropFieldRequest {
        Objects.requireNonNull(crop, "crop");
        if (minimumPlots < 8 || minimumPlots > 80) {
            throw new IllegalArgumentException(
                    "Hydrated crop field must request 8..80 plots"
            );
        }
    }
}
