package dev.mcai.companion.embodiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class HeadlessConnectionPumpSourceContractTest {
    @Test
    void winGameUsesOneVanillaClientCommandAndNoWorldMutation()
            throws IOException {
        final String source = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/embodiment/"
                + "HeadlessConnectionPump.java"
        ));

        assertTrue(source.contains(
            "instanceof ClientboundGameEventPacket gameEvent"
        ));
        assertTrue(source.contains(
            "endCreditsResponseGate.claim("
        ));
        assertTrue(source.contains(
            "ServerboundClientCommandPacket.Action"
        ));
        assertTrue(source.contains(".PERFORM_RESPAWN"));
        assertTrue(source.contains(
            "endCreditsRespawnRequests++"
        ));
        assertTrue(source.contains(
            "long endCreditsRespawnRequests,"
        ));
        assertEquals(
            1,
            occurrences(source, "listener.handleClientCommand(")
        );
        assertFalse(source.contains(".respawn("));
        assertFalse(source.contains(".teleportTo("));
        assertFalse(source.contains(".changeDimension("));
        assertFalse(source.contains(".showEndCredits("));
    }

    @Test
    void completedChunkBatchesReceiveBoundedVanillaAcknowledgement()
            throws IOException {
        final String source = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/embodiment/"
                + "HeadlessConnectionPump.java"
        ));

        assertTrue(source.contains(
            "instanceof ClientboundChunkBatchFinishedPacket"
        ));
        assertTrue(source.contains(
            "listener.handleChunkBatchReceived("
        ));
        assertTrue(source.contains(
            "new ServerboundChunkBatchReceivedPacket("
        ));
        assertTrue(source.contains(
            "private static final float DESIRED_CHUNKS_PER_TICK = 3.5F"
        ));
        assertTrue(source.contains(
            "chunkBatchAcknowledgements++"
        ));
        assertFalse(source.contains(".getChunk("));
        assertFalse(source.contains(".setChunkForced("));
    }

    private static int occurrences(
            final String source,
            final String value
    ) {
        return source.split(
            java.util.regex.Pattern.quote(value),
            -1
        ).length - 1;
    }
}
