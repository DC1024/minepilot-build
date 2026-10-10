package dev.mcai.companion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class ForgeCompatibilityMetadataTest {
    @Test
    void minecraftTwentySixTwoAcceptsEveryForgeSixtyFivePatch()
            throws IOException {
        final var stream = getClass().getResourceAsStream(
                "/META-INF/mods.toml"
        );
        assertNotNull(stream);

        final String metadata;
        try (stream) {
            metadata = new String(
                    stream.readAllBytes(),
                    StandardCharsets.UTF_8
            );
        }

        assertTrue(metadata.contains("loaderVersion=\"[65,66)\""));
        assertTrue(metadata.contains(
                "versionRange=\"[65.0.0,66)\""
        ));
        assertTrue(metadata.contains("versionRange=\"[26.2]\""));
        assertTrue(metadata.contains(
                "version=\"" + BuildInfo.VERSION + "\""
        ));
        assertFalse(metadata.contains("versionRange=\"[65.0.8]\""));
        assertFalse(metadata.contains("versionRange=\"[64.0.14]\""));
    }
}
