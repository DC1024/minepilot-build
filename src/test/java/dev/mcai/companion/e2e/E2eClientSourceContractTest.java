package dev.mcai.companion.e2e;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Keeps the real-client evidence writer tolerant of system chat messages.
 * This is a source contract only; it is not a claim that a rendered client
 * or real model gate has run.
 */
final class E2eClientSourceContractTest {
    @Test
    void systemChatWithNoSenderCannotAbortEvidenceStream() throws IOException {
        final String source = Files.readString(Path.of(
                "src/e2eClient/java/dev/mcai/e2e/client/"
                        + "McaiE2eClientMod.java"
        ));

        assertTrue(source.contains("event.getSender() == null"));
        assertTrue(source.contains("? \"\""));
        assertTrue(source.contains(": event.getSender().toString()"));
    }
}
