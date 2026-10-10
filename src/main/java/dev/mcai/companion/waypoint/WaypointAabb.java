package dev.mcai.companion.waypoint;

import java.util.Objects;

public record WaypointAabb(WaypointPoint minimum, WaypointPoint maximum) implements WaypointGeometry {
    public WaypointAabb {
        Objects.requireNonNull(minimum, "minimum");
        Objects.requireNonNull(maximum, "maximum");
        if (minimum.x() > maximum.x()
            || minimum.y() > maximum.y()
            || minimum.z() > maximum.z()) {
            throw new IllegalArgumentException("AABB minimum must not exceed maximum");
        }
    }

    @Override
    public WaypointGeometryType type() {
        return WaypointGeometryType.AABB;
    }

    @Override
    public WaypointAabb bounds() {
        return this;
    }

    @Override
    public WaypointPoint referencePoint() {
        return new WaypointPoint(
            midpoint(minimum.x(), maximum.x()),
            midpoint(minimum.y(), maximum.y()),
            midpoint(minimum.z(), maximum.z())
        );
    }

    private static double midpoint(double first, double second) {
        return first + (second - first) / 2.0;
    }
}
