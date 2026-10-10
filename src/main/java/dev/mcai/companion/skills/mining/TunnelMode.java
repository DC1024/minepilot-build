package dev.mcai.companion.skills.mining;

import java.util.Locale;

public enum TunnelMode {
    HORIZONTAL,
    DESCENDING,
    ASCENDING;

    public static TunnelMode parse(final String value) {
        if (value == null || !value.equals(value.trim())) {
            throw new IllegalArgumentException("Invalid tunnel mode");
        }
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
