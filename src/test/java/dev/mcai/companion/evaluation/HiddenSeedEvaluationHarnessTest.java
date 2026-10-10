package dev.mcai.companion.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HiddenSeedEvaluationHarnessTest {
    @TempDir
    Path temporary;

    @Test
    void prepareOnlyCreatesFreshHardcoreCasesAndSeedFreeSummary()
            throws Exception {
        final Path template = temporary.resolve("template");
        Files.createDirectories(template);
        Files.writeString(
            template.resolve("server.properties"),
            "motd=hidden-seed-test\nonline-mode=false\n",
            StandardCharsets.UTF_8
        );
        final Path output = temporary.resolve("suite");
        final Process process = new ProcessBuilder(
            "python3",
            "scripts/run-hidden-seed-evaluations.py",
            "--template-dir",
            template.toString(),
            "--output-dir",
            output.toString(),
            "--cases",
            "2",
            "--prepare-only"
        )
            .directory(Path.of(".").toFile())
            .redirectErrorStream(true)
            .start();
        final String console = new String(
            process.getInputStream().readAllBytes(),
            StandardCharsets.UTF_8
        );

        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), console);
        final String summaryText = Files.readString(
            output.resolve("summary.json")
        );
        final var summary = JsonParser.parseString(summaryText)
            .getAsJsonObject();
        assertEquals(2, summary.get("cases").getAsInt());
        assertEquals(0, summary.get("terminalCases").getAsInt());
        assertTrue(summaryText.contains("\"executed\": false"));
        assertFalse(summaryText.contains("\"seed\":"));
        assertFalse(summaryText.contains("apiKey"));
        assertFalse(summaryText.contains("api_key"));

        final var first = summary.getAsJsonArray("results")
            .get(0)
            .getAsJsonObject();
        final var second = summary.getAsJsonArray("results")
            .get(1)
            .getAsJsonObject();
        assertEquals(64, first.get("seedCommitment").getAsString().length());
        assertNotEquals(
            first.get("seedCommitment").getAsString(),
            second.get("seedCommitment").getAsString()
        );

        for (int index = 1; index <= 2; index++) {
            final Path properties = output.resolve(
                "case-%04d/server.properties".formatted(index)
            );
            final String generated = Files.readString(properties);
            assertTrue(generated.contains("hardcore=true"));
            assertTrue(generated.contains("difficulty=hard"));
            assertTrue(generated.contains("gamemode=survival"));
            assertTrue(generated.contains("enable-command-block=false"));
            assertFalse(
                Files.exists(
                    output.resolve("case-%04d/world".formatted(index))
                )
            );
        }

        final String privateManifest = Files.readString(
            output.resolve("private-seeds.json")
        );
        assertTrue(privateManifest.contains("\"seed\":"));
        assertFalse(summaryText.equals(privateManifest));
    }
}
