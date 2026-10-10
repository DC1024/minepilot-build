package dev.mcai.companion.skills.combat;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A model-visible combat target reference.
 *
 * <p>The wire contract intentionally contains no UUID, coordinate, entity
 * selector, or radius. The local skill resolves this bounded observation ID
 * exactly once and keeps the resulting UUID inside the trusted runtime.</p>
 */
public record EngageObservedEntityParameters(
        long sampleSequence,
        String observationId
) {
    private static final Pattern OBSERVATION_ID =
            Pattern.compile("visible-(?:0|[1-9][0-9]{0,5})");

    public EngageObservedEntityParameters {
        Objects.requireNonNull(observationId, "observationId");
        if (sampleSequence < 0
                || !OBSERVATION_ID.matcher(observationId).matches()) {
            throw new IllegalArgumentException(
                    "Invalid semantic combat target"
            );
        }
    }

    int observationIndex() {
        return Integer.parseInt(
                observationId.substring("visible-".length())
        );
    }
}
