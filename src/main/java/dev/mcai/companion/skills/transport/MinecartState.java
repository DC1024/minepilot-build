package dev.mcai.companion.skills.transport;

import dev.mcai.companion.perception.PerceptionVec3;
import java.util.Objects;
import java.util.UUID;

/**
 * Live state owned by the minecart currently carrying the companion.
 */
public record MinecartState(
        UUID minecartId,
        PerceptionVec3 position,
        PerceptionVec3 velocity,
        boolean horizontalCollision
) {
    public MinecartState {
        Objects.requireNonNull(minecartId, "minecartId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(velocity, "velocity");
    }

    public double speed() {
        return velocity.length();
    }
}
