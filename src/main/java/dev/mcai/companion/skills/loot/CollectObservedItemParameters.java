package dev.mcai.companion.skills.loot;

import java.util.Objects;
import java.util.regex.Pattern;

public record CollectObservedItemParameters(
        long sampleSequence,
        String observationId,
        int maximumTicks
) {
    private static final Pattern OBSERVATION_ID =
            Pattern.compile("visible-(?:0|[1-9][0-9]{0,5})");

    public CollectObservedItemParameters {
        Objects.requireNonNull(observationId, "observationId");
        if (sampleSequence < 0
                || !OBSERVATION_ID.matcher(observationId).matches()
                || maximumTicks < 20
                || maximumTicks > 600) {
            throw new IllegalArgumentException(
                    "Invalid observed item collection request"
            );
        }
    }

    int observationIndex() {
        return Integer.parseInt(
                observationId.substring("visible-".length())
        );
    }
}
