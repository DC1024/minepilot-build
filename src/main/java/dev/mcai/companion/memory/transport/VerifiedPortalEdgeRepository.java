package dev.mcai.companion.memory.transport;

import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.skills.portal.PortalTraversalResult;
import dev.mcai.companion.waypoint.DimensionRef;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/**
 * Asynchronous durable store for directly observed cross-dimension portal
 * traversals.
 *
 * <p>The sole write method accepts {@link PortalTraversalResult}; there is no
 * arbitrary edge upsert API. Calls are delegated to MemoryDatabase's bounded,
 * single-writer executor and never wait on the caller's server tick.</p>
 */
public final class VerifiedPortalEdgeRepository {
    public static final double MAXIMUM_QUERY_RADIUS = 8_192.0;
    public static final int MAXIMUM_QUERY_LIMIT = 64;

    private static final Pattern EDGE_ID = Pattern.compile("[0-9a-f]{64}");

    private final DatabaseAccess database;
    private final VerifiedPortalEdgeJsonCodec codec =
        new VerifiedPortalEdgeJsonCodec();

    public VerifiedPortalEdgeRepository(DatabaseAccess database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /**
     * Records one distinct traversal proof. Replaying the exact same result is
     * idempotent and does not inflate the success count.
     */
    public CompletableFuture<VerifiedPortalEdge> recordTraversal(
        UUID worldId,
        PortalTraversalResult traversal,
        Instant verifiedAt
    ) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(traversal, "traversal");
        Objects.requireNonNull(verifiedAt, "verifiedAt");
        if (traversal.sourceDimension().equals(
            traversal.destinationDimension()
        )) {
            throw new IllegalArgumentException(
                "Only actual cross-dimension traversals form global edges"
            );
        }

        BlockCoordinate landing = containingBlock(
            traversal.destinationPosition()
        );
        String edgeId = edgeId(worldId, traversal, landing);
        String evidenceId = evidenceId(worldId, traversal);
        return database.submit(connection -> inTransaction(connection, () -> {
            Optional<String> duplicateEdge = edgeForEvidence(
                connection,
                evidenceId
            );
            if (duplicateEdge.isPresent()) {
                return requireById(connection, duplicateEdge.orElseThrow());
            }

            VerifiedPortalEdge existing = loadById(connection, edgeId)
                .orElse(null);
            VerifiedPortalEdge updated;
            if (existing == null) {
                updated = new VerifiedPortalEdge(
                    edgeId,
                    worldId,
                    traversal.portalKind(),
                    traversal.sourceDimension(),
                    traversal.sourcePosition(),
                    traversal.sourcePortalBlock(),
                    traversal.destinationDimension(),
                    traversal.destinationPosition(),
                    landing,
                    verifiedAt,
                    verifiedAt,
                    1,
                    0
                );
            } else {
                validateIdentity(
                    existing,
                    worldId,
                    traversal,
                    landing
                );
                if (existing.successCount() == Long.MAX_VALUE
                    || existing.revision() == Long.MAX_VALUE) {
                    throw new IllegalStateException(
                        "Portal edge counters are exhausted"
                    );
                }
                boolean newest = !verifiedAt.isBefore(
                    existing.lastVerifiedAt()
                );
                updated = new VerifiedPortalEdge(
                    existing.edgeId(),
                    existing.worldId(),
                    existing.portalKind(),
                    existing.sourceDimension(),
                    newest
                        ? traversal.sourcePosition()
                        : existing.sourcePosition(),
                    existing.sourcePortalBlock(),
                    existing.destinationDimension(),
                    newest
                        ? traversal.destinationPosition()
                        : existing.destinationPosition(),
                    existing.destinationLandingBlock(),
                    existing.firstVerifiedAt(),
                    newest ? verifiedAt : existing.lastVerifiedAt(),
                    existing.successCount() + 1,
                    existing.revision() + 1
                );
            }

            upsertEdge(connection, updated);
            replaceSpatialIndex(connection, updated);
            insertEvidence(connection, evidenceId, updated.edgeId(), verifiedAt);
            return updated;
        }));
    }

    /**
     * Returns directed exits whose verified source pose is within the bounded
     * radius in the caller's current dimension.
     */
    public CompletableFuture<List<VerifiedPortalEdge>> findNearby(
        UUID worldId,
        DimensionRef currentDimension,
        PerceptionVec3 currentPosition,
        double radius,
        int limit
    ) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(currentDimension, "currentDimension");
        Objects.requireNonNull(currentPosition, "currentPosition");
        validateQueryBounds(radius, limit);
        double radiusSquared = radius * radius;

        return database.submit(connection -> {
            List<VerifiedPortalEdge> edges = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.payload_json
                FROM verified_portal_edge_source_bounds b
                JOIN verified_portal_edge e ON e.row_id = b.row_id
                WHERE e.world_id = ?
                  AND e.source_dimension = ?
                  AND b.min_x <= ?
                  AND b.max_x >= ?
                  AND b.min_y <= ?
                  AND b.max_y >= ?
                  AND b.min_z <= ?
                  AND b.max_z >= ?
                  AND (
                    (e.source_x - ?) * (e.source_x - ?)
                    + (e.source_y - ?) * (e.source_y - ?)
                    + (e.source_z - ?) * (e.source_z - ?)
                  ) <= ?
                ORDER BY
                  (
                    (e.source_x - ?) * (e.source_x - ?)
                    + (e.source_y - ?) * (e.source_y - ?)
                    + (e.source_z - ?) * (e.source_z - ?)
                  ) ASC,
                  e.last_verified_at DESC,
                  e.edge_id ASC
                LIMIT ?
                """)) {
                int parameter = 1;
                statement.setString(parameter++, worldId.toString());
                statement.setString(parameter++, currentDimension.id());
                statement.setDouble(parameter++, currentPosition.x() + radius);
                statement.setDouble(parameter++, currentPosition.x() - radius);
                statement.setDouble(parameter++, currentPosition.y() + radius);
                statement.setDouble(parameter++, currentPosition.y() - radius);
                statement.setDouble(parameter++, currentPosition.z() + radius);
                statement.setDouble(parameter++, currentPosition.z() - radius);
                parameter = bindDistanceOrigin(
                    statement,
                    parameter,
                    currentPosition
                );
                statement.setDouble(parameter++, radiusSquared);
                parameter = bindDistanceOrigin(
                    statement,
                    parameter,
                    currentPosition
                );
                statement.setInt(parameter, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        VerifiedPortalEdge edge = codec.decode(
                            rows.getString(1)
                        );
                        if (!edge.worldId().equals(worldId)
                            || !edge.sourceDimension().equals(
                                currentDimension
                            )
                            || distanceSquared(
                                edge.sourcePosition(),
                                currentPosition
                            ) > radiusSquared) {
                            throw new SQLException(
                                "Portal edge index disagrees with payload"
                            );
                        }
                        edges.add(edge);
                    }
                }
            }
            return List.copyOf(edges);
        });
    }

    /**
     * Returns directly observed arrival endpoints near the caller in its
     * current dimension.
     *
     * <p>An arrival endpoint is not promoted to a verified reverse edge. It
     * is only durable evidence that this body previously emerged at that
     * position. A caller must walk there, re-observe a real portal, and
     * complete a new ordinary traversal before a reverse edge can exist.</p>
     */
    public CompletableFuture<List<VerifiedPortalEdge>> findNearbyArrivals(
        UUID worldId,
        DimensionRef currentDimension,
        PerceptionVec3 currentPosition,
        double radius,
        int limit
    ) {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(currentDimension, "currentDimension");
        Objects.requireNonNull(currentPosition, "currentPosition");
        validateQueryBounds(radius, limit);
        final double radiusSquared = radius * radius;

        return database.submit(connection -> {
            final List<VerifiedPortalEdge> edges = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT payload_json
                FROM verified_portal_edge
                WHERE world_id = ?
                  AND destination_dimension = ?
                  AND destination_x BETWEEN ? AND ?
                  AND destination_y BETWEEN ? AND ?
                  AND destination_z BETWEEN ? AND ?
                  AND (
                    (destination_x - ?) * (destination_x - ?)
                    + (destination_y - ?) * (destination_y - ?)
                    + (destination_z - ?) * (destination_z - ?)
                  ) <= ?
                ORDER BY
                  (
                    (destination_x - ?) * (destination_x - ?)
                    + (destination_y - ?) * (destination_y - ?)
                    + (destination_z - ?) * (destination_z - ?)
                  ) ASC,
                  last_verified_at DESC,
                  edge_id ASC
                LIMIT ?
                """)) {
                int parameter = 1;
                statement.setString(parameter++, worldId.toString());
                statement.setString(parameter++, currentDimension.id());
                statement.setDouble(
                    parameter++,
                    currentPosition.x() - radius
                );
                statement.setDouble(
                    parameter++,
                    currentPosition.x() + radius
                );
                statement.setDouble(
                    parameter++,
                    currentPosition.y() - radius
                );
                statement.setDouble(
                    parameter++,
                    currentPosition.y() + radius
                );
                statement.setDouble(
                    parameter++,
                    currentPosition.z() - radius
                );
                statement.setDouble(
                    parameter++,
                    currentPosition.z() + radius
                );
                parameter = bindDistanceOrigin(
                    statement,
                    parameter,
                    currentPosition
                );
                statement.setDouble(parameter++, radiusSquared);
                parameter = bindDistanceOrigin(
                    statement,
                    parameter,
                    currentPosition
                );
                statement.setInt(parameter, limit);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        final VerifiedPortalEdge edge = codec.decode(
                            rows.getString(1)
                        );
                        if (!edge.worldId().equals(worldId)
                            || !edge.destinationDimension().equals(
                                currentDimension
                            )
                            || distanceSquared(
                                edge.destinationPosition(),
                                currentPosition
                            ) > radiusSquared) {
                            throw new SQLException(
                                "Portal arrival query disagrees with payload"
                            );
                        }
                        edges.add(edge);
                    }
                }
            }
            return List.copyOf(edges);
        });
    }

    public CompletableFuture<Optional<VerifiedPortalEdge>> findById(
        String edgeId
    ) {
        validateEdgeId(edgeId);
        return database.submit(connection -> loadById(connection, edgeId));
    }

    public CompletableFuture<Long> count(UUID worldId) {
        Objects.requireNonNull(worldId, "worldId");
        return database.submit(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM verified_portal_edge WHERE world_id = ?
                """)) {
                statement.setString(1, worldId.toString());
                try (ResultSet row = statement.executeQuery()) {
                    return row.next() ? row.getLong(1) : 0L;
                }
            }
        });
    }

    private void upsertEdge(
        Connection connection,
        VerifiedPortalEdge edge
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO verified_portal_edge(
                edge_id, world_id, portal_kind,
                source_dimension, source_x, source_y, source_z,
                source_portal_x, source_portal_y, source_portal_z,
                destination_dimension,
                destination_x, destination_y, destination_z,
                destination_block_x, destination_block_y,
                destination_block_z,
                first_verified_at, last_verified_at,
                success_count, revision, payload_json
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?
            )
            ON CONFLICT(edge_id) DO UPDATE SET
                source_x = excluded.source_x,
                source_y = excluded.source_y,
                source_z = excluded.source_z,
                destination_x = excluded.destination_x,
                destination_y = excluded.destination_y,
                destination_z = excluded.destination_z,
                last_verified_at = excluded.last_verified_at,
                success_count = excluded.success_count,
                revision = excluded.revision,
                payload_json = excluded.payload_json
            """)) {
            int parameter = 1;
            statement.setString(parameter++, edge.edgeId());
            statement.setString(parameter++, edge.worldId().toString());
            statement.setString(parameter++, edge.portalKind().name());
            statement.setString(parameter++, edge.sourceDimension().id());
            statement.setDouble(parameter++, edge.sourcePosition().x());
            statement.setDouble(parameter++, edge.sourcePosition().y());
            statement.setDouble(parameter++, edge.sourcePosition().z());
            statement.setInt(parameter++, edge.sourcePortalBlock().x());
            statement.setInt(parameter++, edge.sourcePortalBlock().y());
            statement.setInt(parameter++, edge.sourcePortalBlock().z());
            statement.setString(
                parameter++,
                edge.destinationDimension().id()
            );
            statement.setDouble(parameter++, edge.destinationPosition().x());
            statement.setDouble(parameter++, edge.destinationPosition().y());
            statement.setDouble(parameter++, edge.destinationPosition().z());
            statement.setInt(
                parameter++,
                edge.destinationLandingBlock().x()
            );
            statement.setInt(
                parameter++,
                edge.destinationLandingBlock().y()
            );
            statement.setInt(
                parameter++,
                edge.destinationLandingBlock().z()
            );
            statement.setString(
                parameter++,
                edge.firstVerifiedAt().toString()
            );
            statement.setString(
                parameter++,
                edge.lastVerifiedAt().toString()
            );
            statement.setLong(parameter++, edge.successCount());
            statement.setLong(parameter++, edge.revision());
            statement.setString(parameter, codec.encode(edge));
            statement.executeUpdate();
        }
    }

    private static void replaceSpatialIndex(
        Connection connection,
        VerifiedPortalEdge edge
    ) throws SQLException {
        long rowId = requireRowId(connection, edge.edgeId());
        try (PreparedStatement delete = connection.prepareStatement("""
                DELETE FROM verified_portal_edge_source_bounds
                WHERE row_id = ?
                """);
             PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO verified_portal_edge_source_bounds(
                    row_id, min_x, max_x, min_y, max_y, min_z, max_z
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            delete.setLong(1, rowId);
            delete.executeUpdate();
            insert.setLong(1, rowId);
            insert.setDouble(2, edge.sourcePosition().x());
            insert.setDouble(3, edge.sourcePosition().x());
            insert.setDouble(4, edge.sourcePosition().y());
            insert.setDouble(5, edge.sourcePosition().y());
            insert.setDouble(6, edge.sourcePosition().z());
            insert.setDouble(7, edge.sourcePosition().z());
            insert.executeUpdate();
        }
    }

    private static void insertEvidence(
        Connection connection,
        String evidenceId,
        String edgeId,
        Instant verifiedAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO verified_portal_traversal_evidence(
                evidence_id, edge_id, recorded_at
            ) VALUES (?, ?, ?)
            """)) {
            statement.setString(1, evidenceId);
            statement.setString(2, edgeId);
            statement.setString(3, verifiedAt.toString());
            statement.executeUpdate();
        }
    }

    private Optional<VerifiedPortalEdge> loadById(
        Connection connection,
        String edgeId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT payload_json FROM verified_portal_edge WHERE edge_id = ?
            """)) {
            statement.setString(1, edgeId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                VerifiedPortalEdge edge = codec.decode(row.getString(1));
                if (!edge.edgeId().equals(edgeId)) {
                    throw new SQLException(
                        "Portal edge id disagrees with payload"
                    );
                }
                return Optional.of(edge);
            }
        }
    }

    private VerifiedPortalEdge requireById(
        Connection connection,
        String edgeId
    ) throws SQLException {
        return loadById(connection, edgeId).orElseThrow(
            () -> new SQLException(
                "Traversal evidence references a missing portal edge"
            )
        );
    }

    private static Optional<String> edgeForEvidence(
        Connection connection,
        String evidenceId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT edge_id
            FROM verified_portal_traversal_evidence
            WHERE evidence_id = ?
            """)) {
            statement.setString(1, evidenceId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next()
                    ? Optional.of(row.getString(1))
                    : Optional.empty();
            }
        }
    }

    private static long requireRowId(
        Connection connection,
        String edgeId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT row_id FROM verified_portal_edge WHERE edge_id = ?
            """)) {
            statement.setString(1, edgeId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException(
                        "Portal edge disappeared during update"
                    );
                }
                return row.getLong(1);
            }
        }
    }

    private static void validateIdentity(
        VerifiedPortalEdge existing,
        UUID worldId,
        PortalTraversalResult traversal,
        BlockCoordinate landing
    ) {
        if (!existing.worldId().equals(worldId)
            || existing.portalKind() != traversal.portalKind()
            || !existing.sourceDimension().equals(
                traversal.sourceDimension()
            )
            || !existing.sourcePortalBlock().equals(
                traversal.sourcePortalBlock()
            )
            || !existing.destinationDimension().equals(
                traversal.destinationDimension()
            )
            || !existing.destinationLandingBlock().equals(landing)) {
            throw new IllegalStateException(
                "Portal edge identity hash collision or corrupt payload"
            );
        }
    }

    private static String edgeId(
        UUID worldId,
        PortalTraversalResult traversal,
        BlockCoordinate landing
    ) {
        String canonical = String.join(
            "\u0000",
            worldId.toString(),
            traversal.portalKind().name(),
            traversal.sourceDimension().id(),
            Integer.toString(traversal.sourcePortalBlock().x()),
            Integer.toString(traversal.sourcePortalBlock().y()),
            Integer.toString(traversal.sourcePortalBlock().z()),
            traversal.destinationDimension().id(),
            Integer.toString(landing.x()),
            Integer.toString(landing.y()),
            Integer.toString(landing.z())
        );
        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static String evidenceId(
        UUID worldId,
        PortalTraversalResult traversal
    ) {
        byte[] dimensions = String.join(
            "\u0000",
            worldId.toString(),
            traversal.portalKind().name(),
            traversal.sourceDimension().id(),
            traversal.destinationDimension().id()
        ).getBytes(StandardCharsets.UTF_8);
        ByteBuffer evidence = ByteBuffer.allocate(
            dimensions.length + 8 * 9 + 4 * 3
        );
        evidence.put(dimensions);
        evidence.putLong(traversal.sessionGeneration());
        evidence.putLong(
            Double.doubleToLongBits(traversal.sourcePosition().x())
        );
        evidence.putLong(
            Double.doubleToLongBits(traversal.sourcePosition().y())
        );
        evidence.putLong(
            Double.doubleToLongBits(traversal.sourcePosition().z())
        );
        evidence.putInt(traversal.sourcePortalBlock().x());
        evidence.putInt(traversal.sourcePortalBlock().y());
        evidence.putInt(traversal.sourcePortalBlock().z());
        evidence.putLong(
            Double.doubleToLongBits(traversal.destinationPosition().x())
        );
        evidence.putLong(
            Double.doubleToLongBits(traversal.destinationPosition().y())
        );
        evidence.putLong(
            Double.doubleToLongBits(traversal.destinationPosition().z())
        );
        evidence.putLong(traversal.startedAtTick());
        evidence.putLong(traversal.completedAtTick());
        return sha256(evidence.array());
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                "Java runtime has no SHA-256 implementation",
                exception
            );
        }
    }

    private static BlockCoordinate containingBlock(
        PerceptionVec3 position
    ) {
        return new BlockCoordinate(
            floorBlock(position.x(), "destination x"),
            floorBlock(position.y(), "destination y"),
            floorBlock(position.z(), "destination z")
        );
    }

    private static int floorBlock(double coordinate, String label) {
        double floored = Math.floor(coordinate);
        if (floored < Integer.MIN_VALUE || floored > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                label + " is outside Minecraft block-coordinate bounds"
            );
        }
        return (int) floored;
    }

    private static int bindDistanceOrigin(
        PreparedStatement statement,
        int parameter,
        PerceptionVec3 origin
    ) throws SQLException {
        statement.setDouble(parameter++, origin.x());
        statement.setDouble(parameter++, origin.x());
        statement.setDouble(parameter++, origin.y());
        statement.setDouble(parameter++, origin.y());
        statement.setDouble(parameter++, origin.z());
        statement.setDouble(parameter++, origin.z());
        return parameter;
    }

    private static double distanceSquared(
        PerceptionVec3 left,
        PerceptionVec3 right
    ) {
        double x = left.x() - right.x();
        double y = left.y() - right.y();
        double z = left.z() - right.z();
        return x * x + y * y + z * z;
    }

    private static void validateQueryBounds(double radius, int limit) {
        if (!Double.isFinite(radius)
            || radius <= 0.0
            || radius > MAXIMUM_QUERY_RADIUS) {
            throw new IllegalArgumentException(
                "Query radius must be finite and in (0,"
                    + MAXIMUM_QUERY_RADIUS + "]"
            );
        }
        if (limit < 1 || limit > MAXIMUM_QUERY_LIMIT) {
            throw new IllegalArgumentException(
                "Query limit must be between 1 and "
                    + MAXIMUM_QUERY_LIMIT
            );
        }
    }

    private static void validateEdgeId(String edgeId) {
        if (edgeId == null || !EDGE_ID.matcher(edgeId).matches()) {
            throw new IllegalArgumentException("Invalid portal edge id");
        }
    }

    private static <T> T inTransaction(
        Connection connection,
        TransactionOperation<T> operation
    ) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = operation.execute();
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
}
