package dev.mcai.companion.memory.transport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.BuildInfo;
import dev.mcai.companion.memory.MemoryDatabase;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.skills.portal.PortalKind;
import dev.mcai.companion.skills.portal.PortalTraversalResult;
import dev.mcai.companion.waypoint.DimensionRef;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class VerifiedPortalEdgeRepositoryTest {
    private static final UUID WORLD_A =
        UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID WORLD_B =
        UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final Instant NOW =
        Instant.parse("2026-07-25T00:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void recordsOnlyDistinctActualTraversalsAndQueriesNearbyDirectedEdges()
            throws Exception {
        try (MemoryDatabase database = open("edges.db")) {
            VerifiedPortalEdgeRepository repository =
                database.portalEdges();
            PortalTraversalResult first = traversal(
                1,
                100,
                180,
                new PerceptionVec3(10.25, 64.0, 10.25),
                new PerceptionVec3(80.25, 70.0, 80.25)
            );
            VerifiedPortalEdge initial = await(
                repository.recordTraversal(WORLD_A, first, NOW)
            );
            VerifiedPortalEdge duplicate = await(
                repository.recordTraversal(
                    WORLD_A,
                    first,
                    NOW.plusSeconds(1)
                )
            );
            VerifiedPortalEdge repeated = await(
                repository.recordTraversal(
                    WORLD_A,
                    traversal(
                        2,
                        200,
                        280,
                        new PerceptionVec3(10.5, 64.0, 10.5),
                        new PerceptionVec3(80.75, 70.0, 80.75)
                    ),
                    NOW.plusSeconds(2)
                )
            );

            assertEquals(initial.edgeId(), duplicate.edgeId());
            assertEquals(1, duplicate.successCount());
            assertEquals(2, repeated.successCount());
            assertEquals(1, repeated.revision());
            assertEquals(
                List.of(repeated),
                await(repository.findNearby(
                    WORLD_A,
                    DimensionRef.OVERWORLD,
                    new PerceptionVec3(10.0, 64.0, 10.0),
                    2.0,
                    10
                ))
            );
            assertTrue(await(repository.findNearby(
                WORLD_A,
                DimensionRef.NETHER,
                new PerceptionVec3(80.0, 70.0, 80.0),
                10.0,
                10
            )).isEmpty());
            assertEquals(
                List.of(repeated),
                await(repository.findNearbyArrivals(
                    WORLD_A,
                    DimensionRef.NETHER,
                    new PerceptionVec3(80.0, 70.0, 80.0),
                    10.0,
                    10
                ))
            );
            assertTrue(await(repository.findNearbyArrivals(
                WORLD_A,
                DimensionRef.OVERWORLD,
                new PerceptionVec3(10.0, 64.0, 10.0),
                10.0,
                10
            )).isEmpty());
            assertTrue(await(repository.findNearby(
                WORLD_B,
                DimensionRef.OVERWORLD,
                new PerceptionVec3(10.0, 64.0, 10.0),
                10.0,
                10
            )).isEmpty());
            assertTrue(await(repository.findNearby(
                WORLD_A,
                DimensionRef.OVERWORLD,
                new PerceptionVec3(1_000.0, 64.0, 1_000.0),
                2.0,
                10
            )).isEmpty());
        }
    }

    @Test
    void rejectsSameDimensionAndEnforcesQueryBoundsBeforeSubmission() {
        AtomicBoolean submitted = new AtomicBoolean();
        VerifiedPortalEdgeRepository repository =
            new VerifiedPortalEdgeRepository(new PendingDatabase(submitted));
        PortalTraversalResult sameDimension = new PortalTraversalResult(
            PortalKind.END_GATEWAY,
            1,
            DimensionRef.END,
            new PerceptionVec3(0, 64, 0),
            new BlockCoordinate(0, 64, 0),
            DimensionRef.END,
            new PerceptionVec3(1_000, 70, 1_000),
            1,
            2,
            Optional.empty()
        );

        assertThrows(
            IllegalArgumentException.class,
            () -> repository.recordTraversal(WORLD_A, sameDimension, NOW)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> repository.findNearby(
                WORLD_A,
                DimensionRef.OVERWORLD,
                new PerceptionVec3(0, 64, 0),
                VerifiedPortalEdgeRepository.MAXIMUM_QUERY_RADIUS + 1,
                1
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> repository.findNearby(
                WORLD_A,
                DimensionRef.OVERWORLD,
                new PerceptionVec3(0, 64, 0),
                1,
                VerifiedPortalEdgeRepository.MAXIMUM_QUERY_LIMIT + 1
            )
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> repository.findNearbyArrivals(
                WORLD_A,
                DimensionRef.NETHER,
                new PerceptionVec3(0, 64, 0),
                VerifiedPortalEdgeRepository.MAXIMUM_QUERY_RADIUS + 1,
                1
            )
        );
        assertFalse(submitted.get());
    }

    @Test
    void submissionAndObserverNeverWaitForDatabaseWork() {
        AtomicBoolean submitted = new AtomicBoolean();
        VerifiedPortalEdgeRepository repository =
            new VerifiedPortalEdgeRepository(new PendingDatabase(submitted));

        CompletableFuture<VerifiedPortalEdge> future =
            repository.recordTraversal(
                WORLD_A,
                traversal(
                    1,
                    1,
                    2,
                    new PerceptionVec3(0, 64, 0),
                    new PerceptionVec3(4, 70, 4)
                ),
                NOW
            );
        assertTrue(submitted.get());
        assertFalse(future.isDone());

        PersistentPortalTraversalObserver observer =
            new PersistentPortalTraversalObserver(
                WORLD_A,
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC)
            );
        observer.onTraversal(traversal(
            2,
            3,
            4,
            new PerceptionVec3(0, 64, 0),
            new PerceptionVec3(4, 70, 4)
        ));
        assertFalse(observer.latestWrite().isDone());
    }

    @Test
    void migratesVersionOneDatabaseAndPersistsAcrossReopen()
            throws Exception {
        Path file = temporaryDirectory.resolve("migration.db");
        Class.forName("org.sqlite.JDBC");
        try (var connection = DriverManager.getConnection(
            "jdbc:sqlite:" + file.toAbsolutePath()
        ); var statement = connection.createStatement()) {
            statement.execute("""
                CREATE TABLE schema_meta(
                    id INTEGER PRIMARY KEY CHECK(id = 1),
                    schema_version INTEGER NOT NULL
                )
                """);
            statement.execute(
                "INSERT INTO schema_meta(id, schema_version) VALUES(1, 1)"
            );
            statement.execute("""
                CREATE TABLE legacy_sentinel(
                    value TEXT NOT NULL
                )
                """);
            statement.execute(
                "INSERT INTO legacy_sentinel(value) VALUES('preserved')"
            );
        }

        String edgeId;
        try (MemoryDatabase database = MemoryDatabase.open(file)) {
            edgeId = await(database.portalEdges().recordTraversal(
                WORLD_A,
                traversal(
                    7,
                    400,
                    500,
                    new PerceptionVec3(5, 65, 5),
                    new PerceptionVec3(40, 70, 40)
                ),
                NOW
            )).edgeId();
        }
        try (MemoryDatabase reopened = MemoryDatabase.open(file)) {
            assertTrue(await(
                reopened.portalEdges().findById(edgeId)
            ).isPresent());
            assertEquals(1L, await(reopened.portalEdges().count(WORLD_A)));
        }
        try (var connection = DriverManager.getConnection(
            "jdbc:sqlite:" + file.toAbsolutePath()
        ); var statement = connection.createStatement()) {
            try (var row = statement.executeQuery(
                "SELECT schema_version FROM schema_meta WHERE id = 1"
            )) {
                assertTrue(row.next());
                assertEquals(
                    BuildInfo.MEMORY_SCHEMA_VERSION,
                    row.getInt(1)
                );
            }
            try (var row = statement.executeQuery(
                "SELECT value FROM legacy_sentinel"
            )) {
                assertTrue(row.next());
                assertEquals("preserved", row.getString(1));
            }
        }
    }

    private MemoryDatabase open(String name) {
        return MemoryDatabase.open(temporaryDirectory.resolve(name));
    }

    private static PortalTraversalResult traversal(
        long generation,
        long start,
        long finish,
        PerceptionVec3 source,
        PerceptionVec3 destination
    ) {
        return new PortalTraversalResult(
            PortalKind.NETHER_PORTAL,
            generation,
            DimensionRef.OVERWORLD,
            source,
            new BlockCoordinate(10, 64, 10),
            DimensionRef.NETHER,
            destination,
            start,
            finish,
            Optional.empty()
        );
    }

    private static <T> T await(CompletableFuture<T> future)
            throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private record PendingDatabase(AtomicBoolean submitted)
            implements VerifiedPortalEdgeRepository.DatabaseAccess {
        @Override
        public <T> CompletableFuture<T> submit(
            VerifiedPortalEdgeRepository.SqlOperation<T> operation
        ) {
            submitted.set(true);
            return new CompletableFuture<>();
        }
    }
}
