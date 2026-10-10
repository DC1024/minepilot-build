package dev.mcai.companion.skills.portal;

import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;
import java.util.Optional;

public record EnterObservedPortalParameters(
        DimensionRef dimension,
        ObservedPortalTarget target,
        Optional<DimensionRef> expectedDestination
) {
    public EnterObservedPortalParameters {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(target, "target");
        expectedDestination = Objects.requireNonNull(
                expectedDestination,
                "expectedDestination"
        );
    }
}
