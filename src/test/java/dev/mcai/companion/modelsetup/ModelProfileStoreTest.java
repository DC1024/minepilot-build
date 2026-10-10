package dev.mcai.companion.modelsetup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.model.ProviderCapabilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ModelProfileStoreTest {
    @TempDir
    Path directory;

    @Test
    void restoresVerifiedNonSecretProfileAcrossInstances()
            throws Exception {
        final ModelProfileStore first =
                new ModelProfileStore(directory);
        first.save(
                "https://provider.example/v1/",
                "example-model"
        );

        final var restored =
                new ModelProfileStore(directory).load();

        assertTrue(restored.isPresent());
        assertEquals(
                "https://provider.example/v1",
                restored.orElseThrow().baseUrl()
        );
        assertEquals(
                "example-model",
                restored.orElseThrow().modelName()
        );
        final String raw = Files.readString(
                directory.resolve("mcai-companion")
                        .resolve(ModelProfileStore.FILE_NAME),
                StandardCharsets.UTF_8
        );
        assertFalse(raw.toLowerCase().contains("apikey"));
        assertFalse(raw.toLowerCase().contains("api_key"));
    }

    @Test
    void restoresExactPreviouslyVerifiedWireCapabilities()
            throws Exception {
        final ProviderCapabilities capabilities =
                ProviderCapabilities.chatJsonSchema(false)
                        .withImageInput(true);
        new ModelProfileStore(directory).save(
                "https://provider.example/v1",
                "example-model",
                capabilities
        );

        final ModelProfileStore.Profile restored =
                new ModelProfileStore(directory)
                        .load()
                        .orElseThrow();

        assertEquals(
                capabilities,
                restored.capabilities().orElseThrow()
        );
    }

    @Test
    void authenticationFailureRetainsEndpointButDropsCapabilities()
            throws Exception {
        final ModelProfileStore store = new ModelProfileStore(directory);
        store.save(
                "https://provider.example/v1",
                "example-model",
                ProviderCapabilities.chatJsonSchema(false)
        );

        store.invalidateCapabilities();

        final ModelProfileStore.Profile restored = store.load().orElseThrow();
        assertEquals("https://provider.example/v1", restored.baseUrl());
        assertEquals("example-model", restored.modelName());
        assertTrue(restored.capabilities().isEmpty());
    }

    @Test
    void rejectsCorruptedOrInvalidProfiles() throws Exception {
        final Path profileDirectory =
                directory.resolve("mcai-companion");
        Files.createDirectories(profileDirectory);
        Files.writeString(
                profileDirectory.resolve(
                        ModelProfileStore.FILE_NAME
                ),
                """
                {"version":1,"baseUrl":"http://remote.invalid/v1",
                 "modelName":"model"}
                """,
                StandardCharsets.UTF_8
        );

        assertTrue(new ModelProfileStore(directory).load().isEmpty());
    }

    @Test
    void retainsValidEndpointWhenCapabilityCacheIsCorrupted()
            throws Exception {
        final Path profileDirectory =
                directory.resolve("mcai-companion");
        Files.createDirectories(profileDirectory);
        Files.writeString(
                profileDirectory.resolve(
                        ModelProfileStore.FILE_NAME
                ),
                """
                {
                  "version":2,
                  "baseUrl":"https://provider.example/v1",
                  "modelName":"model",
                  "capabilities":{
                    "protocol":"NOT_A_PROTOCOL"
                  }
                }
                """,
                StandardCharsets.UTF_8
        );

        final ModelProfileStore.Profile restored =
                new ModelProfileStore(directory)
                        .load()
                        .orElseThrow();

        assertEquals(
                "https://provider.example/v1",
                restored.baseUrl()
        );
        assertEquals("model", restored.modelName());
        assertTrue(restored.capabilities().isEmpty());
    }
}
