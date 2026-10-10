package dev.mcai.companion.skills.building;

import dev.mcai.companion.navigation.GridPos;
import java.util.Objects;

/**
 * One generated construction constraint, not a pre-authored blueprint.
 */
public record ShelterBuildStep(
        int index,
        ShelterStepRole role,
        GridPos target
) {
    public ShelterBuildStep {
        if (index < 0) {
            throw new IllegalArgumentException("Step index must be non-negative");
        }
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(target, "target");
    }
}
