package dev.mcai.companion.memory.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.mcai.companion.waypoint.DimensionRef;
import dev.mcai.companion.waypoint.Waypoint;
import dev.mcai.companion.waypoint.WaypointAabb;
import dev.mcai.companion.waypoint.WaypointGeometry;
import dev.mcai.companion.waypoint.WaypointMovingTarget;
import dev.mcai.companion.waypoint.WaypointPoint;
import dev.mcai.companion.waypoint.WaypointPolygon;
import dev.mcai.companion.waypoint.WaypointPolyline;
import dev.mcai.companion.waypoint.WaypointProvenance;
import dev.mcai.companion.waypoint.WaypointStatus;

final class WaypointJsonCodecTest {
    private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");
    private final WaypointJsonCodec codec = new WaypointJsonCodec();

    @Test
    void roundTripsEveryStrongGeometryType() {
        final List<WaypointGeometry> geometries = List.of(
            new WaypointPoint(1.25, 64.0, -8.5),
            new WaypointAabb(
                new WaypointPoint(-4.0, 60.0, -4.0),
                new WaypointPoint(4.0, 72.0, 4.0)
            ),
            new WaypointPolygon(List.of(
                new WaypointPoint(0.0, 64.0, 0.0),
                new WaypointPoint(8.0, 64.0, 0.0),
                new WaypointPoint(8.0, 64.0, 6.0)
            )),
            new WaypointPolyline(List.of(
                new WaypointPoint(0.0, 64.0, 0.0),
                new WaypointPoint(10.0, 65.0, 2.0),
                new WaypointPoint(20.0, 64.0, 4.0)
            )),
            new WaypointMovingTarget(
                UUID.fromString("30000000-0000-0000-0000-000000000001"),
                new WaypointPoint(25.0, 70.0, -3.0),
                NOW
            )
        );

        for (int index = 0; index < geometries.size(); index++) {
            final Waypoint original = waypoint(
                UUID.fromString("00000000-0000-0000-0000-00000000000" + (index + 1)),
                geometries.get(index),
                index
            );
            assertEquals(original, codec.decode(codec.encode(original)));
            assertEquals(
                original.geometry(),
                codec.decodeGeometry(codec.encodeGeometry(original.geometry()))
            );
        }
    }

    @Test
    void rejectsUnknownSchemaAndMalformedGeometry() {
        final Waypoint original = waypoint(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            new WaypointPoint(1.0, 64.0, 2.0),
            0
        );
        final String unknownSchema = codec.encode(original)
            .replace("\"schemaVersion\":1", "\"schemaVersion\":2");

        assertThrows(WaypointCodecException.class, () -> codec.decode(unknownSchema));
        assertThrows(
            WaypointCodecException.class,
            () -> codec.decodeGeometry("""
                {"type":"AABB","minimum":{"x":5,"y":64,"z":0},"maximum":{"x":1,"y":70,"z":3}}
                """)
        );
    }

    private static Waypoint waypoint(UUID id, WaypointGeometry geometry, long revision) {
        return new Waypoint(
            id,
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
            DimensionRef.OVERWORLD,
            geometry,
            "北仓库",
            Set.of("North Warehouse", "主仓库"),
            "storage",
            UUID.fromString("20000000-0000-0000-0000-000000000001"),
            "test-suite",
            WaypointProvenance.HUMAN_EXPLICIT,
            0.91,
            revision,
            WaypointStatus.ACTIVE,
            NOW,
            NOW.plusSeconds(revision),
            Optional.of(NOW),
            Optional.of(Duration.ofHours(6))
        );
    }
}
