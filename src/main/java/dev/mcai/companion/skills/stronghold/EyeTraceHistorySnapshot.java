package dev.mcai.companion.skills.stronghold;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Goal-scoped bounded trace history plus a conservative two-line
 * triangulation when the observations have useful baseline and angle.
 */
public record EyeTraceHistorySnapshot(
        long goalRevision,
        List<EyeTraceSnapshot> traces,
        Optional<Intersection> estimatedIntersection
) {
    public EyeTraceHistorySnapshot {
        if (goalRevision < 0) {
            throw new IllegalArgumentException(
                    "Eye trace goal revision is invalid"
            );
        }
        traces = List.copyOf(Objects.requireNonNull(traces, "traces"));
        if (traces.isEmpty()
                || traces.size() > EyeTraceResultBuffer.MAXIMUM_TRACES) {
            throw new IllegalArgumentException(
                    "Eye trace history size is invalid"
            );
        }
        estimatedIntersection = Objects.requireNonNull(
                estimatedIntersection,
                "estimatedIntersection"
        );
    }

    public record Intersection(
            double x,
            double z,
            double crossingAngleDegrees,
            double uncertaintyRadius,
            int supportingTraceCount
    ) {
        public Intersection {
            if (!Double.isFinite(x)
                    || !Double.isFinite(z)
                    || !Double.isFinite(crossingAngleDegrees)
                    || !Double.isFinite(uncertaintyRadius)
                    || crossingAngleDegrees < 3.0
                    || crossingAngleDegrees > 90.0
                    || uncertaintyRadius < 8.0
                    || uncertaintyRadius > 256.0
                    || supportingTraceCount < 2
                    || supportingTraceCount
                        > EyeTraceResultBuffer.MAXIMUM_TRACES) {
                throw new IllegalArgumentException(
                        "Eye triangulation is invalid"
                );
            }
        }
    }
}
