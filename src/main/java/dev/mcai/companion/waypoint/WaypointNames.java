package dev.mcai.companion.waypoint;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

public final class WaypointNames {
    public static final int MAXIMUM_NAME_LENGTH = 128;
    public static final int MAXIMUM_CATEGORY_LENGTH = 64;
    public static final int MAXIMUM_SOURCE_LENGTH = 128;

    private WaypointNames() {
    }

    public static String normalize(String value) {
        Objects.requireNonNull(value, "value");
        final String compatible = Normalizer.normalize(value, Normalizer.Form.NFKC)
            .toLowerCase(Locale.ROOT);
        final StringBuilder normalized = new StringBuilder(compatible.length());
        boolean pendingSeparator = false;
        for (int offset = 0; offset < compatible.length();) {
            final int codePoint = compatible.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isSeparator(codePoint)) {
                pendingSeparator = normalized.length() > 0;
                continue;
            }
            if (pendingSeparator) {
                normalized.append(' ');
                pendingSeparator = false;
            }
            normalized.appendCodePoint(codePoint);
        }
        final String result = normalized.toString().trim();
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Waypoint name must contain searchable characters");
        }
        return result;
    }

    static String validateDisplayField(String value, String label, int maximumLength) {
        Objects.requireNonNull(value, label);
        final String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > maximumLength) {
            throw new IllegalArgumentException(label + " exceeds bounds");
        }
        normalize(trimmed);
        return trimmed;
    }

    private static boolean isSeparator(int codePoint) {
        if (Character.isWhitespace(codePoint)) {
            return true;
        }
        return switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION,
                Character.DASH_PUNCTUATION,
                Character.START_PUNCTUATION,
                Character.END_PUNCTUATION,
                Character.INITIAL_QUOTE_PUNCTUATION,
                Character.FINAL_QUOTE_PUNCTUATION,
                Character.OTHER_PUNCTUATION,
                Character.SPACE_SEPARATOR,
                Character.LINE_SEPARATOR,
                Character.PARAGRAPH_SEPARATOR -> true;
            default -> false;
        };
    }
}
