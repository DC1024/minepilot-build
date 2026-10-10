package dev.mcai.companion.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Keeps startup failures actionable for a player who has an inert but present
 * companion body.  The real server wiring is covered by Forge slices; this
 * contract prevents provider error codes from becoming the only UI message.
 */
final class ModelBootstrapStatusMessageSourceContractTest {
    @Test
    void startupCredentialAndProviderFailuresExplainTheInertBody()
            throws IOException {
        final String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/runtime/"
                        + "ModelBootstrapCoordinator.java"
        ));

        assertTrue(source.contains("startupStatusMessage(code)"));
        assertTrue(source.contains("startup_model_waiting_for_credential"));
        assertTrue(source.contains("startup_model_unavailable_invalid_configuration"));
        assertTrue(source.contains("AI 身体仍在世界中"));
        assertTrue(source.contains("不会执行模型动作"));
        assertTrue(source.contains("API Key 未通过验证"));
        assertTrue(source.contains("没有执行自动重试或供应商回退"));
    }
}
