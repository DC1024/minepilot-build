package dev.mcai.companion.navigation;

import dev.mcai.companion.perception.TopSupportAffordance;
import java.util.Objects;

/**
 * Fail-closed interpretation of the fair voxel evidence used by navigation.
 *
 * <p>{@link VoxelKind} is a semantic classification, not by itself proof that
 * a player's collision volume fits or that a generic solid has a safe top
 * face.  Navigation consumers must keep those two facts separate.</p>
 */
public final class NavigationEvidence {
    private NavigationEvidence() {
    }

    /**
     * Whether an observed passable voxel has enough evidence for local
     * traversal.  A single infinitesimal air ray is deliberately insufficient.
     * Liquids and climbable/open-door blocks may instead be known from their
     * directly ray-hit surface.
     */
    public static boolean hasTraversalClearance(
            final ObservedVoxel voxel
    ) {
        Objects.requireNonNull(voxel, "voxel");
        if (!voxel.kind().isPassable()) {
            return false;
        }
        return switch (voxel.kind()) {
            case AIR -> voxel.occupancyEvidence()
                    == OccupancyEvidence.MULTI_RAY_CLEAR
                    || voxel.occupancyEvidence()
                            == OccupancyEvidence.COLLISION_SHAPE_CLEAR
                    || voxel.occupancyEvidence()
                            == OccupancyEvidence.BODY_OCCUPIED;
            case WATER, LAVA, CLIMBABLE, OPEN_DOOR ->
                    voxel.occupancyEvidence()
                            == OccupancyEvidence.SURFACE_HIT
                    || voxel.occupancyEvidence()
                            == OccupancyEvidence.MULTI_RAY_CLEAR
                    || voxel.occupancyEvidence()
                            == OccupancyEvidence.BODY_OCCUPIED;
            case SOLID, CLOSED_DOOR -> false;
        };
    }

    /**
     * Future movement may consume clearance only when that evidence was
     * refreshed by the current semantic sample.
     */
    public static boolean hasFreshTraversalClearance(
            final ObservedVoxel voxel,
            final long snapshotRevision
    ) {
        Objects.requireNonNull(voxel, "voxel");
        return voxel.observationRevision() == snapshotRevision
                && hasTraversalClearance(voxel);
    }

    /**
     * A future land destination needs a top face explicitly observed in this
     * snapshot.  A generic SOLID classification never supplies this proof.
     */
    public static boolean isFreshStandingSupport(
            final ObservedVoxel voxel,
            final long snapshotRevision
    ) {
        Objects.requireNonNull(voxel, "voxel");
        return voxel.kind().supportsWeight()
                && voxel.observationRevision() == snapshotRevision
                && voxel.topSupportAffordance()
                        .safelySupportsStanding()
                && (voxel.occupancyEvidence()
                        == OccupancyEvidence.SURFACE_HIT
                    || voxel.occupancyEvidence()
                        == OccupancyEvidence.BODY_CONTACT);
    }

    /**
     * The player's current start cell may use same-sample on-ground body
     * contact even when its supporting block's top was not in the view fan.
     * This exception must never be used for a future destination.
     */
    public static boolean supportsCurrentBody(
            final ObservedVoxel voxel,
            final long snapshotRevision
    ) {
        Objects.requireNonNull(voxel, "voxel");
        return isFreshStandingSupport(voxel, snapshotRevision)
                || voxel.kind().supportsWeight()
                && voxel.observationRevision() == snapshotRevision
                && voxel.occupancyEvidence()
                        == OccupancyEvidence.BODY_CONTACT;
    }

    /**
     * Whether the companion recently proved a supporting block by physically
     * standing on it. This is deliberately separate from visual top-face
     * evidence: a crop can hide the farmland top while prior body contact
     * remains direct, player-equivalent evidence that the block carried the
     * body. Callers must still require current body/head clearance and a
     * small, task-specific age bound before using this for a future step.
     */
    public static boolean isRecentBodyContactSupport(
            final ObservedVoxel voxel,
            final long snapshotRevision,
            final long maximumRevisionAge
    ) {
        Objects.requireNonNull(voxel, "voxel");
        if (maximumRevisionAge < 0) {
            throw new IllegalArgumentException(
                    "Maximum revision age must be non-negative"
            );
        }
        return voxel.kind().supportsWeight()
                && voxel.occupancyEvidence()
                        == OccupancyEvidence.BODY_CONTACT
                && voxel.observationRevision() <= snapshotRevision
                && snapshotRevision - voxel.observationRevision()
                        <= maximumRevisionAge;
    }

    /**
     * A closed door may be interacted with only when its obstacle surface was
     * directly observed in the current snapshot.
     */
    public static boolean isFreshClosedDoor(
            final ObservedVoxel voxel,
            final long snapshotRevision
    ) {
        Objects.requireNonNull(voxel, "voxel");
        return voxel.kind() == VoxelKind.CLOSED_DOOR
                && voxel.observationRevision() == snapshotRevision
                && voxel.occupancyEvidence()
                        == OccupancyEvidence.SURFACE_HIT;
    }
}
