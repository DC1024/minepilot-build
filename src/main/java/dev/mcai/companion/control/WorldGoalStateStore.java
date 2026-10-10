package dev.mcai.companion.control;

import dev.mcai.companion.world.CompanionWorldData;
import java.util.Objects;
import java.util.Optional;

public final class WorldGoalStateStore implements GoalStateStore {
    private final CompanionWorldData worldData;

    public WorldGoalStateStore(final CompanionWorldData worldData) {
        this.worldData = Objects.requireNonNull(worldData, "worldData");
    }

    @Override
    public Optional<PersistedGoalState> load() {
        return worldData.persistedGoalState();
    }

    @Override
    public void save(final PersistedGoalState state) {
        worldData.updateGoalState(state);
    }
}
