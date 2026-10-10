package dev.mcai.companion.skills.portal;

import java.util.Locale;

public enum PortalBuildAxis {
    X,
    Z,
    AUTO;

    static PortalBuildAxis parse(final String value) {
        if (value == null
                || !value.equals(value.trim())
                || !value.equals(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Invalid portal axis");
        }
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
