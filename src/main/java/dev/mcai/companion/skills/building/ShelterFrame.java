package dev.mcai.companion.skills.building;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.navigation.LocalNavSnapshot;
import dev.mcai.companion.perception.HeldItemSummary;
import dev.mcai.companion.perception.InventoryItemSummary;
import dev.mcai.companion.perception.PerceptionVec3;
import dev.mcai.companion.perception.SemanticObservation;
import dev.mcai.companion.perception.VisibleBlockFace;
import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Fair building input: own body/inventory plus the incremental map and block
 * faces derived from first-person semantic rays.
 */
public record ShelterFrame(
        UUID playerId,
        DimensionRef dimension,
        long currentGameTime,
        long observedAtGameTime,
        long observationRevision,
        long sessionGeneration,
        GridPos feet,
        PerceptionVec3 lookDirection,
        HeldItemSummary mainHand,
        List<InventoryItemSummary> inventory,
        LocalNavSnapshot navigation,
        List<VisibleBlockFace> visibleBlockFaces,
        List<RecentVisibleEntity> recentVisibleEntities
) {
    /*
     * A bounded 360-degree first-person building survey can take roughly
     * 320 ticks with natural head motion. Ten seconds caused its earliest
     * livestock observations to expire before site synthesis ran, allowing
     * the generated shell to intersect a cow. Thirty seconds covers one
     * complete survey plus planning while still expiring moving actors
     * instead of turning memory into persistent hidden radar.
     */
    public static final long MAXIMUM_RECENT_ENTITY_AGE_TICKS = 600;

    public ShelterFrame(
            final UUID playerId,
            final DimensionRef dimension,
            final long currentGameTime,
            final long observedAtGameTime,
            final long observationRevision,
            final long sessionGeneration,
            final GridPos feet,
            final PerceptionVec3 lookDirection,
            final HeldItemSummary mainHand,
            final List<InventoryItemSummary> inventory,
            final LocalNavSnapshot navigation,
            final List<VisibleBlockFace> visibleBlockFaces
    ) {
        this(
                playerId,
                dimension,
                currentGameTime,
                observedAtGameTime,
                observationRevision,
                sessionGeneration,
                feet,
                lookDirection,
                mainHand,
                inventory,
                navigation,
                visibleBlockFaces,
                List.of()
        );
    }

    public ShelterFrame {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(dimension, "dimension");
        if (currentGameTime < 0
                || observedAtGameTime < 0
                || observationRevision < 0
                || sessionGeneration < 0
                || currentGameTime < observedAtGameTime) {
            throw new IllegalArgumentException(
                    "Shelter frame counters are inconsistent"
            );
        }
        Objects.requireNonNull(feet, "feet");
        Objects.requireNonNull(lookDirection, "lookDirection");
        if (Math.abs(lookDirection.length() - 1.0) > 1.0E-6) {
            throw new IllegalArgumentException(
                    "lookDirection must be normalized"
            );
        }
        Objects.requireNonNull(mainHand, "mainHand");
        inventory = List.copyOf(Objects.requireNonNull(inventory, "inventory"));
        Objects.requireNonNull(navigation, "navigation");
        if (!navigation.dimension().equals(dimension)
                || navigation.revision() > observationRevision) {
            throw new IllegalArgumentException(
                    "Navigation is inconsistent with the building frame"
            );
        }
        visibleBlockFaces = List.copyOf(
                Objects.requireNonNull(
                        visibleBlockFaces,
                        "visibleBlockFaces"
                )
        );
        recentVisibleEntities = List.copyOf(
                Objects.requireNonNull(
                        recentVisibleEntities,
                        "recentVisibleEntities"
                )
        );
        if (recentVisibleEntities.stream().anyMatch(entity ->
                entity.observedAtGameTime() > observedAtGameTime
                        || entity.observationRevision()
                                > observationRevision
                        || !entity.isFreshAt(
                                currentGameTime,
                                MAXIMUM_RECENT_ENTITY_AGE_TICKS
                        )
        )) {
            throw new IllegalArgumentException(
                    "Recent visible entity memory is inconsistent "
                            + "with the building frame"
            );
        }
    }

    public static ShelterFrame from(
            SemanticObservation observation,
            LocalNavSnapshot navigation,
            long sessionGeneration,
            List<RecentVisibleEntity> recentVisibleEntities
    ) {
        Objects.requireNonNull(observation, "observation");
        var body = observation.body();
        return new ShelterFrame(
                body.playerId(),
                DimensionRef.parse(body.dimensionId()),
                body.gameTime(),
                body.gameTime(),
                observation.sequence(),
                sessionGeneration,
                feet(body.position()),
                body.lookDirection(),
                body.mainHand(),
                body.inventory(),
                navigation,
                observation.visibleBlockFaces(),
                recentVisibleEntities
        );
    }

    public long observationAgeTicks() {
        return currentGameTime - observedAtGameTime;
    }

    public ShelterFrame atGameTime(long gameTime) {
        return new ShelterFrame(
                playerId,
                dimension,
                gameTime,
                observedAtGameTime,
                observationRevision,
                sessionGeneration,
                feet,
                lookDirection,
                mainHand,
                inventory,
                navigation,
                visibleBlockFaces,
                recentVisibleEntities.stream()
                        .filter(entity -> entity.isFreshAt(
                                gameTime,
                                MAXIMUM_RECENT_ENTITY_AGE_TICKS
                        ))
                        .toList()
        );
    }

    private static GridPos feet(PerceptionVec3 position) {
        return new GridPos(
                floor(position.x()),
                floor(position.y()),
                floor(position.z())
        );
    }

    private static int floor(double value) {
        double result = Math.floor(value);
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Player position is outside grid bounds"
            );
        }
        return (int) result;
    }
}
