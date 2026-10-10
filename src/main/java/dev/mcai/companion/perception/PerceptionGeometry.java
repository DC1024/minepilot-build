package dev.mcai.companion.perception;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure geometry used before any world access.
 */
public final class PerceptionGeometry {
    private PerceptionGeometry() {
    }

    public static boolean isInsideViewCone(
            PerceptionVec3 forward,
            PerceptionVec3 toTarget,
            double fullFieldOfViewDegrees
    ) {
        if (forward == null || toTarget == null
                || !Double.isFinite(fullFieldOfViewDegrees)
                || fullFieldOfViewDegrees <= 0.0
                || fullFieldOfViewDegrees >= 180.0) {
            throw new IllegalArgumentException("Invalid view cone");
        }
        if (toTarget.lengthSquared() <= 1.0E-12) {
            return true;
        }
        double cosine = forward.normalized().dot(toTarget.normalized());
        double threshold = Math.cos(Math.toRadians(fullFieldOfViewDegrees * 0.5));
        return cosine + 1.0E-12 >= threshold;
    }

    public static List<PerceptionVec3> rayFan(
            double yawDegrees,
            double pitchDegrees,
            double horizontalFieldOfViewDegrees,
            double verticalFieldOfViewDegrees,
            int columns,
            int rows
    ) {
        if (!Double.isFinite(yawDegrees)
                || !Double.isFinite(pitchDegrees)
                || !Double.isFinite(horizontalFieldOfViewDegrees)
                || !Double.isFinite(verticalFieldOfViewDegrees)
                || horizontalFieldOfViewDegrees <= 0.0
                || horizontalFieldOfViewDegrees >= 180.0
                || verticalFieldOfViewDegrees <= 0.0
                || verticalFieldOfViewDegrees >= 180.0
                || columns <= 0
                || rows <= 0
                || (long) columns * rows > PerceptionBudget.MAX_BLOCK_RAYS) {
            throw new IllegalArgumentException("Invalid ray fan");
        }

        List<PerceptionVec3> rays = new ArrayList<>(columns * rows);
        for (int row = 0; row < rows; row++) {
            double verticalOffset = uniformGridOffset(
                    row,
                    rows,
                    verticalFieldOfViewDegrees
            );
            for (int column = 0; column < columns; column++) {
                double horizontalOffset = foveatedGridOffset(
                        column,
                        columns,
                        horizontalFieldOfViewDegrees
                );
                rays.add(directionFromRotation(
                        pitchDegrees + verticalOffset,
                        yawDegrees + horizontalOffset
                ));
            }
        }
        return List.copyOf(rays);
    }

    public static PerceptionVec3 directionFromRotation(
            double pitchDegrees,
            double yawDegrees
    ) {
        if (!Double.isFinite(pitchDegrees) || !Double.isFinite(yawDegrees)) {
            throw new IllegalArgumentException("Rotation must be finite");
        }
        double pitch = Math.toRadians(pitchDegrees);
        double negativeYaw = Math.toRadians(-yawDegrees);
        double horizontal = Math.cos(pitch);
        return new PerceptionVec3(
                Math.sin(negativeYaw) * horizontal,
                -Math.sin(pitch),
                Math.cos(negativeYaw) * horizontal
        ).normalized();
    }

    /**
     * Keeps the declared field-of-view boundary while concentrating the fixed
     * ray budget around the direction the player deliberately looks at. This
     * is a deterministic first-person foveated fan, not an extra world query.
     */
    static double foveatedGridOffset(
            final int index,
            final int count,
            final double fieldOfView
    ) {
        if (count == 1) {
            return 0.0;
        }
        final double normalized =
                -1.0 + 2.0 * index / (count - 1.0);
        return Math.copySign(
                normalized * normalized * fieldOfView * 0.5,
                normalized
        );
    }

    /**
     * Vertical layers remain uniform so a downward landing scan preserves
     * distinct support, feet, and head-height evidence. Horizontal foveation
     * supplies multiple rays inside each of those layers.
     */
    static double uniformGridOffset(
            final int index,
            final int count,
            final double fieldOfView
    ) {
        if (count == 1) {
            return 0.0;
        }
        return -fieldOfView * 0.5
                + fieldOfView * index / (count - 1.0);
    }
}
