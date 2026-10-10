package dev.mcai.companion.evaluation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class EvaluationResultWriterSourceContractTest {
    @Test
    void terminalResultIsAtomicMachineReadableAndSeedFree()
            throws IOException {
        final String source = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/evaluation/"
                + "EvaluationResultWriter.java"
        ));

        assertTrue(source.contains("evaluation-result.json"));
        assertTrue(source.contains("ATOMIC_MOVE"));
        assertTrue(source.contains("elapsedTicks"));
        assertTrue(source.contains("dragonKilled"));
        assertTrue(source.contains("returnedFromEnd"));
        assertFalse(source.contains("getSeed("));
        assertFalse(source.contains("apiKey"));
        assertFalse(source.contains("api_key"));
    }
}
