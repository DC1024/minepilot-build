package dev.mcai.companion.communication;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Keeps the production chat boundary tied to the human-player count. The
 * Forge event wiring itself is exercised by the physical presence slices;
 * this small source contract prevents a future refactor from silently
 * reverting to the incorrect "non-dedicated means single-player" rule.
 */
final class CommunicationModuleSourceContractTest {
    @Test
    void countsHumansAndExcludesTheCompanionInsteadOfUsingServerType()
            throws IOException {
        final String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/communication/"
                        + "CommunicationModule.java"
        ));

        assertTrue(source.contains("humanPlayerCount"));
        assertTrue(source.contains("getPlayerList()"));
        assertTrue(source.contains("companionUuid()"));
        assertTrue(source.contains("mayControlCompanion(source)"));
        assertFalse(source.contains("mayAdmin(source),\n            singlePlayer"));
        assertFalse(source.contains(
                "final boolean singlePlayer = !source.getServer()"
                        + ".isDedicatedServer()"
        ));
    }

    @Test
    void taskPermissionIsNotConfusedWithAConversationAcknowledgement()
            throws IOException {
        final String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/communication/"
                        + "CompanionConversationCoordinator.java"
        ));

        assertTrue(source.contains(
                "conversation_task_permission_denied"
        ));
        assertTrue(source.contains(
                "!maySetGoal && taskAddressed && immediateIntent.task()"
        ));
        assertTrue(source.contains("chat.allowedSenders"));
        assertTrue(source.contains("sendTo(sender.getUUID(), denial)"));
    }
}
