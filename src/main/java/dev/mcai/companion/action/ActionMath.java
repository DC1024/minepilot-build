package dev.mcai.companion.action;

/**
 * Pure, deterministic math used by the tick-local actuator.
 */
public final class ActionMath {
    private static final double EPSILON = 1.0E-9;

    private ActionMath() {
    }

    public static double approach(double current, double target, double maximumDelta) {
        current = ActionValidation.finite(current, "current");
        target = ActionValidation.finite(target, "target");
        maximumDelta = ActionValidation.finite(maximumDelta, "maximumDelta");
        if (maximumDelta <= 0.0) {
            throw new IllegalArgumentException("maximumDelta must be positive");
        }
        double difference = target - current;
        if (Math.abs(difference) <= maximumDelta) {
            return target;
        }
        return current + Math.copySign(maximumDelta, difference);
    }

    public static float approach(float current, float target, float maximumDelta) {
        return (float) approach((double) current, target, maximumDelta);
    }

    public static float approachAngle(
            float currentDegrees,
            float targetDegrees,
            float maximumDelta
    ) {
        currentDegrees = wrapDegrees(currentDegrees);
        targetDegrees = wrapDegrees(targetDegrees);
        maximumDelta = ActionValidation.finite(maximumDelta, "maximumDelta");
        if (maximumDelta <= 0.0F || maximumDelta > 180.0F) {
            throw new IllegalArgumentException("maximumDelta must be in (0, 180]");
        }
        float difference = wrapDegrees(targetDegrees - currentDegrees);
        if (Math.abs(difference) <= maximumDelta) {
            return targetDegrees;
        }
        return wrapDegrees(currentDegrees + Math.copySign(maximumDelta, difference));
    }

    public static float wrapDegrees(float degrees) {
        degrees = ActionValidation.finite(degrees, "degrees");
        float wrapped = degrees % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped == 0.0F ? 0.0F : wrapped;
    }

    public static MovementAxes normalizeMovement(double forward, double strafeLeft) {
        forward = ActionValidation.closedUnit(forward, "forward");
        strafeLeft = ActionValidation.closedUnit(strafeLeft, "strafeLeft");
        double magnitude = Math.hypot(forward, strafeLeft);
        if (magnitude <= 1.0) {
            return new MovementAxes(forward, strafeLeft);
        }
        return new MovementAxes(forward / magnitude, strafeLeft / magnitude);
    }

    static boolean approximately(double left, double right) {
        return Math.abs(left - right) <= EPSILON;
    }

    public record MovementAxes(double forward, double strafeLeft) {
        public MovementAxes {
            forward = ActionValidation.closedUnit(forward, "forward");
            strafeLeft = ActionValidation.closedUnit(strafeLeft, "strafeLeft");
            if (Math.hypot(forward, strafeLeft) > 1.0 + EPSILON) {
                throw new IllegalArgumentException("Movement axes must have magnitude <= 1");
            }
        }
    }
}
