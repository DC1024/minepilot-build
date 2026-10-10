package dev.mcai.companion.action;

final class ActionValidation {
    private ActionValidation() {
    }

    static double finite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return value == 0.0 ? 0.0 : value;
    }

    static float finite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return value == 0.0F ? 0.0F : value;
    }

    static double closedUnit(double value, String name) {
        value = finite(value, name);
        if (value < -1.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be in [-1, 1]");
        }
        return value;
    }
}
