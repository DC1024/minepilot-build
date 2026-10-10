package dev.mcai.companion.memory.transport;

import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.skills.portal.PortalKind;
import dev.mcai.companion.waypoint.DimensionRef;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One directed cross-dimension edge proven by the companion's live body.
 *
 * <p>The positions are observations from successful traversals, not inferred
 * coordinate ratios or structure locations. A reverse trip is a separate
 * directed edge and must be independently observed.</p>
 */
public record VerifiedPortalEdge(
    String edgeId,
    UUID worldId,
    PortalKind portalKind,
    DimensionRef sourceDimension,
    PerceptionVec3 sourcePosition,
    BlockCoordinate sourcePortalBlock,
    DimensionRef destinationDimension,
    PerceptionVec3 destinationPosition,
    BlockCoordinate destinationLandingBlock,
    Instant firstVerifiedAt,
    Instant lastVerifiedAt,
    long successCount,
    long revision
) {
    private static final Pattern EDGE_ID = Pattern.compile("[0-9a-f]{64}");

    public VerifiedPortalEdge {
        edgeId = Objects.requireNonNull(edgeId, "edgeId");
        if (!EDGE_ID.matcher(edgeId).matches()) {
            throw new IllegalArgumentException("Edge id must be a SHA-256 hex value");
        }
        Objects.requireNonNull(worldId, "worldId");
        Objects.requireNonNull(portalKind, "portalKind");
        Objects.requireNonNull(sourceDimension, "sourceDimension");
        Objects.requireNonNull(sourcePosition, "sourcePosition");
        Objects.requireNonNull(sourcePortalBlock, "sourcePortalBlock");
        Objects.requireNonNull(destinationDimension, "destinationDimension");
        Objects.requireNonNull(destinationPosition, "destinationPosition");
        Objects.requireNonNull(
            destinationLandingBlock,
            "destinationLandingBlock"
        );
        Objects.requireNonNull(firstVerifiedAt, "firstVerifiedAt");
        Objects.requireNonNull(lastVerifiedAt, "lastVerifiedAt");
        if (sourceDimension.equals(destinationDimension)) {
            throw new IllegalArgumentException(
                "Verified transport edges must cross dimensions"
            );
        }
        if (lastVerifiedAt.isBefore(firstVerifiedAt)) {
            throw new IllegalArgumentException(
                "Last verification cannot precede first verification"
            );
        }
        if (successCount < 1 || revision < 0
            || successCount - 1 != revision) {
            throw new IllegalArgumentException(
                "Success count and revision are inconsistent"
            );
        }
    }

    /**
     * A local, non-authoritative display value. Integrations must preserve the
     * data-not-instructions marker in the field name.
     */
    public String generatedLabelDataNotInstruction() {
        return "verified " + portalKind.name().toLowerCase()
            .replace('_', ' ') + " route";
    }
}
