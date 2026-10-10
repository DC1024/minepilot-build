package dev.mcai.companion.skills.combat;

import dev.mcai.companion.action.ActionHand;
import java.util.Objects;
import java.util.regex.Pattern;

public record ShootObservedEntityParameters(
        long sampleSequence,
        String observationId,
        ActionHand hand,
        int shots
) {
    private static final Pattern OBSERVATION_ID =
            Pattern.compile("visible-(?:0|[1-9][0-9]{0,5})");

    public ShootObservedEntityParameters {
        Objects.requireNonNull(observationId, "observationId");
        Objects.requireNonNull(hand, "hand");
        if (sampleSequence < 0
                || !OBSERVATION_ID.matcher(observationId).matches()
                || shots < 1
                || shots > 16) {
            throw new IllegalArgumentException(
                    "Invalid semantic ranged-combat request"
            );
        }
    }

    int observationIndex() {
        return Integer.parseInt(
                observationId.substring("visible-".length())
        );
    }
}
