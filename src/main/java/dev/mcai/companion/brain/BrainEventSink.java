package dev.mcai.companion.brain;

@FunctionalInterface
public interface BrainEventSink {
    void emit(BrainEvent event);

    static BrainEventSink discard() {
        return event -> {};
    }
}
