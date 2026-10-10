package dev.mcai.companion.skills.stronghold;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps only a few measurements for the active goal. It performs geometry on
 * fair measured rays; it never queries a seed, structure, chunk, or level.
 */
public final class EyeTraceResultBuffer {
    public static final int MAXIMUM_TRACES = 4;
    private static final double MINIMUM_BASELINE = 32.0;
    private static final double MINIMUM_CROSSING_ANGLE_DEGREES = 3.0;
    private static final double MAXIMUM_FORWARD_DISTANCE = 50_000.0;

    private long goalRevision = -1;
    private final List<EyeTraceSnapshot> traces = new ArrayList<>();

    public synchronized void publish(final EyeTraceSnapshot trace) {
        Objects.requireNonNull(trace, "trace");
        if (trace.goalRevision() != goalRevision) {
            traces.clear();
            goalRevision = trace.goalRevision();
        }
        traces.add(trace);
        if (traces.size() > MAXIMUM_TRACES) {
            traces.removeFirst();
        }
    }

    public synchronized Optional<EyeTraceHistorySnapshot> snapshot(
            final long requestedGoalRevision
    ) {
        if (requestedGoalRevision != goalRevision || traces.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new EyeTraceHistorySnapshot(
                goalRevision,
                traces,
                bestIntersection(traces)
        ));
    }

    public synchronized void clear() {
        goalRevision = -1;
        traces.clear();
    }

    private static Optional<EyeTraceHistorySnapshot.Intersection>
            bestIntersection(final List<EyeTraceSnapshot> candidates) {
        Candidate best = null;
        for (int leftIndex = 0;
                leftIndex < candidates.size();
                leftIndex++) {
            for (int rightIndex = leftIndex + 1;
                    rightIndex < candidates.size();
                    rightIndex++) {
                final Candidate candidate = intersect(
                        candidates.get(leftIndex),
                        candidates.get(rightIndex)
                );
                if (candidate != null
                        && (best == null
                            || candidate.uncertaintyRadius
                                < best.uncertaintyRadius)) {
                    best = candidate;
                }
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        return Optional.of(new EyeTraceHistorySnapshot.Intersection(
                best.x,
                best.z,
                best.crossingAngleDegrees,
                best.uncertaintyRadius,
                2
        ));
    }

    private static Candidate intersect(
            final EyeTraceSnapshot left,
            final EyeTraceSnapshot right
    ) {
        if (!left.dimension().equals(right.dimension())) {
            return null;
        }
        final double originDeltaX =
                right.throwOrigin().x() - left.throwOrigin().x();
        final double originDeltaZ =
                right.throwOrigin().z() - left.throwOrigin().z();
        final double baseline = Math.hypot(
                originDeltaX,
                originDeltaZ
        );
        if (baseline < MINIMUM_BASELINE) {
            return null;
        }
        final double determinant =
                left.directionX() * right.directionZ()
                    - left.directionZ() * right.directionX();
        final double sine = Math.abs(determinant);
        final double angle = Math.toDegrees(Math.asin(
                Math.min(1.0, sine)
        ));
        if (angle < MINIMUM_CROSSING_ANGLE_DEGREES) {
            return null;
        }
        final double leftDistance =
                (originDeltaX * right.directionZ()
                    - originDeltaZ * right.directionX())
                    / determinant;
        final double rightDistance =
                (originDeltaX * left.directionZ()
                    - originDeltaZ * left.directionX())
                    / determinant;
        if (leftDistance < 0.0
                || rightDistance < 0.0
                || leftDistance > MAXIMUM_FORWARD_DISTANCE
                || rightDistance > MAXIMUM_FORWARD_DISTANCE) {
            return null;
        }
        final double leftX = left.throwOrigin().x()
                + leftDistance * left.directionX();
        final double leftZ = left.throwOrigin().z()
                + leftDistance * left.directionZ();
        final double rightX = right.throwOrigin().x()
                + rightDistance * right.directionX();
        final double rightZ = right.throwOrigin().z()
                + rightDistance * right.directionZ();
        final double disagreement = Math.hypot(
                leftX - rightX,
                leftZ - rightZ
        );
        final double uncertainty = Math.max(
                8.0,
                Math.min(
                        256.0,
                        disagreement * 0.5 + 6.0 / sine
                )
        );
        return new Candidate(
                (leftX + rightX) * 0.5,
                (leftZ + rightZ) * 0.5,
                angle,
                uncertainty
        );
    }

    private record Candidate(
            double x,
            double z,
            double crossingAngleDegrees,
            double uncertaintyRadius
    ) {
    }
}
