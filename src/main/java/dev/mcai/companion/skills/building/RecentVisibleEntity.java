package dev.mcai.companion.skills.building;

import dev.mcai.companion.perception.VisibleEntity;
import java.util.Objects;

/**
 * Short-lived spatial memory of an entity that passed ordinary first-person
 * distance, view-cone, invisibility, loaded-chunk, and block-clip checks.
 *
 * <p>This is deliberately not an entity tracker. It carries only the last
 * fairly observed semantic entity and expires after a small number of game
 * ticks. Building may use it to avoid trying to place a block through an
 * entity a player has just seen.</p>
 */
public record RecentVisibleEntity(
        VisibleEntity entity,
        long observedAtGameTime,
        long observationRevision
) {
    public RecentVisibleEntity {
        Objects.requireNonNull(entity, "entity");
        if (observedAtGameTime < 0 || observationRevision < 0) {
            throw new IllegalArgumentException(
                    "Recent visible entity counters must be non-negative"
            );
        }
    }

    public boolean isFreshAt(
            final long gameTime,
            final long maximumAgeTicks
    ) {
        return maximumAgeTicks >= 0
                && gameTime >= observedAtGameTime
                && gameTime - observedAtGameTime <= maximumAgeTicks;
    }
}
