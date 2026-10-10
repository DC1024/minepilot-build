package dev.mcai.companion.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import dev.mcai.companion.BuildInfo;
import dev.mcai.companion.memory.transport.VerifiedPortalEdgeRepository;
import dev.mcai.companion.memory.waypoint.WaypointRepository;

/**
 * Single-writer SQLite store. Every query is serialized on a dedicated
 * virtual-thread executor so callers never use the server tick thread.
 */
public final class MemoryDatabase implements AutoCloseable {
    public static final int MAX_PENDING_OPERATIONS = 4_096;
    private static final int MAX_DURABLE_CONVERSATION_TURNS = 4_096;

    private final Connection connection;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong rejectedOperations = new AtomicLong();
    private final WaypointRepository waypointRepository;
    private final VerifiedPortalEdgeRepository portalEdgeRepository;

    private MemoryDatabase(final Connection connection) {
        this.connection = connection;
        this.executor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_PENDING_OPERATIONS),
            Thread.ofVirtual().name("mcai-memory-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy()
        );
        this.waypointRepository = new WaypointRepository(new WaypointRepository.DatabaseAccess() {
            @Override
            public <T> CompletableFuture<T> submit(
                final WaypointRepository.SqlOperation<T> operation
            ) {
                return MemoryDatabase.this.submit(() -> operation.execute(connection));
            }
        });
        this.portalEdgeRepository = new VerifiedPortalEdgeRepository(
            new VerifiedPortalEdgeRepository.DatabaseAccess() {
                @Override
                public <T> CompletableFuture<T> submit(
                    final VerifiedPortalEdgeRepository.SqlOperation<T> operation
                ) {
                    return MemoryDatabase.this.submit(
                        () -> operation.execute(connection)
                    );
                }
            }
        );
    }

    public static MemoryDatabase open(final Path databasePath) {
        try {
            loadSqliteDriver();
            final Path normalized = databasePath.toAbsolutePath().normalize();
            Files.createDirectories(normalized.getParent());
            final Connection connection = DriverManager.getConnection("jdbc:sqlite:" + normalized);
            configure(connection);
            migrate(connection);
            return new MemoryDatabase(connection);
        } catch (IOException | SQLException exception) {
            throw new IllegalStateException("Unable to open companion memory database", exception);
        }
    }

    private static void loadSqliteDriver() {
        try {
            Class.forName(
                "org.sqlite.JDBC",
                true,
                MemoryDatabase.class.getClassLoader()
            );
        } catch (ClassNotFoundException | LinkageError exception) {
            throw new IllegalStateException(
                "SQLite driver is unavailable for companion memory",
                exception
            );
        }
    }

    public CompletableFuture<Void> appendEvent(final MemoryEvent event) {
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO event_log(
                    occurred_at, event_type, source, payload_json, world_revision, goal_revision
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
                statement.setString(1, event.occurredAt().toString());
                statement.setString(2, event.type());
                statement.setString(3, event.source());
                statement.setString(4, event.payloadJson());
                statement.setLong(5, event.worldRevision());
                statement.setLong(6, event.goalRevision());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Void> saveCheckpoint(final TaskCheckpoint checkpoint) {
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO task_checkpoint(task_id, goal_revision, skill_name, state_json, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(task_id) DO UPDATE SET
                    goal_revision = excluded.goal_revision,
                    skill_name = excluded.skill_name,
                    state_json = excluded.state_json,
                    updated_at = excluded.updated_at
                """)) {
                statement.setString(1, checkpoint.taskId().toString());
                statement.setLong(2, checkpoint.goalRevision());
                statement.setString(3, checkpoint.skillName());
                statement.setString(4, checkpoint.stateJson());
                statement.setString(5, checkpoint.updatedAt().toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<Optional<TaskCheckpoint>> loadCheckpoint(final UUID taskId) {
        return submit(() -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT goal_revision, skill_name, state_json, updated_at
                FROM task_checkpoint WHERE task_id = ?
                """)) {
                statement.setString(1, taskId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new TaskCheckpoint(
                        taskId,
                        result.getLong(1),
                        result.getString(2),
                        result.getString(3),
                        Instant.parse(result.getString(4))
                    ));
                }
            }
        });
    }

    public CompletableFuture<Long> eventCount() {
        return submit(() -> {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM event_log")) {
                return result.next() ? result.getLong(1) : 0L;
            }
        });
    }

    public CompletableFuture<Void> appendConversationTurn(
            final ConversationTurn turn
    ) {
        Objects.requireNonNull(turn, "turn");
        return submit(() -> {
            try (PreparedStatement statement =
                    connection.prepareStatement("""
                        INSERT INTO conversation_turn(
                            occurred_at, player_text, agent_text
                        ) VALUES (?, ?, ?)
                        """)) {
                statement.setString(1, turn.occurredAt().toString());
                statement.setString(2, turn.player());
                statement.setString(3, turn.agent());
                statement.executeUpdate();
            }
            try (PreparedStatement prune =
                    connection.prepareStatement("""
                        DELETE FROM conversation_turn
                        WHERE sequence <= (
                            SELECT MAX(sequence) - ?
                            FROM conversation_turn
                        )
                        """)) {
                prune.setInt(1, MAX_DURABLE_CONVERSATION_TURNS);
                prune.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<List<ConversationTurn>>
            loadRecentConversationTurns(final int maximumTurns) {
        if (maximumTurns < 1 || maximumTurns > 256) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                            "maximumTurns must be in [1,256]"
                    )
            );
        }
        return submit(() -> {
            final List<ConversationTurn> newestFirst =
                    new ArrayList<>();
            try (PreparedStatement statement =
                    connection.prepareStatement("""
                        SELECT sequence, occurred_at,
                               player_text, agent_text
                        FROM conversation_turn
                        ORDER BY sequence DESC
                        LIMIT ?
                        """)) {
                statement.setInt(1, maximumTurns);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        newestFirst.add(new ConversationTurn(
                                result.getLong(1),
                                Instant.parse(result.getString(2)),
                                result.getString(3),
                                result.getString(4)
                        ));
                    }
                }
            }
            Collections.reverse(newestFirst);
            return List.copyOf(newestFirst);
        });
    }

    /**
     * Reads the newest audit event of one exact type. The query remains on
     * the database executor so diagnostics and live GameTests never block the
     * server tick thread.
     */
    public CompletableFuture<Optional<MemoryEvent>> latestEvent(
            final String eventType
    ) {
        if (eventType == null
                || eventType.isBlank()
                || eventType.length() > 128) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException(
                        "eventType must contain 1..128 characters"
                    )
            );
        }
        return submit(() -> {
            try (PreparedStatement statement =
                    connection.prepareStatement("""
                        SELECT occurred_at, event_type, source,
                               payload_json, world_revision,
                               goal_revision
                        FROM event_log
                        WHERE event_type = ?
                        ORDER BY sequence DESC
                        LIMIT 1
                        """)) {
                statement.setString(1, eventType);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new MemoryEvent(
                            Instant.parse(result.getString(1)),
                            result.getString(2),
                            result.getString(3),
                            result.getString(4),
                            result.getLong(5),
                            result.getLong(6)
                    ));
                }
            }
        });
    }

    public WaypointRepository waypoints() {
        return waypointRepository;
    }

    public VerifiedPortalEdgeRepository portalEdges() {
        return portalEdgeRepository;
    }

    public long rejectedOperationCount() {
        return rejectedOperations.get();
    }

    private <T> CompletableFuture<T> submit(final SqlSupplier<T> operation) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Memory database is closed"));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return operation.get();
                } catch (SQLException exception) {
                    throw new IllegalStateException(
                        "Memory database operation failed",
                        exception
                    );
                }
            }, executor);
        } catch (RejectedExecutionException exception) {
            rejectedOperations.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException(
                "Memory database is applying backpressure"
            ));
        }
    }

    private static void configure(final Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
    }

    private static void migrate(final Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_meta(
                        id INTEGER PRIMARY KEY CHECK(id = 1),
                        schema_version INTEGER NOT NULL
                    )
                    """);
                statement.execute("""
                    INSERT INTO schema_meta(id, schema_version) VALUES(1, %d)
                    ON CONFLICT(id) DO NOTHING
                    """.formatted(BuildInfo.MEMORY_SCHEMA_VERSION));
                final int storedSchemaVersion = readSchemaVersion(connection);
                if (storedSchemaVersion < 1
                    || storedSchemaVersion > BuildInfo.MEMORY_SCHEMA_VERSION) {
                    throw new SQLException(
                        "Unsupported companion memory schema version "
                            + storedSchemaVersion
                    );
                }
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS event_log(
                        sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                        occurred_at TEXT NOT NULL,
                        event_type TEXT NOT NULL,
                        source TEXT NOT NULL,
                        payload_json TEXT NOT NULL,
                        world_revision INTEGER NOT NULL,
                        goal_revision INTEGER NOT NULL
                    )
                    """);
                statement.execute("""
                    CREATE INDEX IF NOT EXISTS event_log_revision_idx
                    ON event_log(goal_revision, world_revision, sequence)
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS task_checkpoint(
                        task_id TEXT PRIMARY KEY,
                        goal_revision INTEGER NOT NULL,
                        skill_name TEXT NOT NULL,
                        state_json TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS conversation_turn(
                        sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                        occurred_at TEXT NOT NULL,
                        player_text TEXT NOT NULL,
                        agent_text TEXT NOT NULL
                    )
                    """);
                statement.execute("""
                    CREATE INDEX IF NOT EXISTS
                        conversation_turn_sequence_idx
                    ON conversation_turn(sequence)
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS waypoint(
                        row_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        waypoint_id TEXT NOT NULL UNIQUE,
                        world_id TEXT NOT NULL,
                        dimension TEXT NOT NULL,
                        geometry_type TEXT NOT NULL,
                        geometry_json TEXT NOT NULL,
                        payload_json TEXT NOT NULL,
                        canonical_name TEXT NOT NULL,
                        aliases TEXT NOT NULL,
                        category TEXT NOT NULL,
                        creator_uuid TEXT NOT NULL,
                        source TEXT NOT NULL,
                        provenance TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        revision INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        last_verified_at TEXT,
                        ttl_seconds INTEGER,
                        ttl_nanos INTEGER,
                        valid_until TEXT,
                        valid_until_epoch_second INTEGER,
                        valid_until_nano INTEGER,
                        deleted_at TEXT
                    )
                    """);
            }

            ensureWaypointColumn(connection, "payload_json", "TEXT");
            ensureWaypointColumn(connection, "source", "TEXT NOT NULL DEFAULT 'legacy'");
            ensureWaypointColumn(connection, "status", "TEXT NOT NULL DEFAULT 'STALE'");
            ensureWaypointColumn(
                connection,
                "created_at",
                "TEXT NOT NULL DEFAULT '1970-01-01T00:00:00Z'"
            );
            ensureWaypointColumn(
                connection,
                "updated_at",
                "TEXT NOT NULL DEFAULT '1970-01-01T00:00:00Z'"
            );
            ensureWaypointColumn(connection, "ttl_seconds", "INTEGER");
            ensureWaypointColumn(connection, "ttl_nanos", "INTEGER");
            ensureWaypointColumn(connection, "valid_until_epoch_second", "INTEGER");
            ensureWaypointColumn(connection, "valid_until_nano", "INTEGER");
            ensureWaypointColumn(connection, "deleted_at", "TEXT");

            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS waypoint_fts USING fts5(
                        waypoint_id UNINDEXED,
                        canonical_name,
                        aliases,
                        category,
                        tokenize = 'unicode61'
                    )
                    """);
                statement.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS waypoint_bounds USING rtree(
                        row_id,
                        min_x, max_x,
                        min_y, max_y,
                        min_z, max_z
                    )
                    """);
                statement.execute("""
                    CREATE INDEX IF NOT EXISTS waypoint_world_dimension_idx
                    ON waypoint(world_id, dimension, deleted_at, waypoint_id)
                    """);
                statement.execute("""
                    CREATE INDEX IF NOT EXISTS waypoint_revision_idx
                    ON waypoint(waypoint_id, revision)
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS verified_portal_edge(
                        row_id INTEGER PRIMARY KEY AUTOINCREMENT,
                        edge_id TEXT NOT NULL UNIQUE,
                        world_id TEXT NOT NULL,
                        portal_kind TEXT NOT NULL,
                        source_dimension TEXT NOT NULL,
                        source_x REAL NOT NULL,
                        source_y REAL NOT NULL,
                        source_z REAL NOT NULL,
                        source_portal_x INTEGER NOT NULL,
                        source_portal_y INTEGER NOT NULL,
                        source_portal_z INTEGER NOT NULL,
                        destination_dimension TEXT NOT NULL,
                        destination_x REAL NOT NULL,
                        destination_y REAL NOT NULL,
                        destination_z REAL NOT NULL,
                        destination_block_x INTEGER NOT NULL,
                        destination_block_y INTEGER NOT NULL,
                        destination_block_z INTEGER NOT NULL,
                        first_verified_at TEXT NOT NULL,
                        last_verified_at TEXT NOT NULL,
                        success_count INTEGER NOT NULL
                            CHECK(success_count >= 1),
                        revision INTEGER NOT NULL CHECK(revision >= 0),
                        payload_json TEXT NOT NULL,
                        CHECK(source_dimension <> destination_dimension),
                        CHECK(success_count = revision + 1),
                        UNIQUE(
                            world_id,
                            portal_kind,
                            source_dimension,
                            source_portal_x,
                            source_portal_y,
                            source_portal_z,
                            destination_dimension,
                            destination_block_x,
                            destination_block_y,
                            destination_block_z
                        )
                    )
                    """);
                statement.execute("""
                    CREATE INDEX IF NOT EXISTS
                        verified_portal_edge_world_source_idx
                    ON verified_portal_edge(
                        world_id,
                        source_dimension,
                        last_verified_at,
                        edge_id
                    )
                    """);
                statement.execute("""
                    CREATE INDEX IF NOT EXISTS
                        verified_portal_edge_world_destination_idx
                    ON verified_portal_edge(
                        world_id,
                        destination_dimension,
                        last_verified_at,
                        edge_id
                    )
                    """);
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS
                        verified_portal_traversal_evidence(
                            evidence_id TEXT PRIMARY KEY,
                            edge_id TEXT NOT NULL,
                            recorded_at TEXT NOT NULL,
                            FOREIGN KEY(edge_id)
                                REFERENCES verified_portal_edge(edge_id)
                                ON DELETE CASCADE
                        )
                    """);
                statement.execute("""
                    CREATE INDEX IF NOT EXISTS
                        verified_portal_evidence_edge_idx
                    ON verified_portal_traversal_evidence(
                        edge_id,
                        recorded_at
                    )
                    """);
                statement.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS
                        verified_portal_edge_source_bounds USING rtree(
                            row_id,
                            min_x, max_x,
                            min_y, max_y,
                            min_z, max_z
                        )
                    """);
                statement.execute("""
                    UPDATE schema_meta
                    SET schema_version = %d
                    WHERE id = 1
                    """.formatted(BuildInfo.MEMORY_SCHEMA_VERSION));
            }
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static int readSchemaVersion(
        final Connection connection
    ) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("""
                 SELECT schema_version FROM schema_meta WHERE id = 1
                 """)) {
            if (!row.next()) {
                throw new SQLException(
                    "Companion memory schema metadata is missing"
                );
            }
            return row.getInt(1);
        }
    }

    private static void ensureWaypointColumn(
        final Connection connection,
        final String column,
        final String definition
    ) throws SQLException {
        boolean exists = false;
        try (Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery("PRAGMA table_info(waypoint)")) {
            while (columns.next()) {
                if (column.equals(columns.getString("name"))) {
                    exists = true;
                    break;
                }
            }
        }
        if (!exists) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE waypoint ADD COLUMN " + column + " " + definition);
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        boolean interrupted = false;
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            interrupted = true;
            executor.shutdownNow();
        } finally {
            try {
                /*
                 * The runtime lifecycle audit is written immediately before
                 * shutdown.  Flush the final WAL frames before releasing the
                 * JDBC handle so an external read-only verifier (or a second
                 * server process) cannot observe a transient empty event log
                 * while the launcher has already reported a clean exit.
                 * This runs only during server shutdown, never on the tick
                 * thread during normal gameplay.
                 */
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                } catch (SQLException ignored) {
                    // Connection close below remains authoritative cleanup.
                }
                connection.close();
            } catch (SQLException exception) {
                throw new IllegalStateException("Unable to close companion memory database", exception);
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }
}
