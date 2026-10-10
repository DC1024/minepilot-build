package dev.mcai.companion.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MemoryDatabaseTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsEventsAndTaskCheckpointsAcrossReopen() throws Exception {
        final Path databaseFile = temporaryDirectory.resolve("memory.db");
        final UUID taskId = UUID.randomUUID();
        final Instant checkpointTime = Instant.parse("2026-07-24T01:02:03Z");

        try (MemoryDatabase database = MemoryDatabase.open(databaseFile)) {
            database.appendEvent(new MemoryEvent(
                Instant.parse("2026-07-24T01:00:00Z"),
                "goal.started",
                "test",
                "{\"goal\":\"survive\"}",
                4,
                2
            )).get(5, TimeUnit.SECONDS);
            database.saveCheckpoint(new TaskCheckpoint(
                taskId,
                2,
                "gather_wood",
                "{\"remaining\":3}",
                checkpointTime
            )).get(5, TimeUnit.SECONDS);

            assertEquals(1L, database.eventCount().get(5, TimeUnit.SECONDS));
        }

        try (MemoryDatabase reopened = MemoryDatabase.open(databaseFile)) {
            final var checkpoint = reopened.loadCheckpoint(taskId).get(5, TimeUnit.SECONDS);
            assertTrue(checkpoint.isPresent());
            assertEquals("gather_wood", checkpoint.orElseThrow().skillName());
            assertEquals(checkpointTime, checkpoint.orElseThrow().updatedAt());
            assertEquals(1L, reopened.eventCount().get(5, TimeUnit.SECONDS));
            assertFalse(reopened.loadCheckpoint(UUID.randomUUID()).get(5, TimeUnit.SECONDS).isPresent());
        }
    }

    @Test
    void rejectsWritesAfterClose() {
        final MemoryDatabase database = MemoryDatabase.open(temporaryDirectory.resolve("closed.db"));
        database.close();

        final var result = database.appendEvent(new MemoryEvent(
            Instant.now(),
            "test",
            "test",
            "{}",
            0,
            0
        ));

        assertTrue(result.isCompletedExceptionally());
    }

    @Test
    void persistsRecentConversationInChronologicalOrder()
            throws Exception {
        final Path databaseFile = temporaryDirectory.resolve(
                "conversation.db"
        );
        try (MemoryDatabase database = MemoryDatabase.open(databaseFile)) {
            database.appendConversationTurn(new ConversationTurn(
                    Instant.parse("2026-08-24T00:00:01Z"),
                    "follow me",
                    "Following."
            )).get(5, TimeUnit.SECONDS);
            database.appendConversationTurn(new ConversationTurn(
                    Instant.parse("2026-08-24T00:00:02Z"),
                    "do you remember?",
                    "Yes."
            )).get(5, TimeUnit.SECONDS);
        }

        try (MemoryDatabase reopened = MemoryDatabase.open(databaseFile)) {
            final var turns = reopened.loadRecentConversationTurns(16)
                    .get(5, TimeUnit.SECONDS);
            assertEquals(2, turns.size());
            assertEquals("follow me", turns.getFirst().player());
            assertEquals("Yes.", turns.getLast().agent());
            assertTrue(turns.getFirst().sequence() < turns.getLast().sequence());
        }
    }
}
