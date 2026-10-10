package dev.mcai.companion.embodiment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class EndReturnAuditSourceContractTest {
    @Test
    void fullChainRequiresTheVanillaEndConqueredRespawnEvidence()
            throws IOException {
        final String tracker = read(
            "src/main/java/dev/mcai/companion/evaluation/"
                + "EvaluationVictoryTracker.java"
        );
        final String respawnHandler = between(
            tracker,
            "private static void onPlayerRespawn(",
            "private static void markReturned("
        );
        assertTrue(respawnHandler.contains(
            "event.isEndConquered()"
        ));
        assertTrue(respawnHandler.contains("markReturned(player)"));

        final String gameTest = read(
            "src/main/java/dev/mcai/companion/embodiment/"
                + "EmbodimentGameTests.java"
        );
        final String returnStage = between(
            gameTest,
            "private void tickReturnPortalEntry()",
            "private void completeFocused()"
        );
        assertTrue(returnStage.contains("Level.OVERWORLD"));
        assertTrue(returnStage.contains(
            "SurvivalMilestone.RETURNED_FROM_END"
        ));
        assertTrue(returnStage.contains(
            "dragonFightPlayerId.equals("
        ));
        assertTrue(returnStage.contains(
            "endCreditsRespawnRequests() == 1L"
        ));
        assertTrue(
            returnStage.indexOf("endCreditsRespawnRequests() == 1L")
                < returnStage.indexOf("AiPlayerManager.requestRemove(")
        );
    }

    @Test
    void connectionAuditRemainsPackagePrivate()
            throws IOException {
        final String manager = read(
            "src/main/java/dev/mcai/companion/embodiment/"
                + "AiPlayerManager.java"
        );
        assertTrue(manager.contains(
            "static Optional<HeadlessConnectionPump.AuditSnapshot> "
                + "connectionAudit("
        ));
        assertFalse(manager.contains(
            "public static Optional<HeadlessConnectionPump.AuditSnapshot> "
                + "connectionAudit("
        ));
    }

    private static String read(final String path)
            throws IOException {
        return Files.readString(Path.of(path));
    }

    private static String between(
            final String source,
            final String start,
            final String end
    ) {
        final int startIndex = source.indexOf(start);
        final int endIndex = source.indexOf(end, startIndex);
        assertTrue(startIndex >= 0 && endIndex > startIndex);
        return source.substring(startIndex, endIndex);
    }
}
