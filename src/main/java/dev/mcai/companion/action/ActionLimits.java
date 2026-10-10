package dev.mcai.companion.action;

/**
 * Hard caps for natural-looking local input. These values never extend
 * vanilla reach or alter vanilla movement speed.
 */
public record ActionLimits(
        double movementAccelerationPerTick,
        float maximumYawDegreesPerTick,
        float maximumPitchDegreesPerTick,
        int miningTimeoutTicks
) {
    public static final double MAX_MOVEMENT_ACCELERATION = 1.0;
    public static final float MAX_TURN_DEGREES_PER_TICK = 90.0F;
    public static final int MAX_MINING_TIMEOUT_TICKS = 72_000;

    public ActionLimits {
        movementAccelerationPerTick = ActionValidation.finite(
                movementAccelerationPerTick,
                "movementAccelerationPerTick"
        );
        maximumYawDegreesPerTick = ActionValidation.finite(
                maximumYawDegreesPerTick,
                "maximumYawDegreesPerTick"
        );
        maximumPitchDegreesPerTick = ActionValidation.finite(
                maximumPitchDegreesPerTick,
                "maximumPitchDegreesPerTick"
        );
        if (movementAccelerationPerTick <= 0.0
                || movementAccelerationPerTick > MAX_MOVEMENT_ACCELERATION
                || maximumYawDegreesPerTick <= 0.0F
                || maximumYawDegreesPerTick > MAX_TURN_DEGREES_PER_TICK
                || maximumPitchDegreesPerTick <= 0.0F
                || maximumPitchDegreesPerTick > MAX_TURN_DEGREES_PER_TICK
                || miningTimeoutTicks < 20
                || miningTimeoutTicks > MAX_MINING_TIMEOUT_TICKS) {
            throw new IllegalArgumentException("Action limits are outside hard bounds");
        }
    }

    public static ActionLimits defaults() {
        return new ActionLimits(0.25, 20.0F, 15.0F, 7_200);
    }
}
