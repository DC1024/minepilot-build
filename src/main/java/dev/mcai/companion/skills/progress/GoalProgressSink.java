package dev.mcai.companion.skills.progress;

@FunctionalInterface
public interface GoalProgressSink {
    void append(long goalRevision, String note);
}
