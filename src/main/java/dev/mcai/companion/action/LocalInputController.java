package dev.mcai.companion.action;

import java.util.Objects;
import java.util.Optional;

/**
 * Minecraft-independent 20 TPS intent state machine. It smooths input but
 * never changes vanilla movement speed.
 */
final class LocalInputController {
    private final ActionLimits limits;
    private MovementIntent requestedMovement = MovementIntent.STOPPED;
    private LookIntent requestedLook;
    private double appliedForward;
    private double appliedStrafeLeft;
    private boolean jumpQueued;
    private long ticks;

    LocalInputController(ActionLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    void setMovement(MovementIntent movement) {
        requestedMovement = Objects.requireNonNull(movement, "movement");
    }

    void setLook(LookIntent look) {
        requestedLook = Objects.requireNonNull(look, "look");
    }

    void queueJump() {
        jumpQueued = true;
    }

    void stopImmediately() {
        requestedMovement = MovementIntent.STOPPED;
        appliedForward = 0.0;
        appliedStrafeLeft = 0.0;
        jumpQueued = false;
    }

    InputFrame nextFrame(float currentYaw, float currentPitch) {
        ActionMath.MovementAxes target = requestedMovement.normalizedAxes();
        appliedForward = ActionMath.approach(
                appliedForward,
                target.forward(),
                limits.movementAccelerationPerTick()
        );
        appliedStrafeLeft = ActionMath.approach(
                appliedStrafeLeft,
                target.strafeLeft(),
                limits.movementAccelerationPerTick()
        );
        ActionMath.MovementAxes normalized =
                ActionMath.normalizeMovement(appliedForward, appliedStrafeLeft);
        appliedForward = normalized.forward();
        appliedStrafeLeft = normalized.strafeLeft();

        float yaw = currentYaw;
        float pitch = currentPitch;
        boolean rotationChanged = false;
        if (requestedLook != null) {
            yaw = ActionMath.approachAngle(
                    currentYaw,
                    requestedLook.yawDegrees(),
                    limits.maximumYawDegreesPerTick()
            );
            pitch = ActionMath.approach(
                    currentPitch,
                    requestedLook.pitchDegrees(),
                    limits.maximumPitchDegreesPerTick()
            );
            rotationChanged = yaw != currentYaw || pitch != currentPitch;
        }

        boolean jump = jumpQueued;
        jumpQueued = false;
        ticks++;
        return new InputFrame(
                appliedForward,
                appliedStrafeLeft,
                requestedMovement.sprint(),
                requestedMovement.sneak(),
                jump,
                yaw,
                pitch,
                rotationChanged
        );
    }

    ActionState snapshot(Optional<MiningSnapshot> mining) {
        return new ActionState(
                ticks,
                requestedMovement,
                appliedForward,
                appliedStrafeLeft,
                Optional.ofNullable(requestedLook),
                jumpQueued,
                mining
        );
    }
}
