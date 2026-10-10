package dev.mcai.companion.skills.transport;

import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

public record BoatTravelToParameters(
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

    public BoatTravelToParameters {
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
                    "Invalid boat travel bounds"
            );
        }
    }

    public double horizontalDistance(BoatState boat) {
        Objects.requireNonNull(boat, "boat");
        return Math.hypot(
                x - boat.position().x(),
                z - boat.position().z()
        );
    }

    private static void coordinate(
            double value,
            double maximum,
            String name
    ) {
        if (!Double.isFinite(value) || Math.abs(value) > maximum) {
            throw new IllegalArgumentException(
                    name + " is outside world bounds"
            );
        }
    }
}
