package dev.mcai.companion.skills.portal;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

public record CastObservedNetherPortalParameters(
        DimensionRef dimension,
        long sampleSequence,
        GridPos anchor,
        PortalBuildAxis axis,
        PortalCastOperation operation,
        OptionalInt frameIndex,
        Optional<GridPos> lavaSource
) {
    public static final int MINIMUM_FRAME_BLOCKS = 10;

    public CastObservedNetherPortalParameters {
        Objects.requireNonNull(dimension, "dimension");
        if (sampleSequence < 0) {
            throw new IllegalArgumentException(
                    "sampleSequence must be non-negative"
            );
        }
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(axis, "axis");
        Objects.requireNonNull(operation, "operation");
        frameIndex = Objects.requireNonNull(frameIndex, "frameIndex");
        lavaSource = Objects.requireNonNull(lavaSource, "lavaSource");
        if (axis == PortalBuildAxis.AUTO) {
            throw new IllegalArgumentException(
                    "Casting requires an explicit axis"
            );
        }
        if (operation == PortalCastOperation.CAST_NEXT) {
            if (frameIndex.isEmpty()
                    || frameIndex.orElseThrow() < 0
                    || frameIndex.orElseThrow()
                        >= MINIMUM_FRAME_BLOCKS
                    || lavaSource.isEmpty()) {
                throw new IllegalArgumentException(
                        "cast_next requires a frame index and lava source"
                );
            }
        } else if (frameIndex.isPresent() || lavaSource.isPresent()) {
            throw new IllegalArgumentException(
                    "light does not accept casting targets"
            );
        }
        if (outsideWorld(anchor)
                || lavaSource.filter(
                    CastObservedNetherPortalParameters::outsideWorld
                ).isPresent()) {
            throw new IllegalArgumentException(
                    "Portal cast coordinates are outside their bound"
            );
        }
    }

    private static boolean outsideWorld(final GridPos position) {
        return Math.abs((long) position.x()) > 29_999_984L
                || Math.abs((long) position.z()) > 29_999_984L
                || position.y() < -2_048
                || position.y() > 2_048;
    }
}
