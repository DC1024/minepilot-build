package dev.mcai.companion.skills.core;

import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;
import java.util.UUID;

/**
 * Tick-local state legitimately owned by the companion player.
 *
 * <p>The semantic navigation map may be sampled less frequently, but local
 * movement must use a fresh pose each server tick. This type lets the runtime
 * refresh that pose without exposing a level, chunk, or block lookup to a
 * skill.</p>
 */
public record CoreSkillPose(
        UUID playerId,
        DimensionRef dimension,
        long gameTime,
        PerceptionVec3 position,
        PerceptionVec3 eyePosition,
        PerceptionVec3 lookDirection,
        boolean onGround,
        boolean inWater
) {
    public CoreSkillPose {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(dimension, "dimension");
        if (gameTime < 0) {
            throw new IllegalArgumentException("gameTime must be non-negative");
        }
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(eyePosition, "eyePosition");
        Objects.requireNonNull(lookDirection, "lookDirection");
        if (Math.abs(lookDirection.length() - 1.0) > 1.0E-6) {
            throw new IllegalArgumentException(
                    "lookDirection must be normalized"
            );
        }
    }
}
