package dev.mcai.companion.perception;

import java.util.Objects;

/**
 * The loaded, unobstructed portion of a first-person block ray. It either
 * reached its bounded endpoint without a hit or ended immediately before a
 * visual collision shape.
 *
 * <p>This is explicit negative evidence for local free-space mapping. It
 * carries no block, chunk, structure, or registry data.</p>
 */
public record ClearSightRay(
        PerceptionVec3 endPosition,
        double distance,
        PerceptionProvenance provenance
) {
    public ClearSightRay {
        Objects.requireNonNull(endPosition, "endPosition");
        distance = PerceptionValidation.finite(
                distance,
                "distance"
        );
        Objects.requireNonNull(provenance, "provenance");
        if (distance <= 0.0
                || provenance
                    != PerceptionProvenance.BLOCK_RAY_CLEAR_MISS
                && provenance
                    != PerceptionProvenance.BLOCK_RAY_CLEAR_BEFORE_HIT) {
            throw new IllegalArgumentException(
                    "Invalid clear-sight ray"
            );
        }
    }
}
