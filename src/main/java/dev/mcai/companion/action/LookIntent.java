package dev.mcai.companion.action;

import java.util.Objects;

/**
 * Absolute Minecraft yaw/pitch target. Pitch is deliberately limited to what
 * a normal client can aim at.
 */
public record LookIntent(float yawDegrees, float pitchDegrees) {
    /**
     * Yaw/pitch that points the eye exactly at one world point.
     *
     * <p>Fair play requires the body to turn before an interaction, because
     * every block and entity action revalidates that its target is genuinely
     * under the crosshair. Both the movement actuator and the interaction
     * bridge must therefore derive the same angles from the same geometry.
     */
    public static LookIntent toward(ActionVec3 eye, ActionVec3 target) {
        Objects.requireNonNull(eye, "eye");
        Objects.requireNonNull(target, "target");
        final double deltaX = target.x() - eye.x();
        final double deltaY = target.y() - eye.y();
        final double deltaZ = target.z() - eye.z();
        final double horizontal = Math.hypot(deltaX, deltaZ);
        if (horizontal <= 1.0E-6 && Math.abs(deltaY) <= 1.0E-6) {
            throw new IllegalArgumentException(
                    "Look target coincides with the eye"
            );
        }
        return new LookIntent(
                (float) Math.toDegrees(Math.atan2(-deltaX, deltaZ)),
                (float) Math.toDegrees(Math.atan2(-deltaY, horizontal))
        );
    }

    public LookIntent {
        yawDegrees = ActionMath.wrapDegrees(yawDegrees);
        pitchDegrees = ActionValidation.finite(pitchDegrees, "pitchDegrees");
        if (pitchDegrees < -90.0F || pitchDegrees > 90.0F) {
            throw new IllegalArgumentException("pitchDegrees must be in [-90, 90]");
        }
    }
}
