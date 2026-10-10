package dev.mcai.companion.waypoint;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

final class WaypointTest {
    @Test
    void computesTtlFromLastVerificationAndDetectsDanger() {
        final Instant created = Instant.parse("2026-07-24T00:00:00Z");
        final Waypoint waypoint = waypoint(
            WaypointStatus.DANGEROUS,
            created,
            Optional.of(created.plusSeconds(10)),
            Optional.of(Duration.ofSeconds(30))
        );

        assertTrue(waypoint.isDangerous());
        assertFalse(waypoint.isExpired(created.plusSeconds(39)));
        assertTrue(waypoint.isExpired(created.plusSeconds(40)));
        assertFalse(waypoint.isSearchableAt(created.plusSeconds(40)));
    }

    @Test
    void defensivelyCopiesAliases() {
        final Set<String> aliases = new LinkedHashSet<>();
        aliases.add("North Warehouse");
        final Instant created = Instant.parse("2026-07-24T00:00:00Z");
        final Waypoint waypoint = new Waypoint(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
            DimensionRef.OVERWORLD,
            new WaypointPoint(1.0, 64.0, 2.0),
            "北仓库",
            aliases,
            "storage",
            UUID.fromString("20000000-0000-0000-0000-000000000001"),
            "test",
            WaypointProvenance.HUMAN_EXPLICIT,
            1.0,
            0,
            WaypointStatus.ACTIVE,
            created,
            created,
            Optional.empty(),
            Optional.empty()
        );

        aliases.add("Mutated");
        assertFalse(waypoint.aliases().contains("Mutated"));
        assertThrows(UnsupportedOperationException.class, () -> waypoint.aliases().add("No"));
    }

    @Test
    void rejectsInvalidTtlAndConfidence() {
        final Instant created = Instant.parse("2026-07-24T00:00:00Z");
        assertThrows(
            IllegalArgumentException.class,
            () -> waypoint(
                WaypointStatus.ACTIVE,
                created,
                Optional.empty(),
                Optional.of(Duration.ZERO)
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new Waypoint(
                UUID.randomUUID(),
                UUID.randomUUID(),
                DimensionRef.OVERWORLD,
                new WaypointPoint(0.0, 64.0, 0.0),
                "home",
                Set.of(),
                "base",
                UUID.randomUUID(),
                "test",
                WaypointProvenance.AI_DIRECT_OBSERVATION,
                Double.NaN,
                0,
                WaypointStatus.ACTIVE,
                created,
                created,
                Optional.empty(),
                Optional.empty()
            )
        );
    }

    private static Waypoint waypoint(
        WaypointStatus status,
        Instant created,
        Optional<Instant> lastVerified,
        Optional<Duration> ttl
    ) {
        final Instant updated = lastVerified.orElse(created);
        return new Waypoint(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("10000000-0000-0000-0000-000000000001"),
            DimensionRef.OVERWORLD,
            new WaypointPoint(1.0, 64.0, 2.0),
            "home",
            Set.of("base"),
            "base",
            UUID.fromString("20000000-0000-0000-0000-000000000001"),
            "test",
            WaypointProvenance.AI_DIRECT_OBSERVATION,
            0.9,
            0,
            status,
            created,
            updated,
            lastVerified,
            ttl
        );
    }
}
