package dev.mcai.companion.action;

/**
 * Player-relative movement. Positive strafe is left, matching Minecraft's
 * local travel-vector convention.
 */
public record MovementIntent(
        double forward,
        double strafeLeft,
        boolean sprint,
        boolean sneak
) {
    public static final MovementIntent STOPPED =
            new MovementIntent(0.0, 0.0, false, false);

    public MovementIntent {
        forward = ActionValidation.closedUnit(forward, "forward");
        strafeLeft = ActionValidation.closedUnit(strafeLeft, "strafeLeft");
    }

    ActionMath.MovementAxes normalizedAxes() {
        return ActionMath.normalizeMovement(forward, strafeLeft);
    }
}
