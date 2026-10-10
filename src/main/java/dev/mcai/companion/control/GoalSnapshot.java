package dev.mcai.companion.control;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record GoalSnapshot(
    Optional<UUID> goalId,
    long revision,
    GoalStatus status,
    GoalSource source,
    String goal,
    String detailCode,
    Instant updatedAt,
    boolean externalWritesLocked
) {
    public GoalSnapshot {
        goalId = Objects.requireNonNull(goalId, "goalId");
        status = Objects.requireNonNull(status, "status");
        source = Objects.requireNonNull(source, "source");
        goal = Objects.requireNonNull(goal, "goal");
        detailCode = Objects.requireNonNull(detailCode, "detailCode");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
    }
}
