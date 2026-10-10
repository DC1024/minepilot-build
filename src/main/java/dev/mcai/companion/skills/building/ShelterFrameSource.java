package dev.mcai.companion.skills.building;

import dev.mcai.companion.perception.VisibleBlockFace;
import java.util.Optional;

@FunctionalInterface
public interface ShelterFrameSource {
    Optional<ShelterFrame> current();

    /**
     * Returns the bounded, server-authored first-person sample identified by
     * {@code observationRevision}, when it is still retained.
     *
     * <p>A model response is asynchronous, so the current camera sample will
     * normally be newer by the time a decision reaches the game thread.
     * Implementations must return the original sample rather than
     * reconstructing it from current world state.</p>
     */
    default Optional<ShelterFrame> atObservation(
            final long observationRevision
    ) {
        return current().filter(frame ->
                frame.observationRevision() == observationRevision
        );
    }

    /**
     * Returns only the surface selected by the body's centre crosshair.
     *
     * <p>Unlike the wider semantic ray fan, this tick-local OUTLINE ray is
     * suitable as final evidence for an ordinary player interaction. It must
     * neither scan nearby blocks nor load chunks.</p>
     */
    default Optional<VisibleBlockFace> currentCrosshairBlock() {
        return Optional.empty();
    }
}
