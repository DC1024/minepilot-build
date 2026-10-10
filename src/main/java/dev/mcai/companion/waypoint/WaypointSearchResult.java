package dev.mcai.companion.waypoint;

import java.util.Objects;

public record WaypointSearchResult(Waypoint waypoint, double score) {
    public WaypointSearchResult {
        Objects.requireNonNull(waypoint, "waypoint");
        if (!Double.isFinite(score) || score <= 0.0) {
            throw new IllegalArgumentException("Search score must be finite and positive");
        }
    }
}
