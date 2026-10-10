package dev.mcai.companion.control;

public interface GoalRevisionStore {
    long current();

    long advance();
}
