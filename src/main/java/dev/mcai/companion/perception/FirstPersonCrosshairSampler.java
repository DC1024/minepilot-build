package dev.mcai.companion.perception;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Samples only the block surface under a player's current centre crosshair.
 *
 * <p>The result is deliberately narrower than semantic scene perception. It
 * exists for local 20 TPS interaction confirmation after a turn and exposes
 * no hidden block, structure, entity-radar, or unloaded-chunk information.</p>
 */
public final class FirstPersonCrosshairSampler {
    private FirstPersonCrosshairSampler() {
    }

    public static Optional<VisibleBlockFace> sample(
            final ServerPlayer player
    ) {
        if (player == null
                || player.isRemoved()
                || player.connection == null) {
            return Optional.empty();
        }
        final ServerLevel level = player.level();
        final Vec3 eye = player.getEyePosition();
        final Vec3 end = eye.add(
                player.getViewVector(1.0F).scale(
                        player.blockInteractionRange()
                )
        );
        if (!rayIsAlreadyLoaded(level, eye, end)) {
            return Optional.empty();
        }
        final BlockHitResult hit = level.clip(new ClipContext(
                eye,
                end,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player
        ));
        if (hit.getType() != HitResult.Type.BLOCK
                || !level.isLoaded(hit.getBlockPos())) {
            return Optional.empty();
        }
        final BlockPos position = hit.getBlockPos();
        final BlockState state = level.getBlockState(position);
        return Optional.of(new VisibleBlockFace(
                new BlockCoordinate(
                        position.getX(),
                        position.getY(),
                        position.getZ()
                ),
                BuiltInRegistries.BLOCK.getKey(
                        state.getBlock()
                ).toString(),
                hit.getDirection().getName(),
                new PerceptionVec3(
                        hit.getLocation().x(),
                        hit.getLocation().y(),
                        hit.getLocation().z()
                ),
                hit.getLocation().distanceTo(eye),
                PerceptionProvenance.BLOCK_SURFACE_RAY_CLIP,
                visibleStateProperties(state),
                topSupportAffordance(
                        state,
                        level,
                        position,
                        hit.getDirection()
                ),
                collisionAffordance(state, level, position),
                level.getRawBrightness(
                        position.relative(hit.getDirection()),
                        0
                )
        ));
    }

    private static boolean rayIsAlreadyLoaded(
            final ServerLevel level,
            final Vec3 start,
            final Vec3 end
    ) {
        final double distance = start.distanceTo(end);
        final int samples = Math.max(1, (int) Math.ceil(distance));
        for (int sample = 0; sample <= samples; sample++) {
            final Vec3 point = start.lerp(
                    end,
                    sample / (double) samples
            );
            if (!level.isLoaded(BlockPos.containing(point))) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, String> visibleStateProperties(
            final BlockState state
    ) {
        final Map<String, String> properties = new TreeMap<>();
        for (Property<?> property : state.getProperties().stream()
                .sorted(Comparator.comparing(Property::getName))
                .limit(VisibleBlockFace.MAX_STATE_PROPERTIES)
                .toList()) {
            final String name = property.getName();
            final String value = visibleStateValue(state, property);
            if (VisibleBlockFace.isSafeStateToken(name)
                    && VisibleBlockFace.isSafeStateToken(value)) {
                properties.put(name, value);
            }
        }
        return Map.copyOf(properties);
    }

    private static TopSupportAffordance topSupportAffordance(
            final BlockState state,
            final ServerLevel level,
            final BlockPos position,
            final Direction hitFace
    ) {
        return BlockShapeAffordances.topSupport(
                state,
                level,
                position,
                hitFace
        );
    }

    private static CollisionAffordance collisionAffordance(
            final BlockState state,
            final ServerLevel level,
            final BlockPos position
    ) {
        return state.getCollisionShape(level, position).isEmpty()
                ? CollisionAffordance.EMPTY
                : CollisionAffordance.OBSTRUCTED_OR_PARTIAL;
    }

    private static <T extends Comparable<T>> String visibleStateValue(
            final BlockState state,
            final Property<T> property
    ) {
        return property.getName(state.getValue(property));
    }
}
