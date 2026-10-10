package dev.mcai.companion.skills.building;

import java.util.Locale;

/**
 * A bounded size preference. Every generated plan still guarantees at least
 * a three-by-three, two-block-high interior.
 */
public enum ShelterScale {
    COMPACT,
    STANDARD,
    SPACIOUS;

    public static ShelterScale parse(String value) {
        if (value == null || !value.equals(value.strip())) {
            throw new IllegalArgumentException("Invalid shelter scale");
        }
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
