package dev.mcai.companion.skills.loot;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One fair, observed Blaze combat attempt in the Nether.
 *
 * <p>Blaze rods are ordinary random loot, so one accepted attempt does not
 * imply that a rod will drop. Callers must observe the owned inventory and
 * repeat against another visible Blaze when the vanilla loot table yields no
 * rod.</p>
 */
public record AcquireNetherBlazeRodParameters(
        long sampleSequence,
        String observationId,
        int maximumTicks
) {
    private static final Pattern OBSERVATION_ID =
            Pattern.compile("visible-(?:0|[1-9][0-9]{0,5})");

    public AcquireNetherBlazeRodParameters {
        Objects.requireNonNull(observationId, "observationId");
        if (sampleSequence < 0
                || !OBSERVATION_ID.matcher(observationId).matches()
                || maximumTicks < 80
                || maximumTicks > 1_200) {
            throw new IllegalArgumentException(
                    "Invalid Nether Blaze resource request"
            );
        }
    }

    public int observationIndex() {
        return Integer.parseInt(
                observationId.substring("visible-".length())
        );
    }
}
