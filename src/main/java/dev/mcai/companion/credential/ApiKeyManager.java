package dev.mcai.companion.credential;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.mcai.companion.model.SecretSource;

/**
 * Setup-facing credential coordinator and request-facing SecretSource.
 */
public final class ApiKeyManager implements SecretSource, AutoCloseable {
    public static final String ENVIRONMENT_VARIABLE =
        InjectedCredentialSource.KEY_ENVIRONMENT;
    public static final String FILE_ENVIRONMENT_VARIABLE =
        InjectedCredentialSource.FILE_ENVIRONMENT;

    private static final String SERVICE =
        "dev.mcai.companion.model-api-key";

    private final ProcessCredentialStore sessionStore =
        new ProcessCredentialStore();
    private final Optional<NamedStore> persistentStore;
    private final InjectedCredentialSource injected;

    public ApiKeyManager() {
        this(
            Path.of(
                System.getProperty("user.home", "."),
                ".mcai-companion"
            ),
            System.getenv()
        );
    }

    public ApiKeyManager(final Path configDirectory) {
        this(configDirectory, System.getenv());
    }

    ApiKeyManager(
        final Path configDirectory,
        final Map<String, String> environment
    ) {
        this(
            configDirectory,
            environment,
            selectPersistentStore(
                configDirectory,
                System.getProperty(
                    "user.name",
                    "minecraft-player"
                )
            )
        );
    }

    /**
     * Test-only constructor for a deterministic platform-store substitute.
     * Production callers always use the OS-specific selection above.
     */
    ApiKeyManager(
        final Path configDirectory,
        final Map<String, String> environment,
        final ApiCredentialStore persistent,
        final String storage
    ) {
        this(
            configDirectory,
            environment,
            persistent == null
                ? Optional.empty()
                : Optional.of(new NamedStore(
                    persistent,
                    Objects.requireNonNull(storage, "storage")
                ))
        );
    }

    private ApiKeyManager(
        final Path configDirectory,
        final Map<String, String> environment,
        final Optional<NamedStore> selected
    ) {
        Objects.requireNonNull(configDirectory, "configDirectory");
        persistentStore = Objects.requireNonNull(selected, "selected");
        injected = new InjectedCredentialSource(environment);
    }

    public synchronized SaveResult saveFromSetup(
        final char[] credential,
        final boolean preferPersistent
    ) throws CredentialException {
        sessionStore.save(credential);
        if (!preferPersistent) {
            return new SaveResult(false, "process_only");
        }
        if (persistentStore.isEmpty()) {
            return new SaveResult(
                false,
                "process_only_secure_store_unavailable"
            );
        }
        try {
            final NamedStore selected =
                persistentStore.orElseThrow();
            selected.store().save(credential);
            /*
             * Some platform helpers report a successful write even when the
             * item is unreadable (or, historically on macOS, when an empty
             * value was accepted from a non-interactive stdin).  Verify the
             * exact round trip before claiming restart-safe persistence.  The
             * loaded copy never leaves this method and is wiped immediately.
             */
            final Optional<char[]> restored = selected.store().load();
            final char[] restoredValue = restored.orElse(null);
            final boolean roundTrip = restoredValue != null
                && sameCredential(credential, restoredValue);
            if (restoredValue != null) {
                Arrays.fill(restoredValue, '\0');
            }
            if (!roundTrip) {
                return new SaveResult(
                    false,
                    "process_only_secure_store_unavailable"
                );
            }
            return new SaveResult(true, selected.storage());
        } catch (CredentialException exception) {
            return new SaveResult(
                false,
                "process_only_secure_store_unavailable"
            );
        }
    }

    public synchronized boolean unlockPersisted() throws CredentialException {
        /*
         * An explicit process injection is the operator's authoritative
         * choice for dedicated servers, containers and service managers.  It
         * must win over a stale Keychain/DPAPI/Secret-Service item left by a
         * previous profile; otherwise rotating MCAI_API_KEY would appear to
         * do nothing until the old desktop credential was manually deleted.
         * The value is copied into the process store and the temporary array
         * is wiped by cache().
         */
        final Optional<char[]> supplied = injected.load();
        if (supplied.isPresent()) {
            cache(supplied.orElseThrow());
            return true;
        }
        CredentialException platformFailure = null;
        if (persistentStore.isPresent()) {
            try {
                final Optional<char[]> loaded =
                    persistentStore.orElseThrow().store().load();
                if (loaded.isPresent()) {
                    cache(loaded.orElseThrow());
                    return true;
                }
            } catch (CredentialException exception) {
                platformFailure = exception;
            }
        }
        if (platformFailure != null) {
            throw platformFailure;
        }
        return false;
    }

    @Override
    public char[] acquire() {
        final Optional<char[]> session = sessionStore.load();
        if (session.isPresent()) {
            return session.orElseThrow();
        }
        try {
            return injected.load().orElseGet(() -> new char[0]);
        } catch (CredentialException exception) {
            return new char[0];
        }
    }

    public synchronized void clearSession() {
        sessionStore.clear();
    }

    public synchronized void clearPersisted() throws CredentialException {
        if (persistentStore.isPresent()) {
            persistentStore.orElseThrow().store().clear();
        }
        clearSession();
    }

    @Override
    public synchronized void close() {
        sessionStore.close();
    }

    public record SaveResult(boolean persistent, String storage) {
    }

    private void cache(final char[] credential)
            throws CredentialException {
        try {
            sessionStore.save(credential);
        } finally {
            Arrays.fill(credential, '\0');
        }
    }

    private static boolean sameCredential(
        final char[] expected,
        final char[] actual
    ) {
        if (expected.length != actual.length) {
            return false;
        }
        int difference = 0;
        for (int index = 0; index < expected.length; index++) {
            difference |= expected[index] ^ actual[index];
        }
        return difference == 0;
    }

    private static Optional<NamedStore> selectPersistentStore(
        final Path configDirectory,
        final String account
    ) {
        if (MacOsKeychainCredentialStore.isAvailable()) {
            return Optional.of(new NamedStore(
                new MacOsKeychainCredentialStore(SERVICE, account),
                "macos_keychain"
            ));
        }
        if (WindowsDpapiCredentialStore.isAvailable()) {
            return Optional.of(new NamedStore(
                new WindowsDpapiCredentialStore(configDirectory),
                "windows_dpapi_current_user"
            ));
        }
        if (LinuxSecretServiceCredentialStore.isAvailable()) {
            return Optional.of(new NamedStore(
                new LinuxSecretServiceCredentialStore(
                    SERVICE,
                    account
                ),
                "linux_secret_service"
            ));
        }
        return Optional.empty();
    }

    private record NamedStore(
        ApiCredentialStore store,
        String storage
    ) {
    }
}
