package dev.mcai.companion.memory.transport;

import java.util.Objects;

/**
 * Bounded, model-facing view of one directly traversed portal edge.
 *
 * <p>The confidence value describes the strength of the stored traversal
 * evidence. It is deliberately not a promise that the portal still exists;
 * callers must use {@link VerifiedPortalEdge#lastVerifiedAt()} and locally
 * re-observe the portal before entering it.</p>
 */
public record VerifiedPortalEdgeRecallEntry(
    VerifiedPortalEdge edge,
    double distanceFromQueryPosition,
    double evidenceConfidence
) {
    public static final String CONFIDENCE_BASIS =
        "DIRECT_BODY_TRAVERSAL_COUNT_HEURISTIC_NOT_ROUTE_GUARANTEE";

    public VerifiedPortalEdgeRecallEntry {
        Objects.requireNonNull(edge, "edge");
        if (!Double.isFinite(distanceFromQueryPosition)
            || distanceFromQueryPosition < 0.0) {
            throw new IllegalArgumentException(
                "Portal edge recall distance is invalid"
            );
        }
        if (!Double.isFinite(evidenceConfidence)
            || evidenceConfidence < 0.0
            || evidenceConfidence > 1.0) {
            throw new IllegalArgumentException(
                "Portal edge recall confidence is invalid"
            );
        }
    }

    public static VerifiedPortalEdgeRecallEntry from(
        final VerifiedPortalEdge edge,
        final double distance
    ) {
        Objects.requireNonNull(edge, "edge");
        /*
         * One direct body traversal starts at 0.80. Independent successful
         * traversals monotonically strengthen evidence without ever claiming
         * certainty. This is a transparent heuristic, not a calibrated
         * probability and not a freshness guarantee.
         */
        final double confidence = Math.min(
            0.99,
            1.0 - 1.0 / (edge.successCount() + 4.0)
        );
        return new VerifiedPortalEdgeRecallEntry(
            edge,
            distance,
            confidence
        );
    }
}
