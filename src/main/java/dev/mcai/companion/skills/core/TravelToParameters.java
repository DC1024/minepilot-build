package dev.mcai.companion.skills.core;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

/**
 * A same-dimension, fair long-range destination.
 *
 * <p>The radius is deliberately capped at three blocks so a waypoint cannot
 * be reported as reached from an implausibly large distance.</p>
 */
public record TravelToParameters(
        DimensionRef dimension,
        double x,
        double y,
        double z,
        double arrivalRadius
) {
    public TravelToParameters {
        Objects.requireNonNull(dimension, "dimension");
        CoreSkillParameters.coordinate(x, false, "x");
        CoreSkillParameters.coordinate(y, true, "y");
        CoreSkillParameters.coordinate(z, false, "z");
        if (!Double.isFinite(arrivalRadius)
                || arrivalRadius < 0.5
                || arrivalRadius > 3.0) {
            throw new IllegalArgumentException(
                    "arrivalRadius must be in [0.5, 3.0]"
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
