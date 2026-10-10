package dev.mcai.companion.waypoint;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record WaypointPolygon(List<WaypointPoint> vertices) implements WaypointGeometry {
    public static final int MAX_VERTICES = 4_096;
    private static final double MINIMUM_AREA_VECTOR_SQUARED = 1.0e-12;

    public WaypointPolygon {
        Objects.requireNonNull(vertices, "vertices");
        if (vertices.size() < 3 || vertices.size() > MAX_VERTICES) {
            throw new IllegalArgumentException("Polygon must contain between 3 and " + MAX_VERTICES + " vertices");
        }
        vertices = List.copyOf(vertices);
        if (vertices.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Polygon vertices must not be null");
        }
        final Set<WaypointPoint> distinct = new HashSet<>(vertices);
        if (distinct.size() != vertices.size()) {
            throw new IllegalArgumentException("Polygon must not contain duplicate vertices");
        }
        if (!hasNonCollinearVertices(vertices)) {
            throw new IllegalArgumentException("Polygon vertices must enclose a non-zero area");
        }
    }

    @Override
    public WaypointGeometryType type() {
        return WaypointGeometryType.POLYGON;
    }

    @Override
    public WaypointAabb bounds() {
        return boundsOf(vertices);
    }

    @Override
    public WaypointPoint referencePoint() {
        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
        for (WaypointPoint vertex : vertices) {
            x += vertex.x();
            y += vertex.y();
            z += vertex.z();
        }
        return new WaypointPoint(x / vertices.size(), y / vertices.size(), z / vertices.size());
    }

    private static boolean hasNonCollinearVertices(List<WaypointPoint> points) {
        final WaypointPoint origin = points.getFirst();
        for (int firstIndex = 1; firstIndex < points.size() - 1; firstIndex++) {
            final WaypointPoint first = points.get(firstIndex);
            final double ax = first.x() - origin.x();
            final double ay = first.y() - origin.y();
            final double az = first.z() - origin.z();
            for (int secondIndex = firstIndex + 1; secondIndex < points.size(); secondIndex++) {
                final WaypointPoint second = points.get(secondIndex);
                final double bx = second.x() - origin.x();
                final double by = second.y() - origin.y();
                final double bz = second.z() - origin.z();
                final double crossX = ay * bz - az * by;
                final double crossY = az * bx - ax * bz;
                final double crossZ = ax * by - ay * bx;
                final double areaVectorSquared =
                    crossX * crossX + crossY * crossY + crossZ * crossZ;
                if (areaVectorSquared > MINIMUM_AREA_VECTOR_SQUARED) {
                    return true;
                }
            }
        }
        return false;
    }

    static WaypointAabb boundsOf(List<WaypointPoint> points) {
        double minimumX = Double.POSITIVE_INFINITY;
        double minimumY = Double.POSITIVE_INFINITY;
        double minimumZ = Double.POSITIVE_INFINITY;
        double maximumX = Double.NEGATIVE_INFINITY;
        double maximumY = Double.NEGATIVE_INFINITY;
        double maximumZ = Double.NEGATIVE_INFINITY;
        for (WaypointPoint point : points) {
            minimumX = Math.min(minimumX, point.x());
            minimumY = Math.min(minimumY, point.y());
            minimumZ = Math.min(minimumZ, point.z());
            maximumX = Math.max(maximumX, point.x());
            maximumY = Math.max(maximumY, point.y());
            maximumZ = Math.max(maximumZ, point.z());
        }
        return new WaypointAabb(
            new WaypointPoint(minimumX, minimumY, minimumZ),
            new WaypointPoint(maximumX, maximumY, maximumZ)
        );
    }
}
