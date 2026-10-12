package dev.mcai.companion.action;

import java.util.Objects;
import java.util.Optional;

/**
 * Minecraft-independent 20 TPS intent state machine. It smooths input but
 * never changes vanilla movement speed.
 */
final class LocalInputController {
    /**
     * Angular acceleration ceiling, in degrees per tick squared. A turn ramps
     * up to the per-tick yaw ceiling of {@link ActionLimits#defaults()} in
     * four ticks, mirroring how {@code movementAccelerationPerTick = 0.25}
     * ramps up to full movement speed in four ticks.
     */
    private static final float MAX_TURN_ACCELERATION_DEGREES_PER_TICK = 5.0F;

    private final ActionLimits limits;
    private MovementIntent requestedMovement = MovementIntent.STOPPED;
    private LookIntent requestedLook;
    private double appliedForward;
    private double appliedStrafeLeft;
    private float appliedYawRate;
    private float appliedPitchRate;
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
            float yawRemaining = ActionMath.wrapDegrees(
                    requestedLook.yawDegrees() - ActionMath.wrapDegrees(currentYaw)
            );
            float pitchRemaining = requestedLook.pitchDegrees() - currentPitch;
            appliedYawRate = ActionMath.approach(
                    appliedYawRate,
                    turnRateToward(yawRemaining, limits.maximumYawDegreesPerTick()),
                    MAX_TURN_ACCELERATION_DEGREES_PER_TICK
            );
            appliedPitchRate = ActionMath.approach(
                    appliedPitchRate,
                    turnRateToward(pitchRemaining, limits.maximumPitchDegreesPerTick()),
                    MAX_TURN_ACCELERATION_DEGREES_PER_TICK
            );
            // The last tick of a turn moves exactly the remaining error, so
            // ramping down never overshoots the requested look.
            if (Math.abs(yawRemaining) <= Math.abs(appliedYawRate)) {
                appliedYawRate = yawRemaining;
            }
            if (Math.abs(pitchRemaining) <= Math.abs(appliedPitchRate)) {
                appliedPitchRate = pitchRemaining;
            }
            yaw = ActionMath.wrapDegrees(currentYaw + appliedYawRate);
            pitch = currentPitch + appliedPitchRate;
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

    private static float turnRateToward(float remainingDegrees, float maximumRate) {
        return Math.copySign(
                Math.min(Math.abs(remainingDegrees), maximumRate),
                remainingDegrees
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
