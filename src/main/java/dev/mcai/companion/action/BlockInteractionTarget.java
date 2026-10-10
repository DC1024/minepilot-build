package dev.mcai.companion.action;

import java.util.Objects;

/**
 * A concrete, first-person block outline hit.
 *
 * <p>The hit must remain inside the declared block voxel. It cannot be
 * required to lie on the voxel's outer boundary: thin and inset vanilla
 * outlines (iron bars, panes, buttons, repeaters, and similar blocks) report
 * the direction of the struck outline face while their hit point is inside
 * the voxel. The fair actuator independently replays the player's OUTLINE ray
 * and requires the same block, direction, and nearby hit point before it
 * dispatches an action.</p>
 */
public record BlockInteractionTarget(
        int x,
        int y,
        int z,
        BlockFace face,
        ActionVec3 hitPoint
) {
    private static final double SURFACE_EPSILON = 1.0E-3;

    public BlockInteractionTarget {
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(hitPoint, "hitPoint");
        if (!inside(hitPoint.x(), x)
                || !inside(hitPoint.y(), y)
                || !inside(hitPoint.z(), z)) {
            throw new IllegalArgumentException(
                    "hitPoint must lie inside the declared block voxel"
            );
        }
    }

    private static boolean inside(double coordinate, int lower) {
        return coordinate >= lower - SURFACE_EPSILON
                && coordinate <= lower + 1.0 + SURFACE_EPSILON;
    }

}
