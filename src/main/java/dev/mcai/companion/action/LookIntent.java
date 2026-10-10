package dev.mcai.companion.action;

/**
 * Absolute Minecraft yaw/pitch target. Pitch is deliberately limited to what
 * a normal client can aim at.
 */
public record LookIntent(float yawDegrees, float pitchDegrees) {
    public LookIntent {
        yawDegrees = ActionMath.wrapDegrees(yawDegrees);
        pitchDegrees = ActionValidation.finite(pitchDegrees, "pitchDegrees");
        if (pitchDegrees < -90.0F || pitchDegrees > 90.0F) {
            throw new IllegalArgumentException("pitchDegrees must be in [-90, 90]");
        }
    }
}
