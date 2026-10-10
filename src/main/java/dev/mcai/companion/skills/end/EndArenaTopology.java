package dev.mcai.companion.skills.end;

import dev.mcai.companion.perception.PerceptionVec3;

/**
 * Public, seed-independent vanilla End geometry used only as a heading.
 *
 * <p>The central island and dragon fountain are organized around horizontal
 * origin. These constants do not query a level, seed, heightmap, structure,
 * chunk, or dragon-fight object.</p>
 */
public final class EndArenaTopology {
    private static final double CENTER_EPSILON = 1.0E-9;

    public static final double CENTER_X = 0.0;
    public static final double CENTER_Z = 0.0;
    public static final double MAXIMUM_INGRESS_START_RADIUS = 112.0;
    public static final double ARENA_READY_RADIUS = 56.0;

    private EndArenaTopology() {
    }

    public static double horizontalRadius(
            final PerceptionVec3 position
    ) {
        return Math.hypot(
                position.x() - CENTER_X,
                position.z() - CENTER_Z
        );
    }

    public static PerceptionVec3 oneCardinalStepTowardCenter(
            final PerceptionVec3 position
    ) {
        final double deltaX = CENTER_X - position.x();
        final double deltaZ = CENTER_Z - position.z();
        if (Math.abs(deltaX) <= CENTER_EPSILON
                && Math.abs(deltaZ) <= CENTER_EPSILON) {
            return position;
        }
        final double currentCellCenterX = Math.floor(position.x()) + 0.5;
        final double currentCellCenterZ = Math.floor(position.z()) + 0.5;
        if (Math.abs(deltaX) >= Math.abs(deltaZ)) {
            return new PerceptionVec3(
                    currentCellCenterX + Math.copySign(1.0, deltaX),
                    position.y(),
                    currentCellCenterZ
            );
        }
        return new PerceptionVec3(
                currentCellCenterX,
                position.y(),
                currentCellCenterZ + Math.copySign(1.0, deltaZ)
        );
    }

    public static boolean insideArenaReadyRadius(
            final PerceptionVec3 position
    ) {
        return horizontalRadius(position) <= ARENA_READY_RADIUS;
    }
}
