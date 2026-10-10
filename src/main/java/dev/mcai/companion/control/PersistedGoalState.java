package dev.mcai.companion.control;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Small restart-safe goal state. Large task checkpoints remain in SQLite.
 */
public record PersistedGoalState(
    long revision,
    Optional<UUID> goalId,
    GoalStatus status,
    GoalSource source,
    String goal,
    String detailCode,
    Instant updatedAt,
    boolean externalWritesLocked
) {
    public PersistedGoalState {
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
        goalId = Objects.requireNonNull(goalId, "goalId");
        status = Objects.requireNonNull(status, "status");
        source = Objects.requireNonNull(source, "source");
        goal = Objects.requireNonNull(goal, "goal");
        detailCode = Objects.requireNonNull(detailCode, "detailCode");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (goal.length() > GoalCoordinator.MAX_GOAL_CHARACTERS
            || detailCode.length() > 64) {
            throw new IllegalArgumentException("Persisted goal fields exceed bounds");
        }
        if (status != GoalStatus.IDLE
            && (goalId.isEmpty() || goal.isBlank())) {
            throw new IllegalArgumentException(
                "A non-idle persisted goal needs an id and text"
            );
        }
    }

    public static PersistedGoalState idle(final long revision) {
        return new PersistedGoalState(
            revision,
            Optional.empty(),
            GoalStatus.IDLE,
            GoalSource.RECOVERY,
            "",
            "",
            Instant.EPOCH,
            false
        );
    }
}
