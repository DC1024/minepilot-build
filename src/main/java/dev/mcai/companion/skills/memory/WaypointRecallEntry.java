package dev.mcai.companion.skills.memory;

import dev.mcai.companion.waypoint.DimensionRef;
import dev.mcai.companion.waypoint.Waypoint;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded model-facing projection of one durable waypoint.
 *
 * <p>Display text remains explicitly untrusted data. Internal UUIDs and
 * geometry details that are not needed for travel are omitted.</p>
 */
public record WaypointRecallEntry(
    String displayNameUntrusted,
    String categoryUntrusted,
    DimensionRef dimension,
    double x,
    double y,
    double z,
    String geometryType,
    String provenance,
    double confidence,
    long revision,
    Optional<Instant> lastVerifiedAt
) {
    public WaypointRecallEntry {
        displayNameUntrusted = bounded(
            displayNameUntrusted,
            256,
            "displayNameUntrusted"
        );
        categoryUntrusted = bounded(
            categoryUntrusted,
            128,
            "categoryUntrusted"
        );
        Objects.requireNonNull(dimension, "dimension");
        finite(x, "x");
        finite(y, "y");
        finite(z, "z");
        geometryType = bounded(geometryType, 32, "geometryType");
        provenance = bounded(provenance, 64, "provenance");
        if (!Double.isFinite(confidence)
            || confidence < 0.0
            || confidence > 1.0
            || revision < 0) {
            throw new IllegalArgumentException(
                "Waypoint recall metadata is invalid"
            );
        }
        Objects.requireNonNull(lastVerifiedAt, "lastVerifiedAt");
    }

    public static WaypointRecallEntry from(final Waypoint waypoint) {
        Objects.requireNonNull(waypoint, "waypoint");
        final var point = waypoint.geometry().referencePoint();
        return new WaypointRecallEntry(
            waypoint.name(),
            waypoint.category(),
            waypoint.dimension(),
            point.x(),
            point.y(),
            point.z(),
            waypoint.geometry().type().name(),
            waypoint.provenance().name(),
            waypoint.confidence(),
            waypoint.revision(),
            waypoint.lastVerifiedAt()
        );
    }

    private static String bounded(
        final String value,
        final int maximum,
        final String label
    ) {
        final String checked = Objects.requireNonNull(value, label);
        if (checked.isBlank()
            || checked.length() > maximum
            || checked.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return checked;
    }

    private static void finite(
        final double value,
        final String label
    ) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
    }
}
