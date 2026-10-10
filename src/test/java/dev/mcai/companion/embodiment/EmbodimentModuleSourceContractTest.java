package dev.mcai.companion.embodiment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Keeps the Tab-list presence label tied to the live body session rather than
 * to model readiness or a client-only assumption.
 */
final class EmbodimentModuleSourceContractTest {
    @Test
    void tabPresenceUsesServerPlayerSessionStatusOnly() throws IOException {
        final String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/embodiment/"
                        + "EmbodimentModule.java"
        ));

        assertTrue(source.contains(
                "AiPlayerManager.status(server).online()"
        ));
        assertTrue(source.contains("● online"));
        assertTrue(source.contains("○ offline"));
        assertFalse(source.contains("gatewayReady()"));
    }
}
