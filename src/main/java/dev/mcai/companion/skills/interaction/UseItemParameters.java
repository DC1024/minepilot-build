package dev.mcai.companion.skills.interaction;

import dev.mcai.companion.action.ActionHand;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;

public record UseItemParameters(
        DimensionRef dimension,
        ActionHand hand,
        int holdTicks
) {
    public UseItemParameters {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(hand, "hand");
        if (holdTicks < 0
                || holdTicks
                > InteractionSkillPolicy.HARD_MAXIMUM_TIMEOUT_TICKS) {
            throw new IllegalArgumentException(
                    "holdTicks is outside hard bounds"
            );
        }
    }
}
