package dev.mcai.companion.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.mcai.companion.action.AcceptedLowLevelAction;
import dev.mcai.companion.action.ActionOutcome;
import dev.mcai.companion.brain.BrainEvent;
import dev.mcai.companion.control.GoalSnapshot;
import dev.mcai.companion.control.GoalSource;
import dev.mcai.companion.control.GoalStatus;
import dev.mcai.companion.memory.MemoryDatabase;
import dev.mcai.companion.model.DecisionKind;
import dev.mcai.companion.model.Protocol;
import dev.mcai.companion.model.RequestTrace;
import dev.mcai.companion.skills.core.ServerOwnedCoreSkillActuator;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RuntimeActionTraceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void recordsOnlyTheFirstAcceptedActionForTheBoundGoal() {
        final AtomicLong revision = new AtomicLong(4L);
        final Instant timestamp =
            Instant.parse("2026-08-02T09:00:00Z");
        try (MemoryDatabase memory = MemoryDatabase.open(
            temporaryDirectory.resolve("memory.db")
        )) {
            final RuntimeActionTrace trace = new RuntimeActionTrace(
                memory,
                () -> goal(revision.get()),
                Clock.fixed(timestamp, ZoneOffset.UTC)
            );
            trace.observe(new BrainEvent.ModelAudit(
                4L,
                "brain-4-1",
                BrainEvent.ModelAuditStage.SKILL_STARTED,
                12L,
                Optional.of(DecisionKind.START_SKILL),
                "follow_entity",
                Optional.of(new RequestTrace(
                    "brain-4-1",
                    "provider-request",
                    Protocol.RESPONSES,
                    200,
                    40
                ))
            ));

            trace.record(new ServerOwnedCoreSkillActuator.AcceptedAction(
                "move",
                120L,
                ActionOutcome.QUEUED
            ));
            trace.record(new ServerOwnedCoreSkillActuator.AcceptedAction(
                "jump",
                121L,
                ActionOutcome.QUEUED
            ));

            final var event = memory.latestEvent(
                "low_level_actions_issued"
            ).join().orElseThrow();
            final var payload = JsonParser.parseString(
                event.payloadJson()
            ).getAsJsonObject();
            assertEquals(timestamp, event.occurredAt());
            assertEquals(4L, event.goalRevision());
            assertEquals(12L, event.worldRevision());
            assertEquals("brain-4-1", payload.get("requestId").getAsString());
            assertEquals("follow_entity", payload.get("skillName").getAsString());
            assertEquals("move", payload.get("action").getAsString());
            assertEquals(120L, payload.get("serverTick").getAsLong());
            assertEquals(
                "QUEUED",
                payload.get("outcome").getAsString()
            );

            trace.observe(new BrainEvent.ModelAudit(
                4L,
                "brain-4-menu",
                BrainEvent.ModelAuditStage.SKILL_STARTED,
                12L,
                Optional.of(DecisionKind.START_SKILL),
                "transfer_menu_item",
                Optional.of(new RequestTrace(
                    "brain-4-menu",
                    "provider-request-menu",
                    Protocol.RESPONSES,
                    200,
                    40
                ))
            ));
            trace.record(new AcceptedLowLevelAction(
                "transfer_menu_item",
                125L,
                "COMPLETED"
            ));
            final var menuEvent = memory.latestEvent(
                "low_level_actions_issued"
            ).join().orElseThrow();
            final var menuPayload = JsonParser.parseString(
                menuEvent.payloadJson()
            ).getAsJsonObject();
            assertEquals(
                "brain-4-menu",
                menuPayload.get("requestId").getAsString()
            );
            assertEquals(
                "transfer_menu_item",
                menuPayload.get("action").getAsString()
            );
            assertEquals(
                "COMPLETED",
                menuPayload.get("outcome").getAsString()
            );

            revision.set(5L);
            trace.observe(new BrainEvent.ModelAudit(
                4L,
                "brain-4-2",
                BrainEvent.ModelAuditStage.SKILL_STARTED,
                13L,
                Optional.of(DecisionKind.START_SKILL),
                "move_to",
                Optional.of(new RequestTrace(
                    "brain-4-2",
                    "",
                    Protocol.CHAT_COMPLETIONS,
                    200,
                    50
                ))
            ));
            trace.record(new ServerOwnedCoreSkillActuator.AcceptedAction(
                "move",
                130L,
                ActionOutcome.QUEUED
            ));
            assertTrue(memory.latestEvent(
                "low_level_actions_issued"
            ).join().orElseThrow().payloadJson().contains("brain-4-menu"));
        }
    }

    private static GoalSnapshot goal(final long revision) {
        return new GoalSnapshot(
            Optional.empty(),
            revision,
            GoalStatus.RUNNING,
            GoalSource.PLAYER_CHAT,
            "follow",
            "",
            Instant.EPOCH,
            false
        );
    }
}
