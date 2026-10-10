package dev.mcai.companion.skills.memory;

import dev.mcai.companion.waypoint.Waypoint;
import java.util.List;
import java.util.Objects;

/**
 * Goal-scoped handoff from the asynchronous SQLite skill to planner input.
 */
public final class WaypointRecallBuffer {
    private long goalRevision = -1;
    private WaypointRecallSnapshot snapshot =
        WaypointRecallSnapshot.empty();

    public synchronized void publish(
        final long revision,
        final String query,
        final List<Waypoint> waypoints
    ) {
        if (revision < 0) {
            throw new IllegalArgumentException(
                "Goal revision must be non-negative"
            );
        }
        Objects.requireNonNull(query, "query");
        final List<WaypointRecallEntry> entries = Objects.requireNonNull(
            waypoints,
            "waypoints"
        ).stream()
            .limit(WaypointRecallSnapshot.MAXIMUM_MATCHES)
            .map(WaypointRecallEntry::from)
            .toList();
        goalRevision = revision;
        snapshot = new WaypointRecallSnapshot(true, query, entries);
    }

    public synchronized WaypointRecallSnapshot snapshot(
        final long revision
    ) {
        if (revision != goalRevision) {
            return WaypointRecallSnapshot.empty();
        }
        return snapshot;
    }
}
