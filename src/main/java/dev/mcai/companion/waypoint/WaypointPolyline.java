package dev.mcai.companion.waypoint;

import java.util.List;
import java.util.Objects;

public record WaypointPolyline(List<WaypointPoint> points) implements WaypointGeometry {
    public static final int MAX_POINTS = 4_096;

    public WaypointPolyline {
        Objects.requireNonNull(points, "points");
        if (points.size() < 2 || points.size() > MAX_POINTS) {
            throw new IllegalArgumentException("Polyline must contain between 2 and " + MAX_POINTS + " points");
        }
        points = List.copyOf(points);
        if (points.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Polyline points must not be null");
        }
        for (int index = 1; index < points.size(); index++) {
            if (points.get(index - 1).equals(points.get(index))) {
                throw new IllegalArgumentException("Polyline must not contain a zero-length segment");
            }
        }
    }

    @Override
    public WaypointGeometryType type() {
        return WaypointGeometryType.POLYLINE;
    }

    @Override
    public WaypointAabb bounds() {
        return WaypointPolygon.boundsOf(points);
    }

    @Override
    public WaypointPoint referencePoint() {
        return points.getFirst();
    }
}
