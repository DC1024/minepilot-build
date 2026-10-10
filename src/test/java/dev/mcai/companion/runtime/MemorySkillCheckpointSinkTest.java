package dev.mcai.companion.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.mcai.companion.memory.MemoryDatabase;
import dev.mcai.companion.skill.SkillCheckpoint;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MemorySkillCheckpointSinkTest {
    private static final UUID COMPANION =
        UUID.fromString("71cdf43f-51f3-4a22-9016-aa0b160c0e91");

    @TempDir
    Path temporaryDirectory;

    @Test
    void asynchronouslyPersistsAndReplacesOneGoalsSafeCheckpoint() {
        final Instant timestamp = Instant.parse("2026-07-24T12:00:00Z");
        try (MemoryDatabase memory = MemoryDatabase.open(
            temporaryDirectory.resolve("memory.db")
        )) {
            final MemorySkillCheckpointSink sink = new MemorySkillCheckpointSink(
                memory,
                COMPANION,
                Clock.fixed(timestamp, ZoneOffset.UTC)
            );

            sink.save(
                "survival.gather",
                7,
                11,
                1,
                120,
                new SkillCheckpoint(2, "{\"logs\":3}")
            ).toCompletableFuture().join();
            sink.save(
                "survival.gather",
                7,
                11,
                2,
                140,
                new SkillCheckpoint(2, "{\"logs\":5}")
            ).toCompletableFuture().join();

            final var restored = memory.loadCheckpoint(
                MemorySkillCheckpointSink.taskId(COMPANION, 7)
            ).join().orElseThrow();
            final var state = JsonParser.parseString(restored.stateJson())
                .getAsJsonObject();

            assertEquals(
                MemorySkillCheckpointSink.taskId(COMPANION, 7),
                restored.taskId()
            );
            assertEquals(7, restored.goalRevision());
            assertEquals("survival.gather", restored.skillName());
            assertEquals(timestamp, restored.updatedAt());
            assertEquals(1, state.get("stateFormatVersion").getAsInt());
            assertEquals(11, state.get("worldRevision").getAsLong());
            assertEquals(2, state.get("sequence").getAsLong());
            assertEquals(140, state.get("gameTick").getAsLong());
            assertEquals(2, state.get("skillFormatVersion").getAsInt());
            assertEquals("{\"logs\":5}", state.get("payload").getAsString());
        }
    }

    @Test
    void taskIdsAreStableAndSeparatedByGoalRevision() {
        assertEquals(
            MemorySkillCheckpointSink.taskId(COMPANION, 9),
            MemorySkillCheckpointSink.taskId(COMPANION, 9)
        );
        assertTrue(
            !MemorySkillCheckpointSink.taskId(COMPANION, 9).equals(
                MemorySkillCheckpointSink.taskId(COMPANION, 10)
            )
        );
    }
}
