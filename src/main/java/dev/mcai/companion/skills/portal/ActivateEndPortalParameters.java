package dev.mcai.companion.skills.portal;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

public record ActivateEndPortalParameters(
        DimensionRef dimension,
        int centerX,
        int centerY,
        int centerZ
) {
    private static final int MAXIMUM_COORDINATE = 30_000_000;

    public ActivateEndPortalParameters {
        Objects.requireNonNull(dimension, "dimension");
        if (Math.abs((long) centerX) > MAXIMUM_COORDINATE
                || Math.abs((long) centerZ) > MAXIMUM_COORDINATE
                || centerY < -2_048
                || centerY > 2_048) {
            throw new IllegalArgumentException(
                    "End portal center is outside supported bounds"
            );
        }
    }

    public GridPos center() {
        return new GridPos(centerX, centerY, centerZ);
    }
}
