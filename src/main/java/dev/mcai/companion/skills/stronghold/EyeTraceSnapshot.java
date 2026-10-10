package dev.mcai.companion.skills.stronghold;

import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Objects;

/**
 * One direction measurement assembled only from successive first-person
 * observations of the Eye of Ender entity thrown by the companion.
 */
public record EyeTraceSnapshot(
        long goalRevision,
        DimensionRef dimension,
        PerceptionVec3 throwOrigin,
        long completedGameTick,
        long firstObservationRevision,
        long lastObservationRevision,
        List<Sample> samples,
        double directionX,
        double directionZ,
        double bearingDegrees,
        double observedHorizontalTravel
) {
    public static final int MAXIMUM_SAMPLES = 16;

    public EyeTraceSnapshot {
        if (goalRevision < 0
                || completedGameTick < 0
                || firstObservationRevision < 0
                || lastObservationRevision < firstObservationRevision) {
            throw new IllegalArgumentException(
                    "Eye trace counters are invalid"
            );
        }
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(throwOrigin, "throwOrigin");
        samples = List.copyOf(Objects.requireNonNull(samples, "samples"));
        if (samples.size() < 2 || samples.size() > MAXIMUM_SAMPLES) {
            throw new IllegalArgumentException(
                    "Eye trace sample count is invalid"
            );
        }
        finite(directionX, "directionX");
        finite(directionZ, "directionZ");
        finite(bearingDegrees, "bearingDegrees");
        finite(observedHorizontalTravel, "observedHorizontalTravel");
        final double horizontalLength = Math.hypot(
                directionX,
                directionZ
        );
        if (Math.abs(horizontalLength - 1.0) > 1.0E-6
                || bearingDegrees < -180.0
                || bearingDegrees > 180.0
                || observedHorizontalTravel < 1.0) {
            throw new IllegalArgumentException(
                    "Eye trace direction is invalid"
            );
        }
    }

    public record Sample(
            long observationRevision,
            PerceptionVec3 observedPosition
    ) {
        public Sample {
            if (observationRevision < 0) {
                throw new IllegalArgumentException(
                        "Eye sample revision is invalid"
                );
            }
            Objects.requireNonNull(
                    observedPosition,
                    "observedPosition"
            );
        }
    }

    private static void finite(
            final double value,
            final String label
    ) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite");
        }
    }
}
