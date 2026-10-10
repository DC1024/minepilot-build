package dev.mcai.companion.credential;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ApiKeyManagerConcurrencyTest {
    @TempDir
    private Path temporary;

    @Test
    void aNewSetupKeyCannotBeOverwrittenByAnInFlightRestore()
            throws Exception {
        final BlockingStore store = new BlockingStore("old-key");
        try (var manager = new ApiKeyManager(
                temporary,
                Map.of(),
                store,
                "test_store"
        )) {
            final CompletableFuture<Boolean> restore =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return manager.unlockPersisted();
                    } catch (CredentialException exception) {
                        throw new AssertionError(exception);
                    }
                });
            assertTrue(store.loadStarted.await(2, TimeUnit.SECONDS));

            final CompletableFuture<ApiKeyManager.SaveResult> save =
                CompletableFuture.supplyAsync(() -> {
                    final char[] value = "new-key".toCharArray();
                    try {
                        return manager.saveFromSetup(value, true);
                    } catch (CredentialException exception) {
                        throw new AssertionError(exception);
                    } finally {
                        Arrays.fill(value, '\0');
                    }
                });

            /*
             * Without the manager lock, save() reaches the substitute store
             * while load() is paused, and the old load can then overwrite the
             * new process-lifetime value. With the lock, save waits until the
             * restore has cached its value.
             */
            if (!store.saveEntered.await(200, TimeUnit.MILLISECONDS)) {
                store.releaseLoad.countDown();
                assertTrue(store.saveEntered.await(2, TimeUnit.SECONDS));
            } else {
                store.releaseLoad.countDown();
            }
            store.allowSave.countDown();

            assertTrue(restore.get(2, TimeUnit.SECONDS));
            assertTrue(save.get(2, TimeUnit.SECONDS).persistent());
            final char[] acquired = manager.acquire();
            try {
                assertArrayEquals("new-key".toCharArray(), acquired);
            } finally {
                Arrays.fill(acquired, '\0');
            }
        } finally {
            store.releaseLoad.countDown();
            store.allowSave.countDown();
        }
    }

    private static final class BlockingStore implements ApiCredentialStore {
        private final CountDownLatch loadStarted = new CountDownLatch(1);
        private final CountDownLatch releaseLoad = new CountDownLatch(1);
        private final CountDownLatch saveEntered = new CountDownLatch(1);
        private final CountDownLatch allowSave = new CountDownLatch(1);
        private char[] value;

        private BlockingStore(final String initial) {
            value = initial.toCharArray();
        }

        @Override
        public Optional<char[]> load() throws CredentialException {
            loadStarted.countDown();
            await(releaseLoad);
            return Optional.of(value.clone());
        }

        @Override
        public void save(final char[] credential) throws CredentialException {
            saveEntered.countDown();
            await(allowSave);
            Arrays.fill(value, '\0');
            value = CredentialRules.validatedCopy(credential);
        }

        @Override
        public void clear() {
            Arrays.fill(value, '\0');
            value = new char[0];
        }

        @Override
        public boolean persistent() {
            return true;
        }

        private static void await(final CountDownLatch latch)
                throws CredentialException {
            try {
                if (!latch.await(2, TimeUnit.SECONDS)) {
                    throw new CredentialException(
                        "credential concurrency test latch timed out"
                    );
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new CredentialException(
                    "credential concurrency test interrupted",
                    exception
                );
            }
        }
    }
}
