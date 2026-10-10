package dev.mcai.companion.skills.loot;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One bounded Enderman combat attempt from an already-observed safe roof.
 *
 * <p>Ender pearls remain ordinary random loot. A completed invocation proves
 * that at least one pearl entered the companion's own inventory; a failed
 * no-drop attempt must be retried against another newly observed Enderman.</p>
 */
public record AcquireShelteredEnderPearlParameters(
        long sampleSequence,
        String observationId,
        int maximumTicks
) {
    private static final Pattern OBSERVATION_ID =
            Pattern.compile("visible-(?:0|[1-9][0-9]{0,5})");

    public AcquireShelteredEnderPearlParameters {
        Objects.requireNonNull(observationId, "observationId");
        if (sampleSequence < 0
                || !OBSERVATION_ID.matcher(observationId).matches()
                || maximumTicks < 80
                || maximumTicks > 1_200) {
            throw new IllegalArgumentException(
                    "Invalid sheltered Enderman resource request"
            );
        }
    }

    public int observationIndex() {
        return Integer.parseInt(
                observationId.substring("visible-".length())
        );
    }
}
