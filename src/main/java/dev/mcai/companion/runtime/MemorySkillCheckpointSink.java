package dev.mcai.companion.runtime;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.mcai.companion.memory.MemoryDatabase;
import dev.mcai.companion.memory.TaskCheckpoint;
import dev.mcai.companion.skill.SkillCheckpoint;
import dev.mcai.companion.skill.SkillCheckpointSink;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Bridges safe local-skill checkpoints to the world's serialized SQLite
 * writer without ever blocking the server tick.
 */
public final class MemorySkillCheckpointSink implements SkillCheckpointSink {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int STATE_FORMAT_VERSION = 1;

    private final MemoryDatabase memory;
    private final UUID companionUuid;
    private final Clock clock;

    public MemorySkillCheckpointSink(
        final MemoryDatabase memory,
        final UUID companionUuid
    ) {
        this(memory, companionUuid, Clock.systemUTC());
    }

    MemorySkillCheckpointSink(
        final MemoryDatabase memory,
        final UUID companionUuid,
        final Clock clock
    ) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.companionUuid = Objects.requireNonNull(companionUuid, "companionUuid");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public CompletionStage<Void> save(
        final String skillName,
        final long goalRevision,
        final long worldRevision,
        final long sequence,
        final long gameTick,
        final SkillCheckpoint checkpoint
    ) {
        Objects.requireNonNull(skillName, "skillName");
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (goalRevision < 0 || worldRevision < 0 || sequence < 1 || gameTick < 0) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                new IllegalArgumentException("Checkpoint metadata is invalid")
            );
        }

        final JsonObject state = new JsonObject();
        state.addProperty("stateFormatVersion", STATE_FORMAT_VERSION);
        state.addProperty("worldRevision", worldRevision);
        state.addProperty("sequence", sequence);
        state.addProperty("gameTick", gameTick);
        state.addProperty("skillFormatVersion", checkpoint.formatVersion());
        state.addProperty("payload", checkpoint.payload());

        return memory.saveCheckpoint(new TaskCheckpoint(
            taskId(companionUuid, goalRevision),
            goalRevision,
            skillName,
            GSON.toJson(state),
            clock.instant()
        ));
    }

    /**
     * A goal revision is monotonic for one companion, so this stable ID makes
     * newer safe checkpoints replace older ones for the same goal.
     */
    public static UUID taskId(final UUID companionUuid, final long goalRevision) {
        Objects.requireNonNull(companionUuid, "companionUuid");
        if (goalRevision < 0) {
            throw new IllegalArgumentException("goalRevision must be non-negative");
        }
        final String material = "mcai-skill-checkpoint:"
            + companionUuid
            + ":"
            + goalRevision;
        return UUID.nameUUIDFromBytes(material.getBytes(StandardCharsets.UTF_8));
    }
}
