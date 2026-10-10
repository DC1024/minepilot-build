package dev.mcai.companion.memory.waypoint;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import dev.mcai.companion.waypoint.DimensionRef;
import dev.mcai.companion.waypoint.Waypoint;
import dev.mcai.companion.waypoint.WaypointAabb;
import dev.mcai.companion.waypoint.WaypointNames;
import dev.mcai.companion.waypoint.WaypointStatus;

/**
 * Asynchronous waypoint persistence over MemoryDatabase's single JDBC executor.
 *
 * <p>Soft deletion is used: the durable waypoint row and revision history head
 * remain available for audit, while its FTS and RTree entries are removed in
 * the same transaction.</p>
 */
public final class WaypointRepository {
    public static final int MAXIMUM_QUERY_LIMIT = 1_000;

    private static final String EXPIRY_FILTER = """
        (
            w.valid_until_epoch_second IS NULL
            OR w.valid_until_epoch_second > ?
            OR (
                w.valid_until_epoch_second = ?
                AND w.valid_until_nano > ?
            )
        )
        """;

    private final DatabaseAccess database;
    private final WaypointJsonCodec codec = new WaypointJsonCodec();

    public WaypointRepository(DatabaseAccess database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public CompletableFuture<Void> upsert(Waypoint waypoint) {
        Objects.requireNonNull(waypoint, "waypoint");
        return database.submit(connection ->
            inTransaction(connection, () -> {
                upsertTransactional(connection, waypoint, null);
                return null;
            })
        );
    }

    public CompletableFuture<Optional<Waypoint>> findById(UUID waypointId) {
        return findById(waypointId, false);
    }

    public CompletableFuture<Optional<Waypoint>> findByIdIncludingDeleted(UUID waypointId) {
        return findById(waypointId, true);
    }

    public CompletableFuture<List<Waypoint>> searchByName(
        UUID worldId,
        DimensionRef dimension,
        String query,
        Instant at,
        int limit
    ) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(at, "at");
        validateLimit(limit);
        final String ftsQuery = toFtsQuery(query);

        return database.submit(connection -> {
            final List<Waypoint> results = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT w.payload_json
                FROM waypoint_fts
                JOIN waypoint w ON w.row_id = waypoint_fts.rowid
                WHERE waypoint_fts MATCH ?
                  AND w.world_id = ?
                  AND w.dimension = ?
                  AND w.deleted_at IS NULL
                  AND
                """ + EXPIRY_FILTER + """
                ORDER BY
                    bm25(waypoint_fts, 0.0, 10.0, 5.0, 2.0) ASC,
                    w.confidence DESC,
                    w.canonical_name COLLATE NOCASE ASC,
                    w.waypoint_id ASC
                LIMIT ?
                """)) {
                int parameter = 1;
                statement.setString(parameter++, ftsQuery);
                statement.setString(parameter++, worldId.toString());
                statement.setString(parameter++, dimension.id());
                parameter = bindInstant(statement, parameter, at);
                statement.setInt(parameter, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        final Waypoint waypoint = decodePayload(rows.getString(1));
                        if (waypoint.worldId().equals(worldId)
                            && waypoint.dimension().equals(dimension)
                            && waypoint.isSearchableAt(at)) {
                            results.add(waypoint);
                        }
                    }
                }
            }
            return List.copyOf(results);
        });
    }

    public CompletableFuture<List<Waypoint>> findIntersecting(
        UUID worldId,
        DimensionRef dimension,
        WaypointAabb queryBounds,
        Instant at,
        int limit
    ) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(queryBounds, "queryBounds");
        Objects.requireNonNull(at, "at");
        validateLimit(limit);

        return database.submit(connection -> {
            final List<Waypoint> results = new ArrayList<>();
            final var center = queryBounds.referencePoint();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT w.payload_json
                FROM waypoint_bounds b
                JOIN waypoint w ON w.row_id = b.row_id
                WHERE b.min_x <= ?
                  AND b.max_x >= ?
                  AND b.min_y <= ?
                  AND b.max_y >= ?
                  AND b.min_z <= ?
                  AND b.max_z >= ?
                  AND w.world_id = ?
                  AND w.dimension = ?
                  AND w.deleted_at IS NULL
                  AND
                """ + EXPIRY_FILTER + """
                ORDER BY
                    (
                        ((b.min_x + b.max_x) / 2.0 - ?)
                        * ((b.min_x + b.max_x) / 2.0 - ?)
                        + ((b.min_y + b.max_y) / 2.0 - ?)
                        * ((b.min_y + b.max_y) / 2.0 - ?)
                        + ((b.min_z + b.max_z) / 2.0 - ?)
                        * ((b.min_z + b.max_z) / 2.0 - ?)
                    ) ASC,
                    w.waypoint_id ASC
                LIMIT ?
                """)) {
                int parameter = 1;
                statement.setDouble(parameter++, queryBounds.maximum().x());
                statement.setDouble(parameter++, queryBounds.minimum().x());
                statement.setDouble(parameter++, queryBounds.maximum().y());
                statement.setDouble(parameter++, queryBounds.minimum().y());
                statement.setDouble(parameter++, queryBounds.maximum().z());
                statement.setDouble(parameter++, queryBounds.minimum().z());
                statement.setString(parameter++, worldId.toString());
                statement.setString(parameter++, dimension.id());
                parameter = bindInstant(statement, parameter, at);
                statement.setDouble(parameter++, center.x());
                statement.setDouble(parameter++, center.x());
                statement.setDouble(parameter++, center.y());
                statement.setDouble(parameter++, center.y());
                statement.setDouble(parameter++, center.z());
                statement.setDouble(parameter++, center.z());
                statement.setInt(parameter, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        final Waypoint waypoint = decodePayload(rows.getString(1));
                        if (waypoint.worldId().equals(worldId)
                            && waypoint.dimension().equals(dimension)
                            && waypoint.isSearchableAt(at)) {
                            results.add(waypoint);
                        }
                    }
                }
            }
            return List.copyOf(results);
        });
    }

    public CompletableFuture<Boolean> softDelete(
        UUID waypointId,
        long expectedRevision,
        Instant deletedAt
    ) {
        Objects.requireNonNull(waypointId, "waypointId");
        Objects.requireNonNull(deletedAt, "deletedAt");
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("Expected revision must be non-negative");
        }

        return database.submit(connection ->
            inTransaction(connection, () -> {
                final ExistingWaypoint existing = loadExisting(connection, waypointId);
                if (existing == null) {
                    return false;
                }
                if (existing.revision() != expectedRevision) {
                    throw new WaypointRevisionConflictException(
                        waypointId,
                        existing.revision(),
                        expectedRevision
                    );
                }
                if (existing.deletedAt() != null) {
                    return false;
                }
                if (expectedRevision == Long.MAX_VALUE) {
                    throw new WaypointRevisionConflictException(
                        waypointId,
                        expectedRevision,
                        expectedRevision
                    );
                }

                final Waypoint current = decodePayload(existing.payloadJson());
                if (deletedAt.isBefore(current.updatedAt())) {
                    throw new IllegalArgumentException(
                        "Deletion timestamp must not precede the waypoint update"
                    );
                }
                final Waypoint archived = new Waypoint(
                    current.id(),
                    current.worldId(),
                    current.dimension(),
                    current.geometry(),
                    current.name(),
                    current.aliases(),
                    current.category(),
                    current.creatorId(),
                    current.source(),
                    current.provenance(),
                    current.confidence(),
                    expectedRevision + 1,
                    WaypointStatus.ARCHIVED,
                    current.createdAt(),
                    deletedAt,
                    current.lastVerifiedAt(),
                    current.ttl()
                );
                upsertTransactional(connection, archived, deletedAt);
                return true;
            })
        );
    }

    private CompletableFuture<Optional<Waypoint>> findById(
        UUID waypointId,
        boolean includeDeleted
    ) {
        Objects.requireNonNull(waypointId, "waypointId");
        return database.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT payload_json, deleted_at
                FROM waypoint
                WHERE waypoint_id = ?
                """)) {
                statement.setString(1, waypointId.toString());
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next() || (!includeDeleted && row.getString(2) != null)) {
                        return Optional.empty();
                    }
                    return Optional.of(decodePayload(row.getString(1)));
                }
            }
        });
    }

    private void upsertTransactional(
        Connection connection,
        Waypoint waypoint,
        Instant deletedAt
    ) throws SQLException {
        final String payloadJson = codec.encode(waypoint);
        final ExistingWaypoint existing = loadExisting(connection, waypoint.id());
        if (existing != null) {
            if (existing.payloadJson() != null
                && !decodePayload(existing.payloadJson()).worldId().equals(waypoint.worldId())) {
                throw new IllegalArgumentException("A waypoint cannot move between worlds");
            }
            if (waypoint.revision() < existing.revision()) {
                throw new WaypointRevisionConflictException(
                    waypoint.id(),
                    existing.revision(),
                    waypoint.revision()
                );
            }
            if (waypoint.revision() == existing.revision()) {
                if (payloadJson.equals(existing.payloadJson())
                    && Objects.equals(
                        deletedAt == null ? null : deletedAt.toString(),
                        existing.deletedAt()
                    )) {
                    return;
                }
                throw new WaypointRevisionConflictException(
                    waypoint.id(),
                    existing.revision(),
                    waypoint.revision()
                );
            }
        }

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO waypoint(
                waypoint_id,
                world_id,
                dimension,
                geometry_type,
                geometry_json,
                payload_json,
                canonical_name,
                aliases,
                category,
                creator_uuid,
                source,
                provenance,
                confidence,
                revision,
                status,
                created_at,
                updated_at,
                last_verified_at,
                ttl_seconds,
                ttl_nanos,
                valid_until,
                valid_until_epoch_second,
                valid_until_nano,
                deleted_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(waypoint_id) DO UPDATE SET
                world_id = excluded.world_id,
                dimension = excluded.dimension,
                geometry_type = excluded.geometry_type,
                geometry_json = excluded.geometry_json,
                payload_json = excluded.payload_json,
                canonical_name = excluded.canonical_name,
                aliases = excluded.aliases,
                category = excluded.category,
                creator_uuid = excluded.creator_uuid,
                source = excluded.source,
                provenance = excluded.provenance,
                confidence = excluded.confidence,
                revision = excluded.revision,
                status = excluded.status,
                created_at = excluded.created_at,
                updated_at = excluded.updated_at,
                last_verified_at = excluded.last_verified_at,
                ttl_seconds = excluded.ttl_seconds,
                ttl_nanos = excluded.ttl_nanos,
                valid_until = excluded.valid_until,
                valid_until_epoch_second = excluded.valid_until_epoch_second,
                valid_until_nano = excluded.valid_until_nano,
                deleted_at = excluded.deleted_at
            """)) {
            bindWaypoint(statement, waypoint, payloadJson, deletedAt);
            statement.executeUpdate();
        }

        final long rowId = requireRowId(connection, waypoint.id());
        removeSearchIndexes(connection, rowId);
        if (deletedAt == null && waypoint.status().isSearchable()) {
            addSearchIndexes(connection, rowId, waypoint);
        }
    }

    private void bindWaypoint(
        PreparedStatement statement,
        Waypoint waypoint,
        String payloadJson,
        Instant deletedAt
    ) throws SQLException {
        final Optional<Instant> expiry = waypoint.expiresAt();
        final Optional<Duration> ttl = waypoint.ttl();
        int parameter = 1;
        statement.setString(parameter++, waypoint.id().toString());
        statement.setString(parameter++, waypoint.worldId().toString());
        statement.setString(parameter++, waypoint.dimension().id());
        statement.setString(parameter++, waypoint.geometry().type().name());
        statement.setString(parameter++, codec.encodeGeometry(waypoint.geometry()));
        statement.setString(parameter++, payloadJson);
        statement.setString(parameter++, waypoint.name());
        statement.setString(parameter++, String.join("\n", waypoint.aliases()));
        statement.setString(parameter++, waypoint.category());
        statement.setString(parameter++, waypoint.creatorId().toString());
        statement.setString(parameter++, waypoint.source());
        statement.setString(parameter++, waypoint.provenance().name());
        statement.setDouble(parameter++, waypoint.confidence());
        statement.setLong(parameter++, waypoint.revision());
        statement.setString(parameter++, waypoint.status().name());
        statement.setString(parameter++, waypoint.createdAt().toString());
        statement.setString(parameter++, waypoint.updatedAt().toString());
        statement.setString(
            parameter++,
            waypoint.lastVerifiedAt().map(Instant::toString).orElse(null)
        );
        if (ttl.isPresent()) {
            statement.setLong(parameter++, ttl.orElseThrow().getSeconds());
            statement.setInt(parameter++, ttl.orElseThrow().getNano());
        } else {
            statement.setObject(parameter++, null);
            statement.setObject(parameter++, null);
        }
        statement.setString(parameter++, expiry.map(Instant::toString).orElse(null));
        if (expiry.isPresent()) {
            statement.setLong(parameter++, expiry.orElseThrow().getEpochSecond());
            statement.setInt(parameter++, expiry.orElseThrow().getNano());
        } else {
            statement.setObject(parameter++, null);
            statement.setObject(parameter++, null);
        }
        statement.setString(parameter, deletedAt == null ? null : deletedAt.toString());
    }

    private static ExistingWaypoint loadExisting(
        Connection connection,
        UUID waypointId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT row_id, revision, payload_json, deleted_at
            FROM waypoint
            WHERE waypoint_id = ?
            """)) {
            statement.setString(1, waypointId.toString());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                return new ExistingWaypoint(
                    row.getLong(1),
                    row.getLong(2),
                    row.getString(3),
                    row.getString(4)
                );
            }
        }
    }

    private static long requireRowId(Connection connection, UUID waypointId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT row_id FROM waypoint WHERE waypoint_id = ?
            """)) {
            statement.setString(1, waypointId.toString());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException("Waypoint row disappeared during upsert");
                }
                return row.getLong(1);
            }
        }
    }

    private static void removeSearchIndexes(Connection connection, long rowId) throws SQLException {
        try (PreparedStatement fts = connection.prepareStatement(
            "DELETE FROM waypoint_fts WHERE rowid = ?"
        );
             PreparedStatement bounds = connection.prepareStatement(
                 "DELETE FROM waypoint_bounds WHERE row_id = ?"
             )) {
            fts.setLong(1, rowId);
            fts.executeUpdate();
            bounds.setLong(1, rowId);
            bounds.executeUpdate();
        }
    }

    private static void addSearchIndexes(
        Connection connection,
        long rowId,
        Waypoint waypoint
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO waypoint_fts(
                rowid, waypoint_id, canonical_name, aliases, category
            ) VALUES (?, ?, ?, ?, ?)
            """)) {
            statement.setLong(1, rowId);
            statement.setString(2, waypoint.id().toString());
            statement.setString(3, WaypointNames.normalize(waypoint.name()));
            statement.setString(
                4,
                waypoint.aliases().stream()
                    .map(WaypointNames::normalize)
                    .collect(Collectors.joining("\n"))
            );
            statement.setString(5, WaypointNames.normalize(waypoint.category()));
            statement.executeUpdate();
        }

        final WaypointAabb bounds = waypoint.geometry().bounds();
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO waypoint_bounds(
                row_id, min_x, max_x, min_y, max_y, min_z, max_z
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setLong(1, rowId);
            statement.setDouble(2, bounds.minimum().x());
            statement.setDouble(3, bounds.maximum().x());
            statement.setDouble(4, bounds.minimum().y());
            statement.setDouble(5, bounds.maximum().y());
            statement.setDouble(6, bounds.minimum().z());
            statement.setDouble(7, bounds.maximum().z());
            statement.executeUpdate();
        }
    }

    private Waypoint decodePayload(String payloadJson) throws SQLException {
        if (payloadJson == null) {
            throw new SQLException("Legacy waypoint row has no durable payload");
        }
        return codec.decode(payloadJson);
    }

    private static String toFtsQuery(String query) {
        final String normalized = WaypointNames.normalize(query);
        return Arrays.stream(normalized.split(" +"))
            .filter(token -> !token.isEmpty())
            .map(token -> "\"" + token.replace("\"", "\"\"") + "\"")
            .collect(Collectors.joining(" AND "));
    }

    private static int bindInstant(
        PreparedStatement statement,
        int firstParameter,
        Instant instant
    ) throws SQLException {
        statement.setLong(firstParameter++, instant.getEpochSecond());
        statement.setLong(firstParameter++, instant.getEpochSecond());
        statement.setInt(firstParameter++, instant.getNano());
        return firstParameter;
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > MAXIMUM_QUERY_LIMIT) {
            throw new IllegalArgumentException(
                "Query limit must be between 1 and " + MAXIMUM_QUERY_LIMIT
            );
        }
    }

    private static <T> T inTransaction(
        Connection connection,
        TransactionOperation<T> operation
    ) throws SQLException {
        final boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            final T result = operation.execute();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    @FunctionalInterface
    public interface SqlOperation<T> {
        T execute(Connection connection) throws SQLException;
    }

    public interface DatabaseAccess {
        <T> CompletableFuture<T> submit(SqlOperation<T> operation);
    }

    @FunctionalInterface
    private interface TransactionOperation<T> {
        T execute() throws SQLException;
    }

    private record ExistingWaypoint(
        long rowId,
        long revision,
        String payloadJson,
        String deletedAt
    ) {
    }
}
