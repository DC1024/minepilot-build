package dev.mcai.companion.memory.transport;

import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.waypoint.DimensionRef;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable result of one bounded nearby-edge lookup.
 *
 * <p>The world id is retained for integrity checking but is intentionally
 * omitted from the model-facing JSON projection.</p>
 */
public record VerifiedPortalEdgeRecallSnapshot(
    UUID worldId,
    DimensionRef queryDimension,
    PerceptionVec3 queryPosition,
    double radiusLimitBlocks,
    int maximumResults,
    Instant recalledAt,
    List<VerifiedPortalEdgeRecallEntry> matches
) {
    public static final int MAXIMUM_MODEL_RESULTS = 4;

    public VerifiedPortalEdgeRecallSnapshot {
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(queryDimension, "queryDimension");
        Objects.requireNonNull(queryPosition, "queryPosition");
        if (!Double.isFinite(radiusLimitBlocks)
            || radiusLimitBlocks <= 0.0
            || radiusLimitBlocks
                > VerifiedPortalEdgeRepository.MAXIMUM_QUERY_RADIUS) {
            throw new IllegalArgumentException(
                "Portal recall radius is outside its bound"
            );
        }
        if (maximumResults < 1
            || maximumResults > MAXIMUM_MODEL_RESULTS) {
            throw new IllegalArgumentException(
                "Portal recall result limit is outside its bound"
            );
        }
        Objects.requireNonNull(recalledAt, "recalledAt");
        matches = List.copyOf(Objects.requireNonNull(matches, "matches"));
        if (matches.size() > maximumResults) {
            throw new IllegalArgumentException(
                "Portal recall contains too many results"
            );
        }
        double previousDistance = -1.0;
        for (VerifiedPortalEdgeRecallEntry match : matches) {
            final VerifiedPortalEdge edge = Objects.requireNonNull(
                match,
                "match"
            ).edge();
            if (!edge.worldId().equals(worldId)
                || !edge.sourceDimension().equals(queryDimension)
                || match.distanceFromQueryPosition() > radiusLimitBlocks
                || match.distanceFromQueryPosition() < previousDistance) {
                throw new IllegalArgumentException(
                    "Portal recall result disagrees with its bounded query"
                );
            }
            previousDistance = match.distanceFromQueryPosition();
        }
    }
}
