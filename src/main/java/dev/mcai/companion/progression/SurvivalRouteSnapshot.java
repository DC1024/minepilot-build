package dev.mcai.companion.progression;

import dev.mcai.companion.waypoint.DimensionRef;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Compact live route readiness derived from the companion's own state and
 * sticky server evidence.
 */
public record SurvivalRouteSnapshot(
        long goalRevision,
        SurvivalRouteProfile profile,
        DimensionRef currentDimension,
        List<SurvivalMilestone> verifiedMilestones,
        Optional<SurvivalMilestone> nextUnverifiedMilestone,
        List<SurvivalSafetyDeficit> currentSafetyDeficits,
        List<SurvivalRouteObjective> nextObjectives,
        Map<String, Integer> criticalOwnedCounts,
        Map<String, Integer> currentMinimumTargets,
        float health,
        int foodLevel,
        boolean hardcore,
        long elapsedEvaluationTicks
) {
    /*
     * Keep this projection bounded, but include every currently published
     * critical field.  The End-loadout contract added bows to the inventory
     * ledger, taking the stable field count from 24 to 25; retaining the old
     * bound made a legitimate observation throw and collapse the brain into
     * observation_unavailable before the model could act.
     */
    private static final int MAX_CRITICAL_COUNT_FIELDS = 32;

    public SurvivalRouteSnapshot {
        if (goalRevision < 0
                || !Float.isFinite(health)
                || health < 0.0F
                || foodLevel < 0
                || foodLevel > 20
                || elapsedEvaluationTicks < -1) {
            throw new IllegalArgumentException(
                    "Survival route state is invalid"
            );
        }
        Objects.requireNonNull(currentDimension, "currentDimension");
        Objects.requireNonNull(profile, "profile");
        verifiedMilestones = List.copyOf(
                Objects.requireNonNull(
                        verifiedMilestones,
                        "verifiedMilestones"
                )
        );
        nextUnverifiedMilestone = Objects.requireNonNull(
                nextUnverifiedMilestone,
                "nextUnverifiedMilestone"
        );
        currentSafetyDeficits = List.copyOf(
                Objects.requireNonNull(
                        currentSafetyDeficits,
                        "currentSafetyDeficits"
                )
        );
        nextObjectives = List.copyOf(
                Objects.requireNonNull(
                        nextObjectives,
                        "nextObjectives"
                )
        );
        criticalOwnedCounts = Map.copyOf(
                Objects.requireNonNull(
                        criticalOwnedCounts,
                        "criticalOwnedCounts"
                )
        );
        currentMinimumTargets = Map.copyOf(
                Objects.requireNonNull(
                        currentMinimumTargets,
                        "currentMinimumTargets"
                )
        );
        if (verifiedMilestones.size()
                > SurvivalMilestone.values().length
                || currentSafetyDeficits.size()
                        > SurvivalSafetyDeficit.values().length
                || nextObjectives.size() > 8
                || criticalOwnedCounts.size() > MAX_CRITICAL_COUNT_FIELDS
                || criticalOwnedCounts.entrySet().stream().anyMatch(entry ->
                        entry.getKey() == null
                            || !entry.getKey().matches("[a-z_]{1,32}")
                            || entry.getValue() == null
                            || entry.getValue() < 0
                            || entry.getValue() > 36 * 64
                )
                || currentMinimumTargets.size() > 16
                || currentMinimumTargets.entrySet().stream().anyMatch(entry ->
                        entry.getKey() == null
                            || !entry.getKey().matches("[a-z_]{1,32}")
                            || entry.getValue() == null
                            || entry.getValue() < 1
                            || entry.getValue() > 36 * 64
                )) {
            throw new IllegalArgumentException(
                    "Survival route projection exceeds its bound"
            );
        }
    }
}
