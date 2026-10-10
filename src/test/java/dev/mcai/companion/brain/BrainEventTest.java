package dev.mcai.companion.brain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mcai.companion.model.DecisionKind;
import dev.mcai.companion.model.Protocol;
import dev.mcai.companion.model.RequestTrace;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class BrainEventTest {
    @Test
    void usageAllowsUnknownOrNonNegativeCountsOnly() {
        assertDoesNotThrow(() ->
            new BrainEvent.Usage(1L, "request-1", -1L, -1L, -1L)
        );
        assertDoesNotThrow(() ->
            new BrainEvent.Usage(1L, "request-1", 100L, 20L, 120L)
        );
        assertThrows(IllegalArgumentException.class, () ->
            new BrainEvent.Usage(1L, "request-1", -2L, 0L, 0L)
        );
    }

    @Test
    void modelAuditRequiresCausalMetadataAtEachStage() {
        final RequestTrace trace = new RequestTrace(
            "brain-1-1",
            "provider-request",
            Protocol.RESPONSES,
            200,
            25
        );
        assertDoesNotThrow(() -> new BrainEvent.ModelAudit(
            1L,
            "brain-1-1",
            BrainEvent.ModelAuditStage.SKILL_STARTED,
            7L,
            Optional.of(DecisionKind.START_SKILL),
            "follow_entity",
            Optional.of(trace)
        ));
        assertThrows(IllegalArgumentException.class, () ->
            new BrainEvent.ModelAudit(
                1L,
                "brain-1-1",
                BrainEvent.ModelAuditStage.SKILL_STARTED,
                7L,
                Optional.of(DecisionKind.REPLAN),
                "follow_entity",
                Optional.of(trace)
            )
        );
        assertThrows(IllegalArgumentException.class, () ->
            new BrainEvent.ModelAudit(
                1L,
                "brain-1-1",
                BrainEvent.ModelAuditStage.MODEL_RESPONSE_RECEIVED,
                7L,
                Optional.of(DecisionKind.START_SKILL),
                "follow_entity",
                Optional.empty()
            )
        );
    }

    @Test
    void taskAcceptanceStoresOnlyValidatedContentFingerprint() {
        assertDoesNotThrow(() -> new BrainEvent.TaskAccepted(
                4L,
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "a".repeat(64),
                "follow"
        ));
        assertThrows(IllegalArgumentException.class, () ->
                new BrainEvent.TaskAccepted(
                        4L,
                        UUID.randomUUID(),
                        "not-a-sha256",
                        "follow"
                )
        );
    }
}
