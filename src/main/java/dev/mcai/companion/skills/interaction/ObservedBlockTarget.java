package dev.mcai.companion.skills.interaction;

import dev.mcai.companion.action.BlockFace;
import java.util.Objects;

/**
 * Model-visible identity for a block face from one semantic sample.
 *
 * <p>The precise ray hit remains internal and is recovered from that sample
 * immediately before the fair action is dispatched.</p>
 */
public record ObservedBlockTarget(
        long sampleSequence,
        int x,
        int y,
        int z,
        BlockFace face
) {
    public ObservedBlockTarget {
        if (sampleSequence < 0) {
            throw new IllegalArgumentException(
                    "sampleSequence must be non-negative"
            );
        }
        Objects.requireNonNull(face, "face");
    }
}
