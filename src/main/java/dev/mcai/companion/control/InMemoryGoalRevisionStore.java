package dev.mcai.companion.control;

import java.util.concurrent.atomic.AtomicLong;

public final class InMemoryGoalRevisionStore implements GoalRevisionStore {
    private final AtomicLong revision;

    public InMemoryGoalRevisionStore() {
        this(0L);
    }

    public InMemoryGoalRevisionStore(final long initialRevision) {
        if (initialRevision < 0) {
            throw new IllegalArgumentException("initialRevision must be non-negative");
        }
        revision = new AtomicLong(initialRevision);
    }

    @Override
    public long current() {
        return revision.get();
    }

    @Override
    public long advance() {
        return revision.updateAndGet(previous -> Math.addExact(previous, 1L));
    }
}
