package dev.mcai.companion.skills.core;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Bounded moving-target contract for {@code follow_entity}.
 */
public record FollowEntityParameters(
        String observationId,
        long sampleSequence,
        double followDistance,
        int lostGraceTicks
) {
    private static final Pattern OBSERVATION_ID =
            Pattern.compile("visible-(0|[1-9][0-9]{0,2})");

    public FollowEntityParameters {
        Objects.requireNonNull(observationId, "observationId");
        if (!OBSERVATION_ID.matcher(observationId).matches()
                || sampleSequence < 0
                || !Double.isFinite(followDistance)
                || followDistance < 1.5
                || followDistance > 16.0
                || lostGraceTicks < 20
                || lostGraceTicks > 600) {
            throw new IllegalArgumentException(
                    "Follow parameters are outside safe bounds"
            );
        }
    }

    int observationIndex() {
        return Integer.parseInt(
                observationId.substring("visible-".length())
        );
    }
}
