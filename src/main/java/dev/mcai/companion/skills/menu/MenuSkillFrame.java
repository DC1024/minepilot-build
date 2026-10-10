package dev.mcai.companion.skills.menu;

import dev.mcai.companion.perception.OpenMenuSnapshot;
import dev.mcai.companion.perception.SemanticObservation;
import java.util.Objects;
import java.util.UUID;

/**
 * Fair open-menu observation bound to one authoritative body generation.
 */
public record MenuSkillFrame(
        UUID playerId,
        String dimensionId,
        long observedAtGameTime,
        long sampleSequence,
        long sessionGeneration,
        OpenMenuSnapshot menu
) {
    public MenuSkillFrame {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(menu, "menu");
        if (dimensionId.isBlank()
                || observedAtGameTime < 0
                || sampleSequence < 0
                || sessionGeneration < 0) {
            throw new IllegalArgumentException("Menu frame is invalid");
        }
    }

    public static MenuSkillFrame from(
            final SemanticObservation observation,
            final long sessionGeneration
    ) {
        Objects.requireNonNull(observation, "observation");
        return new MenuSkillFrame(
                observation.body().playerId(),
                observation.body().dimensionId(),
                observation.body().gameTime(),
                observation.sequence(),
                sessionGeneration,
                observation.openMenu().orElseThrow(() ->
                        new IllegalArgumentException(
                                "Observation has no open menu"
                        )
                )
        );
    }
}
