package dev.mcai.companion.skills.exploration;

import java.util.Locale;

public enum SearchTargetKind {
    BLOCK,
    ENTITY;

    static SearchTargetKind parse(final String value) {
        if (value == null
                || !value.equals(value.trim())
                || !value.equals(value.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Invalid target kind");
        }
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
