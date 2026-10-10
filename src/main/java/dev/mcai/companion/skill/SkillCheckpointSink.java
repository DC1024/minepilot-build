package dev.mcai.companion.skill;

import java.util.concurrent.CompletionStage;

/**
 * Persists a safe skill checkpoint.
 *
 * <p>The supervisor invokes this method away from the game tick thread and
 * never waits for the returned stage. Implementations should also return the
 * stage promptly. At most one write and one coalesced newer checkpoint are
 * retained by the supervisor.</p>
 */
@FunctionalInterface
public interface SkillCheckpointSink {
    CompletionStage<Void> save(
            String skillName,
            long goalRevision,
            long worldRevision,
            long sequence,
            long gameTick,
            SkillCheckpoint checkpoint
    );

    static SkillCheckpointSink discard() {
        return (skillName, goalRevision, worldRevision, sequence, gameTick, checkpoint) ->
                java.util.concurrent.CompletableFuture.completedFuture(null);
    }
}
