package dev.mcai.companion.communication;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ConversationCommitmentSourceContractTest {
    @Test
    void immediateTaskAcknowledgementDoesNotClaimPhysicalActionStarted()
            throws IOException {
        final String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/communication/"
                        + "CompanionConversationCoordinator.java"
        ));

        assertTrue(source.contains(
                "任务已经创建；我正在规划第一个动作。"
        ));
        assertTrue(source.contains(
                "The task is active; I am planning the first action."
        ));
        assertFalse(source.contains(
                "任务已经创建；我现在开始行动。"
        ));
        assertFalse(source.contains(
                "The task is active; I am acting on it now."
        ));
        assertTrue(source.contains(
                "conversation_model_task_suppressed"
        ));
        assertTrue(source.contains(
                "taskAddressed = singlePlayer || explicitlyAddressed"
        ));
        assertTrue(source.contains("emitTaskAccepted("));
        assertTrue(source.contains("sha256(normalizedMessage)"));
        assertTrue(source.contains("model_selected_task"));
    }

    @Test
    void slowConversationRequestReportsProgressOnceWithoutCancellingIt()
            throws IOException {
        final String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/communication/"
                        + "CompanionConversationCoordinator.java"
        ));

        assertTrue(source.contains("conversation_model_soft_deadline"));
        assertTrue(source.contains("inFlight.softDeadlineReported = true"));
        assertTrue(source.contains("我还在处理这条消息，请稍等。"));
        assertTrue(source.contains("I am still processing that message."));
        assertTrue(source.contains("softDeadlineReported"));
    }

    @Test
    void providerFailuresAreNotPresentedAsUnclearPlayerSpeech()
            throws IOException {
        final String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/communication/"
                        + "CompanionConversationCoordinator.java"
        ));

        assertTrue(source.contains("transientRetryMessage("));
        assertTrue(source.contains("exhaustedFailureMessage("));
        assertTrue(source.contains("missingOutcomeMessage("));
        assertFalse(source.contains("我刚才没听清，再说一次？"));
    }

    @Test
    void stopIsALocalSafeCheckpointCommandAndDoesNotNeedASecondModelTurn()
            throws IOException {
        final String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/communication/"
                        + "CompanionConversationCoordinator.java"
        ));

        assertTrue(source.contains("PlayerTaskIntent.isCancellationRequest"));
        assertTrue(source.contains("cancelFromPlayer(sender, normalized)"));
        assertTrue(source.contains("conversation_task_cancel_requested"));
        assertTrue(source.contains("safe checkpoint"));
        assertTrue(source.contains("GoalSource.PLAYER_CHAT"));
    }

    @Test
    void queuesOnlyWhileAStartupProbeIsActuallyInFlight() {
        assertTrue(
                CompanionConversationCoordinator
                        .shouldQueueUntilModelReady(false, true)
        );
        assertFalse(
                CompanionConversationCoordinator
                        .shouldQueueUntilModelReady(true, true)
        );
        assertFalse(
                CompanionConversationCoordinator
                        .shouldQueueUntilModelReady(false, false)
        );
    }

    @Test
    void startupQueueIsBoundedAndExpires() throws IOException {
        final String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/communication/"
                        + "CompanionConversationCoordinator.java"
        ));

        assertTrue(source.contains(
                "conversation_model_startup_message_queued"
        ));
        assertTrue(source.contains(
                "conversation_model_startup_message_expired"
        ));
        assertTrue(source.contains(
                "conversation_model_startup_queue_cleared"
        ));
        assertTrue(source.contains(
                "MAX_MODEL_STARTUP_QUEUE_TICKS = 600L"
        ));
        assertTrue(source.contains("queue.size() >= MAX_QUEUE"));
    }

    @Test
    void runtimeWiresTheRealProbeStateInsteadOfTheCompatibilityDefault()
            throws IOException {
        final String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/runtime/CompanionRuntime.java"
        ));

        assertTrue(source.contains(
                "() -> model.snapshot().probeInFlight()"
        ));
    }

    @Test
    void runtimeWiresConfiguredSoftDeadlineIntoBothModelLanes()
            throws IOException {
        final String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/runtime/CompanionRuntime.java"
        ));

        assertTrue(source.contains(
                "CompanionConfig.MODEL_SOFT_TIMEOUT_SECONDS.get()"
        ));
        assertTrue(source.contains(
                "modelSoftTimeout"
        ));
        assertTrue(source.contains(
                "modelSoftTimeout,\n                modelHardTimeout"
        ));
        assertTrue(source.contains(
                "coreFrames::recordPlayerThreatWarning,\n"
                    + "                modelSoftTimeout,\n"
                    + "                database"
        ));
    }
}
