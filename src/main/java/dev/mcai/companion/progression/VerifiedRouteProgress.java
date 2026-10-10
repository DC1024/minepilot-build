package dev.mcai.companion.progression;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Persistent, goal-scoped milestones that were independently observed by the
 * server. This is distinct from model-authored progress notes.
 */
public record VerifiedRouteProgress(
        long goalRevision,
        Set<SurvivalMilestone> milestones
) {
    public VerifiedRouteProgress {
        if (goalRevision < -1) {
            throw new IllegalArgumentException(
                    "Route goal revision is invalid"
            );
        }
        Objects.requireNonNull(milestones, "milestones");
        milestones = milestones.isEmpty()
                ? Set.of()
                : Set.copyOf(EnumSet.copyOf(milestones));
        if (goalRevision < 0 && !milestones.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unbound route progress cannot contain milestones"
            );
        }
    }

    public static VerifiedRouteProgress empty() {
        return new VerifiedRouteProgress(-1, Set.of());
    }
}
