package dev.mcai.companion.skills.core;

import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

public record LookAtParameters(
        DimensionRef dimension,
        double x,
        double y,
        double z
) {
    public LookAtParameters {
        Objects.requireNonNull(dimension, "dimension");
        CoreSkillParameters.coordinate(x, false, "x");
        CoreSkillParameters.coordinate(y, true, "y");
        CoreSkillParameters.coordinate(z, false, "z");
    }

    public PerceptionVec3 target() {
        return new PerceptionVec3(x, y, z);
    }
}
