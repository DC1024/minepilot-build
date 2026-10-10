package dev.mcai.companion.skills.core;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

public record MoveToParameters(
        DimensionRef dimension,
        double x,
        double y,
        double z,
        double arrivalRadius
) {
    public static final double MINIMUM_ARRIVAL_RADIUS = 0.10;

    public MoveToParameters {
        Objects.requireNonNull(dimension, "dimension");
        CoreSkillParameters.coordinate(x, false, "x");
        CoreSkillParameters.coordinate(y, true, "y");
        CoreSkillParameters.coordinate(z, false, "z");
        if (!Double.isFinite(arrivalRadius)
                || arrivalRadius < MINIMUM_ARRIVAL_RADIUS
                || arrivalRadius > 32.0) {
            throw new IllegalArgumentException(
                    "arrivalRadius must be in [0.10, 32]"
            );
        }
    }

    public PerceptionVec3 target() {
        return new PerceptionVec3(x, y, z);
    }

    public GridPos gridGoal() {
        return new GridPos(
                CoreSkillParameters.floorCoordinate(x),
                CoreSkillParameters.floorCoordinate(y),
                CoreSkillParameters.floorCoordinate(z)
        );
    }
}
