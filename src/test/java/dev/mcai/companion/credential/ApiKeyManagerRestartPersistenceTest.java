package dev.mcai.companion.credential;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.runtime.ModelRuntime;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the production boundary that matters after a Minecraft restart:
 * the second server-scoped manager must unlock the same persistent store into
 * process memory before a model probe or setup-screen state is evaluated.
 */
final class ApiKeyManagerRestartPersistenceTest {
    @TempDir
    Path temporary;

    @Test
    void aFreshServerManagerRestoresThePersistedCredential() throws Exception {
        final MemoryStore persistent = new MemoryStore();
        final char[] original = "restart-persistence-test".toCharArray();
        try (ApiKeyManager first = new ApiKeyManager(
                temporary,
                Map.of(),
                persistent,
                "test_persistent_store"
        )) {
            try {
                assertTrue(first.saveFromSetup(original, true).persistent());
            } finally {
                Arrays.fill(original, '\0');
            }
        }

        try (ApiKeyManager second = new ApiKeyManager(
                temporary,
                Map.of(),
                persistent,
                "test_persistent_store"
        )) {
            assertTrue(second.unlockPersisted());
            final char[] restored = second.acquire();
            try {
                assertArrayEquals(
                        "restart-persistence-test".toCharArray(),
                        restored
                );
            } finally {
                Arrays.fill(restored, '\0');
            }
        }
    }

    @Test
    void unavailableSecureStoreFallsBackToExplicitProcessOnlySession()
            throws Exception {
        try (ApiKeyManager keys = new ApiKeyManager(
                temporary,
                Map.of(),
                null,
                "unavailable_secure_store"
        ); ModelRuntime runtime = new ModelRuntime(
                keys,
                "https://provider.example/v1",
                "persistent-save-test",
                Duration.ofSeconds(1),
                Duration.ofSeconds(2)
        )) {
            final char[] candidate = "restart-safe-test".toCharArray();
            final ModelRuntime.ProfileUpdateOutcome outcome;
            try {
                outcome = runtime.updateProfile(
                        "https://provider.example/v1",
                        "persistent-save-test",
                        candidate,
                        true
                ).toCompletableFuture().get(2, TimeUnit.SECONDS);
            } finally {
                Arrays.fill(candidate, '\0');
            }

            assertTrue(outcome.accepted());
            assertEquals(
                    "process_only_secure_store_unavailable",
                    outcome.credentialStorage()
            );
            assertFalse(outcome.credentialPersistent());
            assertTrue(runtime.snapshot().credentialAvailable());
        }
    }

    @Test
    void aWriteThatCannotBeReadBackIsNotReportedAsPersistent()
            throws Exception {
        try (ApiKeyManager manager = new ApiKeyManager(
                temporary.resolve("write-only"),
                Map.of(),
                new WriteOnlyStore(),
                "write_only_store"
        )) {
            final char[] candidate = "write-only-regression".toCharArray();
            try {
                final ApiKeyManager.SaveResult result =
                    manager.saveFromSetup(candidate, true);
                assertFalse(result.persistent());
                assertEquals(
                    "process_only_secure_store_unavailable",
                    result.storage()
                );
            } finally {
                Arrays.fill(candidate, '\0');
            }
            final char[] acquired = manager.acquire();
            try {
                assertArrayEquals(
                    "write-only-regression".toCharArray(),
                    acquired
                );
            } finally {
                Arrays.fill(acquired, '\0');
            }
        }
    }

    @Test
    void aFreshServerManagerRestoresAnInjectedCredentialWithoutUiInput()
            throws Exception {
        final Map<String, String> environment = Map.of(
            ApiKeyManager.ENVIRONMENT_VARIABLE,
            "injected-restart-test"
        );
        try (ApiKeyManager first = new ApiKeyManager(
                temporary.resolve("first"),
                environment,
                null,
                "unavailable_secure_store"
        )) {
            assertTrue(first.unlockPersisted());
            final char[] acquired = first.acquire();
            try {
                assertArrayEquals(
                    "injected-restart-test".toCharArray(),
                    acquired
                );
            } finally {
                Arrays.fill(acquired, '\0');
            }
        }
        try (ApiKeyManager second = new ApiKeyManager(
                temporary.resolve("second"),
                environment,
                null,
                "unavailable_secure_store"
        )) {
            assertTrue(second.unlockPersisted());
            final char[] acquired = second.acquire();
            try {
                assertArrayEquals(
                    "injected-restart-test".toCharArray(),
                    acquired
                );
            } finally {
                Arrays.fill(acquired, '\0');
            }
        }
    }

    @Test
    void explicitInjectionOverridesAStalePersistentCredential()
            throws Exception {
        final MemoryStore persistent = new MemoryStore("stale-key");
        final Map<String, String> environment = Map.of(
            ApiKeyManager.ENVIRONMENT_VARIABLE,
            "rotated-injected-key"
        );
        try (ApiKeyManager manager = new ApiKeyManager(
                temporary.resolve("precedence"),
                environment,
                persistent,
                "test_persistent_store"
        )) {
            assertTrue(manager.unlockPersisted());
            final char[] acquired = manager.acquire();
            try {
                assertArrayEquals(
                    "rotated-injected-key".toCharArray(),
                    acquired
                );
            } finally {
                Arrays.fill(acquired, '\0');
            }
        }
    }

    private static final class MemoryStore implements ApiCredentialStore {
        private char[] value = new char[0];

        private MemoryStore() {
        }

        private MemoryStore(final String initial) {
            value = initial.toCharArray();
        }

        @Override
        public synchronized Optional<char[]> load() {
            return value.length == 0
                    ? Optional.empty()
                    : Optional.of(value.clone());
        }

        @Override
        public synchronized void save(final char[] credential)
                throws CredentialException {
            final char[] validated = CredentialRules.validatedCopy(credential);
            Arrays.fill(value, '\0');
            value = validated;
        }

        @Override
        public synchronized void clear() {
            Arrays.fill(value, '\0');
            value = new char[0];
        }

        @Override
        public boolean persistent() {
            return true;
        }
    }

    private static final class WriteOnlyStore implements ApiCredentialStore {
        private char[] value = new char[0];

        @Override
        public synchronized Optional<char[]> load() {
            return Optional.empty();
        }

        @Override
        public synchronized void save(final char[] credential)
                throws CredentialException {
            Arrays.fill(value, '\0');
            value = CredentialRules.validatedCopy(credential);
        }

        @Override
        public synchronized void clear() {
            Arrays.fill(value, '\0');
            value = new char[0];
        }

        @Override
        public boolean persistent() {
            return true;
        }
    }
}
