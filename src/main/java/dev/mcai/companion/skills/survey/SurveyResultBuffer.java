package dev.mcai.companion.skills.survey;

import java.util.Objects;
import java.util.Optional;

/**
 * Goal-scoped handoff from a completed local survey to planner context.
 */
public final class SurveyResultBuffer {
    private long goalRevision = -1;
    private SurveyResultSnapshot snapshot;

    public synchronized void publish(
            final SurveyResultSnapshot result
    ) {
        snapshot = Objects.requireNonNull(result, "result");
        goalRevision = result.goalRevision();
    }

    public synchronized Optional<SurveyResultSnapshot> snapshot(
            final long requestedGoalRevision
    ) {
        return snapshot != null && goalRevision == requestedGoalRevision
                ? Optional.of(snapshot)
                : Optional.empty();
    }

    public synchronized void clear() {
        goalRevision = -1;
        snapshot = null;
    }
}
