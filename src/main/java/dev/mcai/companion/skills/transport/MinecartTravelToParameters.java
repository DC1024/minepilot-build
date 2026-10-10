package dev.mcai.companion.skills.transport;

import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

public record MinecartTravelToParameters(
        DimensionRef dimension,
        double x,
        double y,
        double z,
        double arrivalRadius,
        int timeoutTicks,
        boolean dismountAtArrival
) {
    public static final int HARD_MAXIMUM_TIMEOUT_TICKS = 72_000;
    private static final double MAX_HORIZONTAL = 29_999_984.0;
    private static final double MAX_VERTICAL = 2_048.0;

    public MinecartTravelToParameters {
        Objects.requireNonNull(dimension, "dimension");
        coordinate(x, MAX_HORIZONTAL, "x");
        coordinate(y, MAX_VERTICAL, "y");
        coordinate(z, MAX_HORIZONTAL, "z");
        if (!Double.isFinite(arrivalRadius)
                || arrivalRadius < 0.75
                || arrivalRadius > 16.0
                || timeoutTicks < 40
                || timeoutTicks > HARD_MAXIMUM_TIMEOUT_TICKS) {
            throw new IllegalArgumentException(
                    "Invalid minecart travel bounds"
            );
        }
    }

    public double distance(final MinecartState minecart) {
        Objects.requireNonNull(minecart, "minecart");
        final double dx = x - minecart.position().x();
        final double dy = y - minecart.position().y();
        final double dz = z - minecart.position().z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void coordinate(
            final double value,
            final double maximum,
            final String name
    ) {
        if (!Double.isFinite(value)
                || Math.abs(value) > maximum) {
            throw new IllegalArgumentException(
                    name + " is outside world bounds"
            );
        }
    }
}
