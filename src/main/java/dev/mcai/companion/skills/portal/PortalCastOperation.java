package dev.mcai.companion.skills.portal;

import java.util.Locale;

public enum PortalCastOperation {
    CAST_NEXT,
    LIGHT;

    static PortalCastOperation parse(final String value) {
        if (value == null || !value.equals(value.strip())) {
            throw new IllegalArgumentException(
                    "Portal cast operation is invalid"
            );
        }
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
