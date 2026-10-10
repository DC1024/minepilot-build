package dev.mcai.companion.embodiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class PendingPlayerSpawnTest {
    @Test
    void headlessClientRequestsConfiguredServerViewDistance() {
        assertEquals(10, HeadlessViewDistance.requested(10));
    }

    @Test
    void headlessViewDistanceStaysInsideVanillaBounds() {
        assertEquals(2, HeadlessViewDistance.requested(0));
        assertEquals(32, HeadlessViewDistance.requested(64));
    }

    @Test
    void unanchoredPreparationHasOnlyShortLoginGraceWindow() {
        assertEquals(
                40,
                PendingPlayerSpawn.UNANCHORED_LOGIN_GRACE_TICKS
        );
    }

    @Test
    void loginCanReplaceOnlyAnUnanchoredPendingPreparation()
            throws IOException {
        final String manager = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/embodiment/"
                    + "AiPlayerManager.java"
        ));
        assertTrue(manager.contains(
                "manager.pendingSpawn != null"
        ));
        assertTrue(manager.contains(
                "!manager.pendingSpawn.anchored()"
        ));
        assertTrue(manager.contains(
                "return requestSpawnNear(server, anchor);"
        ));
        assertTrue(manager.contains(
                "lifecycle.beginStop();"
        ));
        final String pending = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/embodiment/"
                    + "PendingPlayerSpawn.java"
        ));
        assertTrue(pending.contains(
                "unanchoredLoginGraceTicks > 0"
        ));
        assertTrue(pending.contains(
                "UNANCHORED_LOGIN_GRACE_TICKS"
        ));
    }
}
