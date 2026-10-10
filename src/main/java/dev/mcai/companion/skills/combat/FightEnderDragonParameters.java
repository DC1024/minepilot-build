package dev.mcai.companion.skills.combat;

import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

public record FightEnderDragonParameters(
        DimensionRef dimension,
        double rallyX,
        double rallyY,
        double rallyZ,
        int maximumShots,
        int timeoutTicks
) {
    private static final double MAXIMUM_COORDINATE = 30_000_000.0;
    private static final int LOCAL_MAXIMUM_SHOTS = 128;
    private static final int LOCAL_TIMEOUT_TICKS = 12_000;

    public FightEnderDragonParameters {
        Objects.requireNonNull(dimension, "dimension");
        if (!coordinate(rallyX)
                || !Double.isFinite(rallyY)
                || rallyY < -2_048.0
                || rallyY > 2_048.0
                || !coordinate(rallyZ)
                || maximumShots < 1
                || maximumShots > 256
                || timeoutTicks < 200
                || timeoutTicks > 12_000) {
            throw new IllegalArgumentException(
                    "Invalid Ender Dragon fight parameters"
            );
        }
    }

    /**
     * The model-facing contract is deliberately parameterless. These bounded
     * budgets are local policy, while the actual rally point is captured from
     * the live body at skill start. The placeholder coordinates therefore
     * carry no movement authority.
     */
    public static FightEnderDragonParameters localControllerDefaults() {
        return new FightEnderDragonParameters(
                DimensionRef.END,
                0.0,
                0.0,
                0.0,
                LOCAL_MAXIMUM_SHOTS,
                LOCAL_TIMEOUT_TICKS
        );
    }

    private static boolean coordinate(final double value) {
        return Double.isFinite(value)
                && Math.abs(value) <= MAXIMUM_COORDINATE;
    }
}
