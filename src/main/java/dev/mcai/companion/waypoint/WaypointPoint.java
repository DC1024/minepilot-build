package dev.mcai.companion.waypoint;

public record WaypointPoint(double x, double y, double z) implements WaypointGeometry {
    public static final double MAX_ABSOLUTE_COORDINATE = 30_000_000.0;

    public WaypointPoint {
        x = validateCoordinate(x, "x");
        y = validateCoordinate(y, "y");
        z = validateCoordinate(z, "z");
    }

    @Override
    public WaypointGeometryType type() {
        return WaypointGeometryType.POINT;
    }

    @Override
    public WaypointAabb bounds() {
        return new WaypointAabb(this, this);
    }

    @Override
    public WaypointPoint referencePoint() {
        return this;
    }

    public double distanceSquared(WaypointPoint other) {
        if (other == null) {
            throw new IllegalArgumentException("Other point must not be null");
        }
        final double dx = x - other.x;
        final double dy = y - other.y;
        final double dz = z - other.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double validateCoordinate(double value, String label) {
        if (!Double.isFinite(value) || Math.abs(value) > MAX_ABSOLUTE_COORDINATE) {
            throw new IllegalArgumentException("Invalid " + label + " coordinate");
        }
        return value == 0.0 ? 0.0 : value;
    }
}
