package dev.mcai.companion.skills.interaction;

import dev.mcai.companion.perception.VisibleBlockFace;
import java.util.Optional;

@FunctionalInterface
public interface InteractionSkillFrameSource {
    Optional<InteractionSkillFrame> current();

    /**
     * Returns a bounded historical first-person sample when available.
     * Implementations must never reconstruct it from current world state.
     */
    default Optional<InteractionSkillFrame> atObservation(
            final long observationRevision
    ) {
        return current().filter(frame ->
                frame.observationRevision() == observationRevision
        );
    }

    /**
     * Returns the block surface currently selected by the body's own
     * first-person crosshair.
     *
     * <p>This is a tick-local OUTLINE ray, equivalent to the target a normal
     * client can select at the centre of its screen. It must not scan nearby
     * blocks, load chunks, or return a hit through an obstruction.</p>
     */
    default Optional<VisibleBlockFace> currentCrosshairBlock() {
        return Optional.empty();
    }
}
