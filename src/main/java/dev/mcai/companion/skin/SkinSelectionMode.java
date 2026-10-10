package dev.mcai.companion.skin;

enum SkinSelectionMode {
    DEFAULT,
    CUSTOM,
    DISABLED;

    static SkinSelectionMode parseStored(final String value) {
        if (value == null) {
            return DEFAULT;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return DEFAULT;
        }
    }
}
