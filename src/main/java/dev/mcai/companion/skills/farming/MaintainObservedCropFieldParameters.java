package dev.mcai.companion.skills.farming;

import dev.mcai.companion.mechanism.CropFieldMaintenanceRequest;
import dev.mcai.companion.mechanism.CropFieldVariant;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

/** Coordinate-free request for one bounded mature-crop maintenance pass. */
public record MaintainObservedCropFieldParameters(
        DimensionRef dimension,
        CropFieldVariant crop,
        int maximumPlants
) {
    public MaintainObservedCropFieldParameters {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(crop, "crop");
        new CropFieldMaintenanceRequest(crop, maximumPlants);
    }

    public CropFieldMaintenanceRequest request() {
        return new CropFieldMaintenanceRequest(crop, maximumPlants);
    }
}
