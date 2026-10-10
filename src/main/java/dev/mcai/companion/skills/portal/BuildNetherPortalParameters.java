package dev.mcai.companion.skills.portal;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

public record BuildNetherPortalParameters(
        DimensionRef dimension,
        int x,
        int y,
        int z,
        PortalBuildAxis axis
) {
    public BuildNetherPortalParameters {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(axis, "axis");
        if (Math.abs((long) x) > 29_999_984L
                || Math.abs((long) z) > 29_999_984L
                || y < -2_048
                || y > 2_048) {
            throw new IllegalArgumentException(
                    "Portal anchor is outside its bound"
            );
        }
    }

    public GridPos anchor() {
        return new GridPos(x, y, z);
    }
}
