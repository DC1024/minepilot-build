package dev.mcai.companion.credential;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Linux desktop Secret Service adapter backed by libsecret's
 * {@code secret-tool}. Headless servers use an injected secret file or
 * environment variable instead of an application-owned master key.
 */
public final class LinuxSecretServiceCredentialStore
        implements ApiCredentialStore {
    private static final Duration COMMAND_TIMEOUT =
        Duration.ofSeconds(15);
    private static final int MAXIMUM_OUTPUT_BYTES = 64 * 1024;
    private static final Path USR_BIN =
        Path.of("/usr/bin/secret-tool");
    private static final Path BIN = Path.of("/bin/secret-tool");

    private final Path executable;
    private final String service;
    private final String account;

    public LinuxSecretServiceCredentialStore(
        final String service,
        final String account
    ) {
        this(
            executable().orElse(USR_BIN),
            service,
            account
        );
    }

    LinuxSecretServiceCredentialStore(
        final Path executable,
        final String service,
        final String account
    ) {
        this.executable = executable.toAbsolutePath().normalize();
        this.service = requireIdentifier(service, "service");
        this.account = requireIdentifier(account, "account");
    }

    public static boolean isAvailable() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("linux")
            && executable().isPresent();
    }

    @Override
    public Optional<char[]> load() throws CredentialException {
        ensureAvailable();
        final CredentialCommand.Result result =
            CredentialCommand.execute(
                executable,
                null,
                COMMAND_TIMEOUT,
                MAXIMUM_OUTPUT_BYTES,
                "lookup",
                "application", service,
                "account", account
            );
        try {
            if (result.exitCode() != 0) {
                return Optional.empty();
            }
            final byte[] exact =
                CredentialCommand.trimLineEnding(result.output());
            try {
                if (exact.length == 0) {
                    return Optional.empty();
                }
                return Optional.of(CredentialUtf8.decode(exact));
            } finally {
                Arrays.fill(exact, (byte) 0);
            }
        } finally {
            Arrays.fill(result.output(), (byte) 0);
        }
    }

    @Override
    public void save(final char[] credential)
            throws CredentialException {
        ensureAvailable();
        final byte[] encoded = CredentialUtf8.encode(credential);
        try {
            final CredentialCommand.Result result =
                CredentialCommand.execute(
                    executable,
                    encoded,
                    COMMAND_TIMEOUT,
                    MAXIMUM_OUTPUT_BYTES,
                    "store",
                    "--label=Minecraft AI Companion API Key",
                    "application", service,
                    "account", account
                );
            Arrays.fill(result.output(), (byte) 0);
            if (result.exitCode() != 0) {
                throw new CredentialException(
                    "Linux Secret Service could not save "
                        + "the API credential"
                );
            }
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    @Override
    public void clear() throws CredentialException {
        ensureAvailable();
        final CredentialCommand.Result result =
            CredentialCommand.execute(
                executable,
                null,
                COMMAND_TIMEOUT,
                MAXIMUM_OUTPUT_BYTES,
                "clear",
                "application", service,
                "account", account
            );
        Arrays.fill(result.output(), (byte) 0);
        if (result.exitCode() != 0) {
            throw new CredentialException(
                "Linux Secret Service could not delete "
                    + "the API credential"
            );
        }
    }

    @Override
    public boolean persistent() {
        return true;
    }

    private void ensureAvailable() throws CredentialException {
        if (!Files.isExecutable(executable)) {
            throw new CredentialException(
                "Linux Secret Service helper is unavailable"
            );
        }
    }

    private static Optional<Path> executable() {
        if (Files.isExecutable(USR_BIN)) {
            return Optional.of(USR_BIN);
        }
        if (Files.isExecutable(BIN)) {
            return Optional.of(BIN);
        }
        return Optional.empty();
    }

    private static String requireIdentifier(
        final String value,
        final String label
    ) {
        if (value == null || value.isBlank()
                || value.length() > 256) {
            throw new IllegalArgumentException(
                "Invalid Secret Service " + label
            );
        }
        return value;
    }
}
