package dev.mcai.companion.skills.memory;

import java.util.Objects;

public record RememberWaypointParameters(
    String name,
    String category
) {
    public RememberWaypointParameters {
        name = bounded(name, 256, "name");
        category = bounded(category, 128, "category");
    }

    private static String bounded(
        final String value,
        final int maximum,
        final String label
    ) {
        final String normalized = Objects.requireNonNull(value, label)
            .strip();
        if (normalized.isEmpty()
            || normalized.length() > maximum
            || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return normalized;
    }
}
