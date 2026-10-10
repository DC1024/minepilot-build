package dev.mcai.companion.waypoint;

public sealed interface WaypointGeometry permits
    WaypointPoint,
    WaypointAabb,
    WaypointPolygon,
    WaypointPolyline,
    WaypointMovingTarget {

    WaypointGeometryType type();

    WaypointAabb bounds();

    WaypointPoint referencePoint();
}
