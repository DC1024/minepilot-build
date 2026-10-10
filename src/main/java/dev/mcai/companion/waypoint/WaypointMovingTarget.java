package dev.mcai.companion.waypoint;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record WaypointMovingTarget(
    UUID targetId,
    WaypointPoint lastKnownPosition,
    Instant observedAt
) implements WaypointGeometry {
    public WaypointMovingTarget {
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(lastKnownPosition, "lastKnownPosition");
        Objects.requireNonNull(observedAt, "observedAt");
    }

    @Override
    public WaypointGeometryType type() {
        return WaypointGeometryType.MOVING_TARGET;
    }

    @Override
    public WaypointAabb bounds() {
        return lastKnownPosition.bounds();
    }

    @Override
    public WaypointPoint referencePoint() {
        return lastKnownPosition;
    }
}
