package dev.mcai.companion.skills.mining;

import java.util.Locale;

/**
 * Absolute cardinal baseline chosen from the companion's observed position.
 */
public enum TunnelDirection {
    NORTH(0, -1),
    SOUTH(0, 1),
    WEST(-1, 0),
    EAST(1, 0);

    private final int stepX;
    private final int stepZ;

    TunnelDirection(final int stepX, final int stepZ) {
        this.stepX = stepX;
        this.stepZ = stepZ;
    }

    public int stepX() {
        return stepX;
    }

    public int stepZ() {
        return stepZ;
    }

    public static TunnelDirection parse(final String value) {
        if (value == null || !value.equals(value.trim())) {
            throw new IllegalArgumentException("Invalid tunnel direction");
        }
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
