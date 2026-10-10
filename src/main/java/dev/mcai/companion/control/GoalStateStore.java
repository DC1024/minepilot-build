package dev.mcai.companion.control;

import java.util.Optional;

/**
 * Persistence boundary for the restart-critical goal and evaluation lock.
 */
public interface GoalStateStore {
    Optional<PersistedGoalState> load();

    void save(PersistedGoalState state);

    static GoalStateStore none() {
        return NoOpHolder.INSTANCE;
    }

    enum NoOpHolder implements GoalStateStore {
        INSTANCE;

        @Override
        public Optional<PersistedGoalState> load() {
            return Optional.empty();
        }

        @Override
        public void save(final PersistedGoalState state) {
            // Tests and pure callers may deliberately opt out of persistence.
        }
    }
}
