package dev.mcai.companion.skills.core;

import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface CoreSkillFrameSource {
    Optional<CoreSkillFrame> current();

    /**
     * Resolves a recently published fair observation by its public semantic
     * sequence. Server implementations retain a small bounded history so a
     * model response can still bind the exact thing it saw while the 2-5 Hz
     * perception loop continues to publish newer samples.
     */
    default Optional<CoreSkillFrame> atObservation(
            final long observationRevision
    ) {
        return current().filter(frame ->
                frame.observationRevision() == observationRevision
        );
    }

    /**
     * Resolves one public visible-entity index from the exact fair semantic
     * sample that authored a model response.
     *
     * <p>The default implementation uses the full frame history. Production
     * sources may retain a longer compact binding history without retaining
     * hundreds of complete navigation maps.</p>
     */
    default Optional<VisibleEntityBinding> visibleEntityAtObservation(
            final long observationRevision,
            final int observationIndex
    ) {
        if (observationIndex < 0) {
            return Optional.empty();
        }
        return atObservation(observationRevision).flatMap(frame -> {
            if (observationIndex >= frame.visibleEntities().size()) {
                return Optional.empty();
            }
            return Optional.of(new VisibleEntityBinding(
                    frame.dimension(),
                    frame.visibleEntities().get(observationIndex)
            ));
        });
    }

    /**
     * Returns the live public position of a non-sneaking player.
     *
     * <p>This is deliberately separate from first-person semantic vision.
     * Minecraft teammates ordinarily have server-visible coordinates in the
     * companion contract, while sneaking opts the player out of that exact
     * tracking. Implementations that cannot provide an authenticated player
     * position keep the fail-closed empty default.</p>
     */
    default Optional<TrackablePlayer> trackablePlayer(
            final UUID playerId
    ) {
        return Optional.empty();
    }

    record VisibleEntityBinding(
            DimensionRef dimension,
            VisibleEntity entity
    ) {
        public VisibleEntityBinding {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(entity, "entity");
        }
    }

    record TrackablePlayer(
            UUID playerId,
            DimensionRef dimension,
            PerceptionVec3 position,
            long gameTime
    ) {
        public TrackablePlayer {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(position, "position");
            if (gameTime < 0) {
                throw new IllegalArgumentException(
                        "Trackable player game time must be non-negative"
                );
            }
        }
    }
}
