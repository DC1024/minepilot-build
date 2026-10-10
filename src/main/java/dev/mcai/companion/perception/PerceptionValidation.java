package dev.mcai.companion.perception;

final class PerceptionValidation {
    private static final int MAX_IDENTIFIER_LENGTH = 256;

    private PerceptionValidation() {
    }

    static String identifier(String value, String label) {
        if (value == null || value.isEmpty() || value.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException(label + " is outside its bounds");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character) || character == '\u00a7') {
                throw new IllegalArgumentException(label + " contains forbidden characters");
            }
        }
        return value;
    }

    static String token(String value, String label, int maximumLength) {
        if (value == null || value.isBlank()
                || value.length() > maximumLength) {
            throw new IllegalArgumentException(label + " is outside its bounds");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(Character.isLetterOrDigit(character)
                    || character == '_' || character == '-')
                    || Character.isISOControl(character)) {
                throw new IllegalArgumentException(
                        label + " contains forbidden characters"
                );
            }
        }
        return value;
    }

    static double finite(double value, String label) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite");
        }
        return value == 0.0 ? 0.0 : value;
    }

    static float finite(float value, String label) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite");
        }
        return value == 0.0F ? 0.0F : value;
    }
}
