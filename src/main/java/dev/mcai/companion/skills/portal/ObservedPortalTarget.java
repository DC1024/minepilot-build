package dev.mcai.companion.skills.portal;

import dev.mcai.companion.action.BlockFace;
import java.util.Objects;

/**
 * Model-visible identity of one portal surface from one semantic sample.
 *
 * <p>No hidden block state, ray-hit precision, portal destination, or world
 * lookup is accepted from the caller.</p>
 */
public record ObservedPortalTarget(
        long sampleSequence,
        int x,
        int y,
        int z,
        BlockFace face
) {
    public ObservedPortalTarget {
        if (sampleSequence < 0) {
            throw new IllegalArgumentException(
                    "sampleSequence must be non-negative"
            );
        }
        Objects.requireNonNull(face, "face");
    }
}
