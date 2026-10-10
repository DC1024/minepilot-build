package dev.mcai.companion.skills.transport;

import dev.mcai.companion.perception.PerceptionVec3;
import java.util.Objects;
import java.util.UUID;

/**
 * State of the vehicle the companion itself currently controls.
 *
 * <p>This is self/vehicle feedback, not an environment scan.</p>
 */
public record BoatState(
        UUID boatId,
        PerceptionVec3 position,
        float yawDegrees,
        PerceptionVec3 velocity,
        boolean horizontalCollision,
        boolean underwater
) {
    public BoatState {
        Objects.requireNonNull(boatId, "boatId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(velocity, "velocity");
        if (!Float.isFinite(yawDegrees)) {
            throw new IllegalArgumentException("Boat yaw must be finite");
        }
    }

    public double horizontalSpeed() {
        return Math.hypot(velocity.x(), velocity.z());
    }
}
