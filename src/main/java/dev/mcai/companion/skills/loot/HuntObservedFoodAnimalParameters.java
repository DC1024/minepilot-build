package dev.mcai.companion.skills.loot;

import java.util.Objects;
import java.util.regex.Pattern;
import net.minecraft.resources.Identifier;

/**
 * One fair attempt to hunt an observed adult food animal and collect the
 * expected vanilla meat drop.
 */
public record HuntObservedFoodAnimalParameters(
        long sampleSequence,
        String observationId,
        String expectedItemId,
        int maximumTicks
) {
    private static final Pattern OBSERVATION_ID =
            Pattern.compile("visible-(?:0|[1-9][0-9]{0,5})");

    public HuntObservedFoodAnimalParameters {
        Objects.requireNonNull(observationId, "observationId");
        Objects.requireNonNull(expectedItemId, "expectedItemId");
        final Identifier parsed = Identifier.tryParse(expectedItemId);
        if (sampleSequence < 0
                || !OBSERVATION_ID.matcher(observationId).matches()
                || parsed == null
                || !parsed.toString().equals(expectedItemId)
                || maximumTicks < 80
                || maximumTicks > 1_200) {
            throw new IllegalArgumentException(
                    "Invalid observed food-animal hunt request"
            );
        }
    }

    public int observationIndex() {
        return Integer.parseInt(
                observationId.substring("visible-".length())
        );
    }
}
