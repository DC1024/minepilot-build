package dev.mcai.companion.skills.sleeping;

import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

/**
 * The companion's own server-authoritative respawn position.
 */
public record SleepRespawnPoint(
        DimensionRef dimension,
        BlockCoordinate block
) {
    public SleepRespawnPoint {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(block, "block");
    }
}
