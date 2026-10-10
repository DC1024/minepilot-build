package dev.mcai.companion.mechanism;

import java.util.Objects;

/** Coordinate-free request for one bounded crop-field maintenance pass. */
public record CropFieldMaintenanceRequest(
        CropFieldVariant crop,
        int maximumPlants
) {
    public CropFieldMaintenanceRequest {
        Objects.requireNonNull(crop, "crop");
        if (maximumPlants < 1 || maximumPlants > 80) {
            throw new IllegalArgumentException(
                    "Crop maintenance must request 1..80 plants"
            );
        }
    }
}
