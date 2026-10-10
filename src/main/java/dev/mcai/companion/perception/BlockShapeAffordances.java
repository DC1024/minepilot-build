package dev.mcai.companion.perception;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Reduces collision geometry for an already ray-visible block to bounded
 * player affordances. Raw boxes never leave the server-side perception
 * boundary.
 */
final class BlockShapeAffordances {
    private static final double EDGE_EPSILON = 1.0E-7;

    private BlockShapeAffordances() {
    }

    static TopSupportAffordance topSupport(
            final BlockState state,
            final ServerLevel level,
            final BlockPos position,
            final Direction hitFace
    ) {
        if (hitFace != Direction.UP) {
            return TopSupportAffordance.UNKNOWN;
        }
        if (state.isCollisionShapeFullBlock(level, position)
                && state.isFaceSturdy(
                        level,
                        position,
                        Direction.UP
                )) {
            return TopSupportAffordance.STURDY_FULL_TOP;
        }
        final boolean fullFootprint = state
                .getCollisionShape(level, position)
                .toAabbs().stream().anyMatch(box ->
                        box.minX <= EDGE_EPSILON
                                && box.maxX >= 1.0 - EDGE_EPSILON
                                && box.minZ <= EDGE_EPSILON
                                && box.maxZ >= 1.0 - EDGE_EPSILON
                                && box.maxY > EDGE_EPSILON
                );
        return fullFootprint
                ? TopSupportAffordance.WALKABLE_FULL_FOOTPRINT_TOP
                : TopSupportAffordance.NON_STURDY_OR_PARTIAL;
    }
}
