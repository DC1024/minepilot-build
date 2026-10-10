package dev.mcai.companion.credential;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

/**
 * Non-interactive secret injection for dedicated servers and containers.
 */
final class InjectedCredentialSource {
    static final String KEY_ENVIRONMENT = "MCAI_API_KEY";
    static final String FILE_ENVIRONMENT = "MCAI_API_KEY_FILE";
    static final String SYSTEMD_DIRECTORY = "CREDENTIALS_DIRECTORY";
    static final String SYSTEMD_FILE_NAME = "mcai-api-key";
    private static final int MAXIMUM_FILE_BYTES = 32 * 1024;

    private final Map<String, String> environment;

    InjectedCredentialSource(final Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
    }

    Optional<char[]> load() throws CredentialException {
        final String direct = environment.get(KEY_ENVIRONMENT);
        if (direct != null && !direct.isBlank()) {
            final char[] credential = direct.toCharArray();
            try {
                return Optional.of(
                    CredentialRules.validatedCopy(credential)
                );
            } finally {
                Arrays.fill(credential, '\0');
            }
        }
        final String configuredPath =
            environment.get(FILE_ENVIRONMENT);
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Optional.of(read(path(configuredPath)));
        }
        final String systemdDirectory =
            environment.get(SYSTEMD_DIRECTORY);
        if (systemdDirectory != null
                && !systemdDirectory.isBlank()) {
            final Path credential = path(systemdDirectory)
                .resolve(SYSTEMD_FILE_NAME);
            if (Files.isRegularFile(credential)) {
                return Optional.of(read(credential));
            }
        }
        return Optional.empty();
    }

    private static char[] read(final Path path)
            throws CredentialException {
        final byte[] encoded;
        try {
            final long size = Files.size(path);
            if (size <= 0 || size > MAXIMUM_FILE_BYTES) {
                throw new CredentialException(
                    "Injected API credential file has invalid size"
                );
            }
            encoded = Files.readAllBytes(path);
        } catch (IOException | SecurityException exception) {
            throw new CredentialException(
                "Injected API credential file could not be read",
                exception
            );
        }
        try {
            final byte[] exact =
                CredentialCommand.trimLineEnding(encoded);
            try {
                return CredentialUtf8.decode(exact);
            } finally {
                Arrays.fill(exact, (byte) 0);
            }
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private static Path path(final String value)
            throws CredentialException {
        try {
            return Path.of(value);
        } catch (RuntimeException exception) {
            throw new CredentialException(
                "Injected API credential path is invalid",
                exception
            );
        }
    }
}
