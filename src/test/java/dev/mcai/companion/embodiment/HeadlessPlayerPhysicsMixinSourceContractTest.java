package dev.mcai.companion.embodiment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Keeps the headless physics override explicitly scoped and wired in ordinary
 * CI. The real GameTest supplies the dynamic proof that a marked companion
 * receives exactly one vanilla travel pass.
 */
final class HeadlessPlayerPhysicsMixinSourceContractTest {
    @Test
    void overrideRequiresBothServerPlayerAndVersionedProfileMarker()
            throws IOException {
        final String source = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/mixin/"
                + "HeadlessPlayerPhysicsMixin.java"
        ));

        assertTrue(source.contains("@Mixin(Player.class)"));
        assertTrue(source.contains("method = \"isEffectiveAi\""));
        assertTrue(source.contains(
            "player instanceof ServerPlayer serverPlayer"
        ));
        assertTrue(source.contains("AiProfileMarker.isMarked("));
        assertTrue(source.contains("callback.setReturnValue(false)"));
        assertFalse(source.contains("getUUID("));
        assertFalse(source.contains("getName("));
    }

    @Test
    void commonMixinIsConfiguredForDevelopmentAndRelease()
            throws IOException {
        final String config = Files.readString(Path.of(
            "src/main/resources/mcai_companion.mixins.json"
        ));
        final String build = Files.readString(Path.of("build.gradle"));

        assertTrue(config.contains("\"required\": true"));
        assertTrue(config.contains("\"HeadlessPlayerPhysicsMixin\""));
        assertTrue(build.contains(
            "args '--mixin.config', 'mcai_companion.mixins.json'"
        ));
        assertTrue(build.contains(
            "'MixinConfigs': 'mcai_companion.mixins.json,"
                + "mcai_companion.client.mixins.json'"
        ));
        assertTrue(build.contains(
            "'HeadlessPlayerPhysicsMixin.class'"
        ));
        assertTrue(build.contains(
            "archive.getJarEntry(requiredEntry)"
        ));
    }
}
