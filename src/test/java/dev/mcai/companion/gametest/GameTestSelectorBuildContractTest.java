package dev.mcai.companion.gametest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class GameTestSelectorBuildContractTest {
    @Test
    void selectorsReachGameTestArgument() throws Exception {
        final String build = Files.readString(
                Path.of("build.gradle")
        );

        assertTrue(build.contains("gradleProperty('test_selector')"));
        assertTrue(build.contains("gradleProperty('live_model_selector')"));
        assertTrue(build.contains(
                "def requestedSelector = genericSelector"
        ));
        assertTrue(build.contains(".orElse(legacyLiveSelector)"));
        assertTrue(build.contains("args '--tests', selector"));
        assertTrue(build.contains(
                "test_selector and live_model_selector disagree"
        ));
    }
}
