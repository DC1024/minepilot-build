package dev.mcai.companion.embodiment;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.phys.Vec3;

/**
 * Selects a conservative initial login position near a real player.
 *
 * <p>This is used only while creating the companion body. It is not exposed
 * to the model or navigation system and cannot be used as a gameplay
 * teleport. Candidates are bounded to an eight-block ring around the player,
 * use vanilla's player dismount safety calculation, which validates the
 * complete player collision box, dangerous/invalid spawn blocks, floor
 * height, and world border.</p>
 */
final class SafeCompanionSpawnLocator {
    private static final int MAX_HORIZONTAL_RADIUS = 8;
    private static final int[] Y_OFFSETS = {
            0, 1, -1, 2, -2, 3, -3, 4, -4
    };

    private SafeCompanionSpawnLocator() {
    }

    static Anchor capture(final ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        return new Anchor(
                (ServerLevel) player.level(),
                player.blockPosition(),
                player.getYRot()
        );
    }

    static Optional<Placement> locate(
            final Anchor anchor
    ) {
        Objects.requireNonNull(anchor, "anchor");
        Placement canopyFallback = null;
        for (final HorizontalOffset offset : offsets()) {
            for (final int yOffset : Y_OFFSETS) {
                final BlockPos feet = anchor.origin().offset(
                        offset.x(),
                        yOffset,
                        offset.z()
                );
                final Vec3 safe =
                        DismountHelper.findSafeDismountLocation(
                                EntityTypes.PLAYER,
                                anchor.level(),
                                feet,
                                true
                        );
                if (safe == null) {
                    continue;
                }
                final Placement placement = new Placement(
                            anchor.level(),
                            safe,
                            anchor.yaw()
                );
                if (hasStableGroundSupport(anchor.level(), safe)) {
                    return Optional.of(placement);
                }
                if (canopyFallback == null) {
                    canopyFallback = placement;
                }
            }
        }
        /* A canopy body is recoverable by the local descent skill; an absent
         * companion is not. Keep vanilla dismount safety as the final bounded
         * fallback when the whole nearby ring is forest canopy. */
        return Optional.ofNullable(canopyFallback);
    }

    /**
     * Vanilla dismount safety accepts leaf canopies as collision-safe. That is
     * correct for leaving a vehicle but a poor first login location: the new
     * companion can be isolated above an unseen trunk before it owns tools or
     * has mapped a descent. Prefer ordinary ground and keep tree tops out of
     * the initial spawn candidate set.
     */
    private static boolean hasStableGroundSupport(
            final ServerLevel level,
            final Vec3 feet
    ) {
        final BlockPos support = BlockPos.containing(
                feet.x(),
                feet.y() - 1.0E-4,
                feet.z()
        );
        final var state = level.getBlockState(support);
        return !state.is(BlockTags.LEAVES)
                && !state.is(BlockTags.LOGS);
    }

    private static List<HorizontalOffset> offsets() {
        final List<HorizontalOffset> result = new ArrayList<>();
        for (int radius = 1;
                radius <= MAX_HORIZONTAL_RADIUS;
                radius++) {
            for (int x = -radius; x <= radius; x++) {
                result.add(new HorizontalOffset(x, -radius));
                result.add(new HorizontalOffset(x, radius));
            }
            for (int z = -radius + 1; z < radius; z++) {
                result.add(new HorizontalOffset(-radius, z));
                result.add(new HorizontalOffset(radius, z));
            }
        }
        /*
         * Prefer an unoccupied adjacent square, but retain the real player's
         * own vanilla-safe feet position as the final bounded fallback. Two
         * player bodies may briefly overlap there, which is still safer and
         * more truthful than silently leaving the companion at an unrelated
         * world spawn when the login area is cramped.
         */
        result.add(new HorizontalOffset(0, 0));
        return List.copyOf(result);
    }

    record Anchor(
            ServerLevel level,
            BlockPos origin,
            float yaw
    ) {
        Anchor {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(origin, "origin");
            if (!Float.isFinite(yaw)) {
                throw new IllegalArgumentException(
                        "Spawn anchor yaw is invalid"
                );
            }
        }
    }

    record Placement(
            ServerLevel level,
            Vec3 position,
            float yaw
    ) {
        Placement {
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(position, "position");
            if (!Float.isFinite(yaw)) {
                throw new IllegalArgumentException(
                        "Spawn placement yaw is invalid"
                );
            }
        }

        void apply(final ServerPlayer player) {
            player.teleportTo(
                    level,
                    position.x(),
                    position.y(),
                    position.z(),
                    Set.of(),
                    yaw,
                    0.0F,
                    false
            );
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
        }
    }

    private record HorizontalOffset(int x, int z) {
    }
}
