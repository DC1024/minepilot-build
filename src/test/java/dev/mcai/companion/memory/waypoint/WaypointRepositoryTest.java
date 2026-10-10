package dev.mcai.companion.memory.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mcai.companion.memory.MemoryDatabase;
import dev.mcai.companion.waypoint.DimensionRef;
import dev.mcai.companion.waypoint.Waypoint;
import dev.mcai.companion.waypoint.WaypointAabb;
import dev.mcai.companion.waypoint.WaypointGeometry;
import dev.mcai.companion.waypoint.WaypointPoint;
import dev.mcai.companion.waypoint.WaypointPolygon;
import dev.mcai.companion.waypoint.WaypointProvenance;
import dev.mcai.companion.waypoint.WaypointStatus;

final class WaypointRepositoryTest {
    private static final UUID WORLD_A =
        UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID WORLD_B =
        UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID CREATOR =
        UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void searchesChineseAndEnglishWhileIsolatingWorldAndDimension() throws Exception {
        try (MemoryDatabase database = open("search.db")) {
            final WaypointRepository repository = database.waypoints();
            await(repository.upsert(waypoint(
                "00000000-0000-0000-0000-000000000001",
                WORLD_A,
                DimensionRef.OVERWORLD,
                new WaypointPoint(0.0, 64.0, 0.0),
                "北仓库",
                Set.of("North Warehouse", "主基地储藏室"),
                0,
                Optional.empty()
            )));
            await(repository.upsert(waypoint(
                "00000000-0000-0000-0000-000000000002",
                WORLD_A,
                DimensionRef.NETHER,
                new WaypointPoint(0.0, 64.0, 0.0),
                "北仓库",
                Set.of("North Warehouse", "主基地储藏室"),
                0,
                Optional.empty()
            )));
            await(repository.upsert(waypoint(
                "00000000-0000-0000-0000-000000000003",
                WORLD_B,
                DimensionRef.OVERWORLD,
                new WaypointPoint(0.0, 64.0, 0.0),
                "北仓库",
                Set.of("North Warehouse", "主基地储藏室"),
                0,
                Optional.empty()
            )));

            assertEquals(
                List.of(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                ids(await(repository.searchByName(
                    WORLD_A,
                    DimensionRef.OVERWORLD,
                    "主基地储藏室",
                    NOW,
                    10
                )))
            );
            assertEquals(
                List.of(UUID.fromString("00000000-0000-0000-0000-000000000001")),
                ids(await(repository.searchByName(
                    WORLD_A,
                    DimensionRef.OVERWORLD,
                    "ＮＯＲＴＨ　ＷＡＲＥＨＯＵＳＥ",
                    NOW,
                    10
                )))
            );
            assertEquals(
                List.of(),
                await(repository.searchByName(
                    WORLD_A,
                    DimensionRef.OVERWORLD,
                    "' OR 1=1; DROP TABLE waypoint; --",
                    NOW,
                    10
                ))
            );
            assertTrue((await(repository.findById(
                UUID.fromString("00000000-0000-0000-0000-000000000001")
            ))).isPresent());
        }
    }

    @Test
    void updateAtomicallyReplacesFtsAndRtreeEntries() throws Exception {
        try (MemoryDatabase database = open("update.db")) {
            final WaypointRepository repository = database.waypoints();
            final UUID id = UUID.fromString("00000000-0000-0000-0000-000000000010");
            final Waypoint original = waypoint(
                id.toString(),
                WORLD_A,
                DimensionRef.OVERWORLD,
                new WaypointPoint(0.0, 64.0, 0.0),
                "旧补给站",
                Set.of("legacy depot"),
                0,
                Optional.empty()
            );
            final Waypoint updated = new Waypoint(
                original.id(),
                original.worldId(),
                original.dimension(),
                new WaypointPoint(1_000.0, 70.0, 1_000.0),
                "新补给站",
                Set.of("replacement depot"),
                original.category(),
                original.creatorId(),
                original.source(),
                original.provenance(),
                original.confidence(),
                1,
                original.status(),
                original.createdAt(),
                original.updatedAt().plusSeconds(1),
                original.lastVerifiedAt(),
                original.ttl()
            );

            await(repository.upsert(original));
            await(repository.upsert(updated));

            assertEquals(
                List.of(),
                await(repository.searchByName(
                    WORLD_A,
                    DimensionRef.OVERWORLD,
                    "legacy depot",
                    NOW.plusSeconds(2),
                    10
                ))
            );
            assertEquals(
                List.of(id),
                ids(await(repository.searchByName(
                    WORLD_A,
                    DimensionRef.OVERWORLD,
                    "replacement depot",
                    NOW.plusSeconds(2),
                    10
                )))
            );
            assertEquals(
                List.of(),
                await(repository.findIntersecting(
                    WORLD_A,
                    DimensionRef.OVERWORLD,
                    boxAround(0.0, 64.0, 0.0, 4.0),
                    NOW.plusSeconds(2),
                    10
                ))
            );
            assertEquals(
                List.of(id),
                ids(await(repository.findIntersecting(
                    WORLD_A,
                    DimensionRef.OVERWORLD,
                    boxAround(1_000.0, 70.0, 1_000.0, 4.0),
                    NOW.plusSeconds(2),
                    10
                )))
            );
        }
    }

    @Test
    void spatialQueryFindsOverlappingStrongGeometryInStableDistanceOrder() throws Exception {
        try (MemoryDatabase database = open("spatial.db")) {
            final WaypointRepository repository = database.waypoints();
            final Waypoint nearPoint = waypoint(
                "00000000-0000-0000-0000-000000000001",
                WORLD_A,
                DimensionRef.OVERWORLD,
                new WaypointPoint(2.0, 64.0, 2.0),
                "近点",
                Set.of(),
                0,
                Optional.empty()
            );
            final Waypoint overlappingRegion = waypoint(
                "00000000-0000-0000-0000-000000000002",
                WORLD_A,
                DimensionRef.OVERWORLD,
                new WaypointPolygon(List.of(
                    new WaypointPoint(4.0, 64.0, 4.0),
                    new WaypointPoint(12.0, 64.0, 4.0),
                    new WaypointPoint(12.0, 64.0, 12.0)
                )),
                "农田",
                Set.of(),
                0,
                Optional.empty()
            );
            final Waypoint farPoint = waypoint(
                "00000000-0000-0000-0000-000000000003",
                WORLD_A,
                DimensionRef.OVERWORLD,
                new WaypointPoint(500.0, 64.0, 500.0),
                "远点",
                Set.of(),
                0,
                Optional.empty()
            );
            await(repository.upsert(farPoint));
            await(repository.upsert(overlappingRegion));
            await(repository.upsert(nearPoint));

            assertEquals(
                List.of(nearPoint.id(), overlappingRegion.id()),
                ids(await(repository.findIntersecting(
                    WORLD_A,
                    DimensionRef.OVERWORLD,
                    new WaypointAabb(
                        new WaypointPoint(-5.0, 60.0, -5.0),
                        new WaypointPoint(6.0, 70.0, 6.0)
                    ),
                    NOW,
                    10
                )))
            );
        }
    }

    @Test
    void persistsAcrossReopenAndSoftDeleteRemovesBothIndexes() throws Exception {
        final Path databaseFile = temporaryDirectory.resolve("reopen.db");
        final Waypoint original = waypoint(
            "00000000-0000-0000-0000-000000000020",
            WORLD_A,
            DimensionRef.OVERWORLD,
            new WaypointPoint(20.0, 64.0, 20.0),
            "临时营地",
            Set.of("temporary camp"),
            0,
            Optional.empty()
        );
        try (MemoryDatabase database = MemoryDatabase.open(databaseFile)) {
            await(database.waypoints().upsert(original));
        }

        try (MemoryDatabase reopened = MemoryDatabase.open(databaseFile)) {
            assertEquals(
                original,
                await(reopened.waypoints().findById(original.id())).orElseThrow()
            );
            assertTrue(await(reopened.waypoints().softDelete(
                original.id(),
                0,
                NOW.plusSeconds(5)
            )));
            assertTrue(await(reopened.waypoints().findById(original.id())).isEmpty());
            final Waypoint archived = await(
                reopened.waypoints().findByIdIncludingDeleted(original.id())
            ).orElseThrow();
            assertEquals(WaypointStatus.ARCHIVED, archived.status());
            assertEquals(1, archived.revision());
            assertEquals(
                List.of(),
                await(reopened.waypoints().searchByName(
                    WORLD_A,
                    DimensionRef.OVERWORLD,
                    "temporary camp",
                    NOW.plusSeconds(6),
                    10
                ))
            );
            assertEquals(
                List.of(),
                await(reopened.waypoints().findIntersecting(
                    WORLD_A,
                    DimensionRef.OVERWORLD,
                    boxAround(20.0, 64.0, 20.0, 2.0),
                    NOW.plusSeconds(6),
                    10
                ))
            );
        }
    }

    @Test
    void rejectsStaleAndConflictingRevisionsWithoutChangingStoredRecord() throws Exception {
        try (MemoryDatabase database = open("revision.db")) {
            final WaypointRepository repository = database.waypoints();
            final Waypoint current = waypoint(
                "00000000-0000-0000-0000-000000000030",
                WORLD_A,
                DimensionRef.OVERWORLD,
                new WaypointPoint(0.0, 64.0, 0.0),
                "当前名称",
                Set.of(),
                2,
                Optional.empty()
            );
            await(repository.upsert(current));
            await(repository.upsert(current));

            final Waypoint stale = waypoint(
                current.id().toString(),
                WORLD_A,
                DimensionRef.OVERWORLD,
                current.geometry(),
                "旧名称",
                Set.of(),
                1,
                Optional.empty()
            );
            final CompletionException staleFailure = assertThrows(
                CompletionException.class,
                () -> repository.upsert(stale).join()
            );
            assertInstanceOf(WaypointRevisionConflictException.class, staleFailure.getCause());

            final Waypoint sameRevisionConflict = waypoint(
                current.id().toString(),
                WORLD_A,
                DimensionRef.OVERWORLD,
                current.geometry(),
                "冲突名称",
                Set.of(),
                2,
                Optional.empty()
            );
            final CompletionException equalFailure = assertThrows(
                CompletionException.class,
                () -> repository.upsert(sameRevisionConflict).join()
            );
            assertInstanceOf(WaypointRevisionConflictException.class, equalFailure.getCause());

            final Waypoint differentWorld = waypoint(
                current.id().toString(),
                WORLD_B,
                DimensionRef.OVERWORLD,
                current.geometry(),
                "跨世界错误更新",
                Set.of(),
                3,
                Optional.empty()
            );
            final CompletionException worldFailure = assertThrows(
                CompletionException.class,
                () -> repository.upsert(differentWorld).join()
            );
            assertInstanceOf(IllegalArgumentException.class, worldFailure.getCause());
            assertEquals(current, await(repository.findById(current.id())).orElseThrow());
        }
    }

    @Test
    void filtersExpiredRowsAndBoundsQueryLimit() throws Exception {
        try (MemoryDatabase database = open("expiry.db")) {
            final WaypointRepository repository = database.waypoints();
            final Waypoint expires = waypoint(
                "00000000-0000-0000-0000-000000000040",
                WORLD_A,
                DimensionRef.OVERWORLD,
                new WaypointPoint(0.0, 64.0, 0.0),
                "短期目标",
                Set.of("short target"),
                0,
                Optional.of(Duration.ofSeconds(10))
            );
            await(repository.upsert(expires));

            assertEquals(
                List.of(expires.id()),
                ids(await(repository.searchByName(
                    WORLD_A,
                    DimensionRef.OVERWORLD,
                    "short target",
                    NOW.plusSeconds(9),
                    10
                )))
            );
            assertEquals(
                List.of(),
                await(repository.searchByName(
                    WORLD_A,
                    DimensionRef.OVERWORLD,
                    "short target",
                    NOW.plusSeconds(10),
                    10
                ))
            );
            assertThrows(
                IllegalArgumentException.class,
                () -> repository.searchByName(
                    WORLD_A,
                    DimensionRef.OVERWORLD,
                    "short target",
                    NOW,
                    WaypointRepository.MAXIMUM_QUERY_LIMIT + 1
                )
            );
        }
    }

    private MemoryDatabase open(String name) {
        return MemoryDatabase.open(temporaryDirectory.resolve(name));
    }

    private static <T> T await(java.util.concurrent.CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static List<UUID> ids(List<Waypoint> waypoints) {
        return waypoints.stream().map(Waypoint::id).toList();
    }

    private static WaypointAabb boxAround(
        double x,
        double y,
        double z,
        double radius
    ) {
        return new WaypointAabb(
            new WaypointPoint(x - radius, y - radius, z - radius),
            new WaypointPoint(x + radius, y + radius, z + radius)
        );
    }

    private static Waypoint waypoint(
        String id,
        UUID world,
        DimensionRef dimension,
        WaypointGeometry geometry,
        String name,
        Set<String> aliases,
        long revision,
        Optional<Duration> ttl
    ) {
        return new Waypoint(
            UUID.fromString(id),
            world,
            dimension,
            geometry,
            name,
            aliases,
            "storage",
            CREATOR,
            "repository-test",
            WaypointProvenance.HUMAN_EXPLICIT,
            0.9,
            revision,
            WaypointStatus.ACTIVE,
            NOW,
            NOW.plusSeconds(revision),
            Optional.of(NOW),
            ttl
        );
    }
}
