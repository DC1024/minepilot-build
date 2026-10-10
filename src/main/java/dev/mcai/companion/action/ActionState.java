package dev.mcai.companion.action;

import java.util.Objects;
import java.util.Optional;

public record ActionState(
        long actuatorTicks,
        MovementIntent requestedMovement,
        double appliedForward,
        double appliedStrafeLeft,
        Optional<LookIntent> requestedLook,
        boolean jumpQueued,
        Optional<MiningSnapshot> mining
) {
    public ActionState {
        if (actuatorTicks < 0) {
            throw new IllegalArgumentException("actuatorTicks must be non-negative");
        }
        Objects.requireNonNull(requestedMovement, "requestedMovement");
        ActionMath.normalizeMovement(appliedForward, appliedStrafeLeft);
        requestedLook = Objects.requireNonNull(requestedLook, "requestedLook");
        mining = Objects.requireNonNull(mining, "mining");
    }
}
