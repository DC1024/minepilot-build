package dev.mcai.companion.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

final class WaypointGeometryTest {
    @Test
    void validatesFiniteCoordinatesAndAabbOrdering() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new WaypointPoint(Double.NaN, 64.0, 0.0)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new WaypointPoint(WaypointPoint.MAX_ABSOLUTE_COORDINATE + 1.0, 64.0, 0.0)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new WaypointAabb(
                new WaypointPoint(10.0, 64.0, 10.0),
                new WaypointPoint(0.0, 70.0, 20.0)
            )
        );
    }

    @Test
    void validatesPolygonAndPolylinePointCountsAndShape() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new WaypointPolygon(List.of(
                new WaypointPoint(0.0, 64.0, 0.0),
                new WaypointPoint(1.0, 64.0, 1.0)
            ))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new WaypointPolygon(List.of(
                new WaypointPoint(0.0, 64.0, 0.0),
                new WaypointPoint(1.0, 64.0, 1.0),
                new WaypointPoint(2.0, 64.0, 2.0)
            ))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new WaypointPolyline(List.of(new WaypointPoint(0.0, 64.0, 0.0)))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new WaypointPolyline(List.of(
                new WaypointPoint(0.0, 64.0, 0.0),
                new WaypointPoint(0.0, 64.0, 0.0)
            ))
        );
    }

    @Test
    void exposesStrongGeometryTypesAndBounds() {
        final WaypointPolygon polygon = new WaypointPolygon(List.of(
            new WaypointPoint(0.0, 64.0, 0.0),
            new WaypointPoint(4.0, 64.0, 0.0),
            new WaypointPoint(4.0, 64.0, 3.0)
        ));
        assertEquals(WaypointGeometryType.POLYGON, polygon.type());
        assertEquals(new WaypointPoint(0.0, 64.0, 0.0), polygon.bounds().minimum());
        assertEquals(new WaypointPoint(4.0, 64.0, 3.0), polygon.bounds().maximum());

        final WaypointMovingTarget target = new WaypointMovingTarget(
            UUID.fromString("00000000-0000-0000-0000-000000000010"),
            new WaypointPoint(8.0, 70.0, -3.0),
            Instant.parse("2026-07-24T00:00:00Z")
        );
        assertEquals(WaypointGeometryType.MOVING_TARGET, target.type());
        assertEquals(target.lastKnownPosition(), target.referencePoint());
    }
}
