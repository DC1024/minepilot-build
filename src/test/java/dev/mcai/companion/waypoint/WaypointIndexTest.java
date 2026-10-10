package dev.mcai.companion.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

final class WaypointIndexTest {
    private static final UUID WORLD =
        UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID CREATOR =
        UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");

    @Test
    void normalizesAndFindsChineseAndEnglishAliases() {
        final WaypointIndex index = new WaypointIndex();
        index.upsert(waypoint(
            "00000000-0000-0000-0000-000000000001",
            DimensionRef.OVERWORLD,
            "北仓库",
            Set.of("North Warehouse", "主基地储藏室"),
            1.0,
            Optional.empty()
        ));

        assertEquals(
            "北仓库",
            index.search(WORLD, DimensionRef.OVERWORLD, "主基地储藏室", NOW, 10)
                .getFirst().waypoint().name()
        );
        assertEquals(
            "北仓库",
            index.search(WORLD, DimensionRef.OVERWORLD, "ＮＯＲＴＨ　ＷＡＲＥＨＯＵＳＥ", NOW, 10)
                .getFirst().waypoint().name()
        );
    }

    @Test
    void isolatesDimensionsAndExcludesExpiredWaypoints() {
        final WaypointIndex index = new WaypointIndex();
        index.upsert(waypoint(
            "00000000-0000-0000-0000-000000000001",
            DimensionRef.OVERWORLD,
            "传送门",
            Set.of("portal"),
            1.0,
            Optional.empty()
        ));
        index.upsert(waypoint(
            "00000000-0000-0000-0000-000000000002",
            DimensionRef.NETHER,
            "传送门",
            Set.of("portal"),
            1.0,
            Optional.empty()
        ));
        index.upsert(waypoint(
            "00000000-0000-0000-0000-000000000003",
            DimensionRef.OVERWORLD,
            "旧仓库",
            Set.of("expired storage"),
            1.0,
            Optional.of(Duration.ofSeconds(10))
        ));

        final List<WaypointSearchResult> overworld =
            index.search(WORLD, DimensionRef.OVERWORLD, "传送门", NOW.plusSeconds(20), 10);
        final List<WaypointSearchResult> nether =
            index.search(WORLD, DimensionRef.NETHER, "传送门", NOW.plusSeconds(20), 10);

        assertEquals(1, overworld.size());
        assertEquals(DimensionRef.OVERWORLD, overworld.getFirst().waypoint().dimension());
        assertEquals(1, nether.size());
        assertEquals(DimensionRef.NETHER, nether.getFirst().waypoint().dimension());
        assertTrue(
            index.search(WORLD, DimensionRef.OVERWORLD, "expired storage", NOW.plusSeconds(10), 10)
                .stream()
                .noneMatch(result -> result.waypoint().id().equals(
                    UUID.fromString("00000000-0000-0000-0000-000000000003")
                ))
        );
    }

    @Test
    void returnsStableOrderingIndependentOfInsertionOrder() {
        final Waypoint first = waypoint(
            "00000000-0000-0000-0000-000000000001",
            DimensionRef.OVERWORLD,
            "仓库",
            Set.of("storage"),
            0.8,
            Optional.empty()
        );
        final Waypoint second = waypoint(
            "00000000-0000-0000-0000-000000000002",
            DimensionRef.OVERWORLD,
            "仓库",
            Set.of("storage"),
            0.8,
            Optional.empty()
        );

        final WaypointIndex forward = new WaypointIndex();
        forward.upsert(first);
        forward.upsert(second);
        final WaypointIndex reverse = new WaypointIndex();
        reverse.upsert(second);
        reverse.upsert(first);

        final List<UUID> forwardIds = ids(forward.search(
            WORLD,
            DimensionRef.OVERWORLD,
            "仓库",
            NOW,
            10
        ));
        final List<UUID> reverseIds = ids(reverse.search(
            WORLD,
            DimensionRef.OVERWORLD,
            "仓库",
            NOW,
            10
        ));

        assertEquals(forwardIds, reverseIds);
        assertEquals(List.of(first.id(), second.id()), forwardIds);
    }

    @Test
    void rejectsConflictingEqualRevisions() {
        final WaypointIndex index = new WaypointIndex();
        final Waypoint original = waypoint(
            "00000000-0000-0000-0000-000000000001",
            DimensionRef.OVERWORLD,
            "仓库",
            Set.of(),
            1.0,
            Optional.empty()
        );
        index.upsert(original);

        final Waypoint conflict = new Waypoint(
            original.id(),
            original.worldId(),
            original.dimension(),
            original.geometry(),
            "另一个名字",
            original.aliases(),
            original.category(),
            original.creatorId(),
            original.source(),
            original.provenance(),
            original.confidence(),
            original.revision(),
            original.status(),
            original.createdAt(),
            original.updatedAt(),
            original.lastVerifiedAt(),
            original.ttl()
        );
        assertThrows(IllegalArgumentException.class, () -> index.upsert(conflict));
    }

    private static List<UUID> ids(List<WaypointSearchResult> results) {
        return results.stream().map(result -> result.waypoint().id()).toList();
    }

    private static Waypoint waypoint(
        String id,
        DimensionRef dimension,
        String name,
        Set<String> aliases,
        double confidence,
        Optional<Duration> ttl
    ) {
        return new Waypoint(
            UUID.fromString(id),
            WORLD,
            dimension,
            new WaypointPoint(10.0, 64.0, -5.0),
            name,
            aliases,
            "storage",
            CREATOR,
            "test",
            WaypointProvenance.HUMAN_EXPLICIT,
            confidence,
            0,
            WaypointStatus.ACTIVE,
            NOW,
            NOW,
            Optional.empty(),
            ttl
        );
    }
}
