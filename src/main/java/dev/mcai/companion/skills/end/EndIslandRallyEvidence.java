package dev.mcai.companion.skills.end;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.NavigationEvidence;
import dev.mcai.companion.navigation.ObservedVoxel;
import dev.mcai.companion.navigation.OccupancyEvidence;
import dev.mcai.companion.perception.BlockCoordinate;
import dev.mcai.companion.skills.core.CoreSkillFrame;
import java.util.Objects;

/**
 * Fair, current-pose evidence for handing the End island from ingress to
 * dragon combat. A sticky route milestone is not sufficient: the body must
 * still be standing on a freshly observed natural End-stone cell with two
 * blocks of clear space in the same navigation revision.
 */
public final class EndIslandRallyEvidence {
    private static final String END_STONE = "minecraft:end_stone";
    private static final double CENTER_TOLERANCE = 0.72;
    private static final long MAX_HEAD_CLEAR_MEMORY_REVISIONS = 20L;

    private EndIslandRallyEvidence() {
    }

    public static boolean supportsCurrentPose(
            final CoreSkillFrame frame,
            final double maximumRadius
    ) {
        return supportsCurrentStandingCell(frame, maximumRadius)
                && hasFreshSkyObservation(frame);
    }

    /** Fair support/clearance evidence before the controller aims upward. */
    public static boolean supportsCurrentStandingCell(
            final CoreSkillFrame frame,
            final double maximumRadius
    ) {
        Objects.requireNonNull(frame, "frame");
        if (!frame.onGround()
                || frame.inWater()
                || !Double.isFinite(maximumRadius)
                || EndArenaTopology.horizontalRadius(frame.position())
                    > maximumRadius
                || frame.navigation().revision()
                    != frame.observationRevision()) {
            return false;
        }
        final GridPos support = frame.feet().below();
        final double centerDistance = Math.hypot(
                frame.position().x() - (support.x() + 0.5),
                frame.position().z() - (support.z() + 0.5)
        );
        if (centerDistance > CENTER_TOLERANCE
                || !safeFreshDestination(frame, support)) {
            return false;
        }
        return frame.visibleBlockFaces().stream().anyMatch(face ->
                END_STONE.equals(face.blockTypeId())
                        && "up".equals(face.face())
                        && face.topSupportAffordance()
                            .safelySupportsStanding()
                        && grid(face.block()).equals(support)
        );
    }

    /**
     * The latest semantic frame must be an upward-looking sky check with no
     * visible block in the current body column above the head. This prevents
     * handing a body from a low End-stone tunnel to the dragon controller.
     */
    public static boolean hasFreshSkyObservation(
            final CoreSkillFrame frame
    ) {
        Objects.requireNonNull(frame, "frame");
        return frame.lookDirection().y() >= 0.25
                && !visibleOverheadObstruction(frame);
    }

    private static boolean safeFreshDestination(
            final CoreSkillFrame frame,
            final GridPos support
    ) {
        final long revision = frame.navigation().revision();
        return frame.navigation().voxelAt(support).filter(voxel ->
                        NavigationEvidence.isFreshStandingSupport(
                                voxel,
                                revision
                        )).isPresent()
                && frame.navigation().voxelAt(support.above()).filter(voxel ->
                        NavigationEvidence.hasFreshTraversalClearance(
                                voxel,
                                revision
                        )).isPresent()
                && (frame.navigation().voxelAt(support.above(2)).filter(
                        voxel -> NavigationEvidence
                                .hasFreshTraversalClearance(voxel, revision)
                ).isPresent()
                    /* The navigation cache is deliberately incremental and
                     * may retain an older clear head voxel while the current
                     * first-person frame has just performed the required
                     * upward sky check.  The latter is stronger evidence for
                     * this exact standing cell than a stale cache revision,
                     * provided the current view contains no overhead block. */
                    || (headClearMemoryIsBounded(frame, support)
                        && !visibleOverheadObstruction(frame)));
    }

    private static boolean headClearMemoryIsBounded(
            final CoreSkillFrame frame,
            final GridPos support
    ) {
        final long revision = frame.navigation().revision();
        return frame.navigation().voxelAt(support.above(2)).filter(voxel ->
                voxel.kind().isPassable()
                    && voxel.occupancyEvidence() != OccupancyEvidence.UNKNOWN
                    && revision >= voxel.observationRevision()
                    && revision - voxel.observationRevision()
                        <= MAX_HEAD_CLEAR_MEMORY_REVISIONS
        ).isPresent();
    }

    private static boolean visibleOverheadObstruction(
            final CoreSkillFrame frame
    ) {
        final GridPos feet = frame.feet();
        return frame.visibleBlockFaces().stream().anyMatch(face -> {
            final BlockCoordinate block = face.block();
            return block.x() == feet.x()
                    && block.z() == feet.z()
                    && block.y() >= feet.y() + 2
                    && block.y() <= feet.y() + 8
                    && !"minecraft:air".equals(face.blockTypeId());
        });
    }

    private static GridPos grid(final BlockCoordinate block) {
        return new GridPos(block.x(), block.y(), block.z());
    }
}
