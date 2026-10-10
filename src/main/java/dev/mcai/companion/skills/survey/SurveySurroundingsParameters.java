package dev.mcai.companion.skills.survey;

import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

public record SurveySurroundingsParameters(
        DimensionRef dimension,
        int horizontalSteps,
        boolean includeVertical,
        int observationWaitTicks
) {
    public static final int DEFAULT_OBSERVATION_WAIT_TICKS = 40;

    /**
     * Compatibility constructor used by ordinary model-selected surveys.
     * The larger wait gives the semantic sampler enough time to publish a
     * fresh frame even on a busy server.
     */
    public SurveySurroundingsParameters(
            final DimensionRef dimension,
            final int horizontalSteps,
            final boolean includeVertical
    ) {
        this(
                dimension,
                horizontalSteps,
                includeVertical,
                DEFAULT_OBSERVATION_WAIT_TICKS
        );
    }

    public SurveySurroundingsParameters {
        Objects.requireNonNull(dimension, "dimension");
        if (horizontalSteps < 4 || horizontalSteps > 16) {
            throw new IllegalArgumentException(
                    "horizontalSteps must be in [4, 16]"
            );
        }
        if (observationWaitTicks < 4 || observationWaitTicks > 40) {
            throw new IllegalArgumentException(
                    "observationWaitTicks must be in [4, 40]"
            );
        }
    }

    public int totalViews() {
        return horizontalSteps * (includeVertical ? 3 : 1);
    }
}
