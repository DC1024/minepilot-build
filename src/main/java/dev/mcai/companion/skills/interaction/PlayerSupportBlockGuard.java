package dev.mcai.companion.skills.interaction;

import dev.mcai.companion.navigation.GridPos;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Prevents an ordinary mining action from removing the collision surface
 * currently carrying the companion.
 *
 * <p>This is an action-boundary guard, not hidden perception. It discloses
 * no block to the planner and runs only after a ray-bound mining target has
 * already been selected. A skill that genuinely needs the block must first
 * walk the player onto another observed support, just as a careful Hardcore
 * player would.</p>
 */
public final class PlayerSupportBlockGuard {
    private static final double CONTACT_TOLERANCE = 0.125D;
    private static final double OVERLAP_EPSILON = 1.0E-7D;

    private PlayerSupportBlockGuard() {
    }

    public static boolean protects(
            final ServerPlayer player,
            final GridPos target
    ) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(target, "target");
        if (!player.onGround()) {
            return false;
        }
        final BlockPos position = new BlockPos(
                target.x(),
                target.y(),
                target.z()
        );
        final VoxelShape collision = player.level()
                .getBlockState(position)
                .getCollisionShape(
                        player.level(),
                        position,
                        CollisionContext.of(player)
                );
        return protects(
                player.getBoundingBox(),
                true,
                target,
                collision
        );
    }

    static boolean protects(
            final AABB playerBounds,
            final boolean onGround,
            final GridPos target,
            final VoxelShape localCollision
    ) {
        Objects.requireNonNull(playerBounds, "playerBounds");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(localCollision, "localCollision");
        if (!onGround || localCollision.isEmpty()) {
            return false;
        }
        for (AABB localBox : localCollision.toAabbs()) {
            final AABB worldBox = localBox.move(
                    target.x(),
                    target.y(),
                    target.z()
            );
            final boolean horizontalOverlap =
                    playerBounds.maxX
                            > worldBox.minX + OVERLAP_EPSILON
                    && playerBounds.minX
                            < worldBox.maxX - OVERLAP_EPSILON
                    && playerBounds.maxZ
                            > worldBox.minZ + OVERLAP_EPSILON
                    && playerBounds.minZ
                            < worldBox.maxZ - OVERLAP_EPSILON;
            final double verticalGap =
                    playerBounds.minY - worldBox.maxY;
            if (horizontalOverlap
                    && verticalGap >= -OVERLAP_EPSILON
                    && verticalGap <= CONTACT_TOLERANCE) {
                return true;
            }
        }
        return false;
    }
}
