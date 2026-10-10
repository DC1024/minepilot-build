package dev.mcai.companion.credential;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class CrossPlatformCredentialSourceTest {
    @TempDir
    private Path temporary;

    @Test
    void directEnvironmentTakesPriorityWithoutPersistence()
            throws Exception {
        final var source = new InjectedCredentialSource(Map.of(
            InjectedCredentialSource.KEY_ENVIRONMENT,
            "direct-test-key",
            InjectedCredentialSource.FILE_ENVIRONMENT,
            temporary.resolve("missing").toString()
        ));
        final char[] loaded = source.load().orElseThrow();
        try {
            assertArrayEquals(
                "direct-test-key".toCharArray(),
                loaded
            );
        } finally {
            Arrays.fill(loaded, '\0');
        }
    }

    @Test
    void explicitAndSystemdFilesTrimOnlyLineEndings()
            throws Exception {
        final Path explicit = temporary.resolve("docker-secret");
        Files.writeString(explicit, "file-test-key\n");
        final char[] direct = new InjectedCredentialSource(Map.of(
            InjectedCredentialSource.FILE_ENVIRONMENT,
            explicit.toString()
        )).load().orElseThrow();
        try {
            assertArrayEquals("file-test-key".toCharArray(), direct);
        } finally {
            Arrays.fill(direct, '\0');
        }

        final Path systemd = temporary.resolve(
            InjectedCredentialSource.SYSTEMD_FILE_NAME
        );
        Files.writeString(systemd, "systemd-test-key\r\n");
        final char[] loaded = new InjectedCredentialSource(Map.of(
            InjectedCredentialSource.SYSTEMD_DIRECTORY,
            temporary.toString()
        )).load().orElseThrow();
        try {
            assertArrayEquals(
                "systemd-test-key".toCharArray(),
                loaded
            );
        } finally {
            Arrays.fill(loaded, '\0');
        }
    }

    @Test
    void invalidInjectedFilesFailClosed() throws Exception {
        final Path invalid = temporary.resolve("invalid-secret");
        Files.writeString(invalid, "has whitespace");
        assertThrows(
            CredentialException.class,
            () -> new InjectedCredentialSource(Map.of(
                InjectedCredentialSource.FILE_ENVIRONMENT,
                invalid.toString()
            )).load()
        );
    }

    @Test
    void managerUsesInjectedFileWithoutSavingItToWorld()
            throws Exception {
        final Path credential = temporary.resolve("api-key");
        Files.writeString(credential, "manager-test-key\n");
        try (var manager = new ApiKeyManager(
                temporary.resolve("config"),
                Map.of(
                    ApiKeyManager.FILE_ENVIRONMENT_VARIABLE,
                    credential.toString()
                )
        )) {
            final char[] acquired = manager.acquire();
            try {
                assertArrayEquals(
                    "manager-test-key".toCharArray(),
                    acquired
                );
            } finally {
                Arrays.fill(acquired, '\0');
            }
        }
        assertEquals("manager-test-key\n", Files.readString(credential));
        assertFalse(
            Files.exists(
                temporary.resolve("config")
                    .resolve("mcai-companion")
                    .resolve("credentials")
                    .resolve("model-api-key.dpapi")
            )
        );
    }

    @Test
    void windowsCiphertextPathIsInstanceScoped() {
        final Path config = temporary.resolve("instance-config");
        final Path credential =
            WindowsDpapiCredentialStore.credentialPath(config);
        assertTrue(credential.startsWith(config));
        assertEquals(
            "model-api-key.dpapi",
            credential.getFileName().toString()
        );
    }
}
