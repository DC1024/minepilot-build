package dev.mcai.companion.skills.memory;

import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

public record RecallWaypointParameters(
    DimensionRef dimension,
    String query
) {
    public RecallWaypointParameters {
        Objects.requireNonNull(dimension, "dimension");
        query = Objects.requireNonNull(query, "query").strip();
        if (query.isEmpty()
            || query.length() > 128
            || query.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                "Waypoint query is invalid"
            );
        }
    }
}
