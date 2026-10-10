package dev.mcai.companion.skills.portal;

import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;
import java.util.Optional;

/**
 * Trusted evidence produced only after the companion's live body traverses.
 *
 * <p>Both endpoints are measured from actual player poses. The result never
 * uses a claimed destination, coordinate scaling guess, or structure query.</p>
 */
public record PortalTraversalResult(
        PortalKind portalKind,
        long sessionGeneration,
        DimensionRef sourceDimension,
        PerceptionVec3 sourcePosition,
        BlockCoordinate sourcePortalBlock,
        DimensionRef destinationDimension,
        PerceptionVec3 destinationPosition,
        long startedAtTick,
        long completedAtTick,
        Optional<DimensionRef> expectedDestination
) {
    public PortalTraversalResult {
        Objects.requireNonNull(portalKind, "portalKind");
        if (sessionGeneration < 0
                || startedAtTick < 0
                || completedAtTick < startedAtTick) {
            throw new IllegalArgumentException(
                    "Traversal counters must be non-negative and monotonic"
            );
        }
        Objects.requireNonNull(sourceDimension, "sourceDimension");
        Objects.requireNonNull(sourcePosition, "sourcePosition");
        Objects.requireNonNull(sourcePortalBlock, "sourcePortalBlock");
        Objects.requireNonNull(
                destinationDimension,
                "destinationDimension"
        );
        Objects.requireNonNull(destinationPosition, "destinationPosition");
        expectedDestination = Objects.requireNonNull(
                expectedDestination,
                "expectedDestination"
        );
    }

    public boolean destinationMatchesExpectation() {
        return expectedDestination.isEmpty()
                || expectedDestination.orElseThrow()
                .equals(destinationDimension);
    }
}
