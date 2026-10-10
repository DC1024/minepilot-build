package dev.mcai.companion.perception;

/**
 * Auditable work and result counts for one sample.
 */
public record ObservationBudgetUsage(
        int entityCandidates,
        int entityLosChecks,
        int dangerCandidatesInspected,
        int blockRaysCast,
        int visibleEntities,
        int visibleBlockFaces,
        int dangerSignals,
        boolean entityCandidatesTruncated,
        boolean visibleEntitiesTruncated,
        boolean visibleBlockFacesTruncated,
        boolean dangerSignalsTruncated
) {
    public ObservationBudgetUsage {
        if (entityCandidates < 0
                || entityLosChecks < 0
                || dangerCandidatesInspected < 0
                || blockRaysCast < 0
                || visibleEntities < 0
                || visibleBlockFaces < 0
                || dangerSignals < 0) {
            throw new IllegalArgumentException("Budget usage cannot be negative");
        }
    }

    public void validateAgainst(PerceptionBudget budget) {
        if (budget == null) {
            throw new IllegalArgumentException("budget is required");
        }
        if (entityCandidates > budget.maxEntityCandidates()
                || entityLosChecks > budget.maxEntityLosChecks()
                || dangerCandidatesInspected > budget.maxEntityCandidates()
                || blockRaysCast > budget.maxBlockRays()
                || visibleEntities > budget.maxVisibleEntities()
                || visibleBlockFaces > budget.maxVisibleBlockFaces()
                || dangerSignals > budget.maxDangerSignals()) {
            throw new IllegalArgumentException("Observation exceeded its declared budget");
        }
    }
}
