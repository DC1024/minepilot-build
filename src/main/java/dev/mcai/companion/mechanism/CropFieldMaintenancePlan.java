package dev.mcai.companion.mechanism;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Objects;

/** Immutable work order generated only from retained first-person evidence. */
public record CropFieldMaintenancePlan(
        DimensionRef dimension,
        long sourceRevision,
        CropFieldVariant crop,
        List<Cell> cells
) {
    public CropFieldMaintenancePlan {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(crop, "crop");
        if (sourceRevision < 0) {
            throw new IllegalArgumentException("Invalid source revision");
        }
        cells = List.copyOf(Objects.requireNonNull(cells, "cells"));
        if (cells.isEmpty() || cells.size() > 80
                || cells.stream().map(Cell::cropPosition).distinct().count()
                        != cells.size()) {
            throw new IllegalArgumentException(
                    "Maintenance cells must be 1..80 unique positions"
            );
        }
    }

    public record Cell(
            GridPos cropPosition,
            List<GridPos> workStandSupports
    ) {
        public Cell {
            Objects.requireNonNull(cropPosition, "cropPosition");
            workStandSupports = List.copyOf(Objects.requireNonNull(
                    workStandSupports,
                    "workStandSupports"
            ));
            if (workStandSupports.isEmpty()
                    || workStandSupports.size() > 8) {
                throw new IllegalArgumentException(
                        "A maintenance cell needs 1..8 work stands"
                );
            }
        }
    }
}
