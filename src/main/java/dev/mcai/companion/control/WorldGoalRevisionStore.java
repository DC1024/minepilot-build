package dev.mcai.companion.control;

import java.util.Objects;

import dev.mcai.companion.world.CompanionWorldData;

public final class WorldGoalRevisionStore implements GoalRevisionStore {
    private final CompanionWorldData worldData;

    public WorldGoalRevisionStore(final CompanionWorldData worldData) {
        this.worldData = Objects.requireNonNull(worldData, "worldData");
    }

    @Override
    public long current() {
        return worldData.goalRevision();
    }

    @Override
    public long advance() {
        return worldData.advanceGoalRevision();
    }
}
