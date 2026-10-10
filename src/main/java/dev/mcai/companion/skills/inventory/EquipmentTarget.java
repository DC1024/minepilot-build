package dev.mcai.companion.skills.inventory;

import java.util.Locale;

public enum EquipmentTarget {
    MAINHAND,
    OFFHAND,
    HEAD,
    CHEST,
    LEGS,
    FEET;

    public static EquipmentTarget parse(final String value) {
        if (value == null || !value.equals(value.trim())) {
            throw new IllegalArgumentException("Equipment target is invalid");
        }
        return valueOf(value.toUpperCase(Locale.ROOT));
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
