package dev.mcai.companion.control;

import java.util.Objects;
import java.util.Optional;

public final class InMemoryGoalStateStore implements GoalStateStore {
    private PersistedGoalState state;

    @Override
    public synchronized Optional<PersistedGoalState> load() {
        return Optional.ofNullable(state);
    }

    @Override
    public synchronized void save(final PersistedGoalState value) {
        state = Objects.requireNonNull(value, "value");
    }
}
