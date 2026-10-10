package dev.mcai.companion.skills.interaction;

import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.SemanticObservation;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.perception.VisibleEntity;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Fair semantic candidates bound to one concrete body session.
 *
 * <p>The observation timestamp remains distinct from the current body game
 * time so skills can reject stale visibility instead of treating an old ray
 * hit as current knowledge.</p>
 */
public record InteractionSkillFrame(
        UUID playerId,
        DimensionRef dimension,
        long currentGameTime,
        long observedAtGameTime,
        long observationRevision,
        long sessionGeneration,
        HeldItemSummary mainHand,
        HeldItemSummary offHand,
        List<VisibleEntity> visibleEntities,
        List<VisibleBlockFace> visibleBlockFaces,
        List<InventoryItemSummary> inventory
) {
    public InteractionSkillFrame {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(dimension, "dimension");
        if (currentGameTime < 0
                || observedAtGameTime < 0
                || observationRevision < 0
                || sessionGeneration < 0
                || currentGameTime < observedAtGameTime) {
            throw new IllegalArgumentException(
                    "Frame counters must be non-negative and monotonic"
            );
        }
        Objects.requireNonNull(mainHand, "mainHand");
        Objects.requireNonNull(offHand, "offHand");
        visibleEntities = List.copyOf(
                Objects.requireNonNull(visibleEntities, "visibleEntities")
        );
        visibleBlockFaces = List.copyOf(
                Objects.requireNonNull(
                        visibleBlockFaces,
                        "visibleBlockFaces"
                )
        );
        inventory = List.copyOf(
                Objects.requireNonNull(inventory, "inventory")
        );
    }

    public InteractionSkillFrame(
            UUID playerId,
            DimensionRef dimension,
            long currentGameTime,
            long observedAtGameTime,
            long observationRevision,
            long sessionGeneration,
            HeldItemSummary mainHand,
            HeldItemSummary offHand,
            List<VisibleEntity> visibleEntities,
            List<VisibleBlockFace> visibleBlockFaces
    ) {
        this(
                playerId,
                dimension,
                currentGameTime,
                observedAtGameTime,
                observationRevision,
                sessionGeneration,
                mainHand,
                offHand,
                visibleEntities,
                visibleBlockFaces,
                List.of()
        );
    }

    public static InteractionSkillFrame from(
            SemanticObservation observation,
            long sessionGeneration
    ) {
        Objects.requireNonNull(observation, "observation");
        var body = observation.body();
        return new InteractionSkillFrame(
                body.playerId(),
                DimensionRef.parse(body.dimensionId()),
                body.gameTime(),
                body.gameTime(),
                observation.sequence(),
                sessionGeneration,
                body.mainHand(),
                body.offHand(),
                observation.visibleEntities(),
                observation.visibleBlockFaces(),
                body.inventory()
        );
    }

    public long observationAgeTicks() {
        return currentGameTime - observedAtGameTime;
    }

    public InteractionSkillFrame atGameTime(long gameTime) {
        return new InteractionSkillFrame(
                playerId,
                dimension,
                gameTime,
                observedAtGameTime,
                observationRevision,
                sessionGeneration,
                mainHand,
                offHand,
                visibleEntities,
                visibleBlockFaces,
                inventory
        );
    }
}
