package dev.mcai.companion.credential;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * macOS Keychain adapter.
 *
 * <p>The {@code security(1)} command accepts a password on stdin only for
 * interactive prompts; when {@code -w} is supplied without a value it can
 * exit successfully while storing an empty password in a non-interactive
 * process.  The only non-interactive form supported by the system utility is
 * therefore {@code -w <password>}.  The argument is held only for the short
 * lifetime of the child process, is never logged, and is cleared from the
 * parent command array immediately after process creation.  Reads and all
 * failure messages continue to omit the secret.</p>
 */
public final class MacOsKeychainCredentialStore implements ApiCredentialStore {
    private static final Path SECURITY = Path.of("/usr/bin/security");
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAXIMUM_OUTPUT_BYTES = 16 * 1024;

    private final String service;
    private final String account;

    public MacOsKeychainCredentialStore(final String service, final String account) {
        this.service = requireIdentifier(service, "service");
        this.account = requireIdentifier(account, "account");
    }

    public static boolean isAvailable() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac")
            && Files.isExecutable(SECURITY);
    }

    @Override
    public Optional<char[]> load() throws CredentialException {
        ensureAvailable();
        final CommandResult result = execute(
            null,
            "find-generic-password",
            "-a", account,
            "-s", service,
            "-w"
        );
        try {
            if (result.exitCode() == 44) {
                return Optional.empty();
            }
            if (result.exitCode() != 0) {
                throw new CredentialException("macOS Keychain could not read the API credential");
            }
            final byte[] bytes = trimLineEnding(result.output());
            try {
                final java.nio.CharBuffer decoded = StandardCharsets.UTF_8
                    .decode(java.nio.ByteBuffer.wrap(bytes));
                final char[] credential = new char[decoded.remaining()];
                decoded.get(credential);
                try {
                    return Optional.of(CredentialRules.validatedCopy(credential));
                } finally {
                    Arrays.fill(credential, '\0');
                    if (decoded.hasArray()) {
                        Arrays.fill(decoded.array(), '\0');
                    }
                }
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        } finally {
            Arrays.fill(result.output(), (byte) 0);
        }
    }

    @Override
    public void save(final char[] credential) throws CredentialException {
        ensureAvailable();
        final char[] validated = CredentialRules.validatedCopy(credential);
        java.nio.ByteBuffer encoded = null;
        try {
            encoded = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(validated));
            final byte[] passwordBytes = new byte[encoded.remaining()];
            encoded.get(passwordBytes);
            try {
                final CommandResult result = execute(
                    null,
                    "add-generic-password",
                    "-a", account,
                    "-s", service,
                    "-U",
                    "-w",
                    new String(validated)
                );
                Arrays.fill(result.output(), (byte) 0);
                if (result.exitCode() != 0) {
                    throw new CredentialException("macOS Keychain could not save the API credential");
                }
            } finally {
                Arrays.fill(passwordBytes, (byte) 0);
            }
        } finally {
            Arrays.fill(validated, '\0');
            if (encoded != null && encoded.hasArray()) {
                Arrays.fill(encoded.array(), (byte) 0);
            }
        }
    }

    @Override
    public void clear() throws CredentialException {
        ensureAvailable();
        final CommandResult result = execute(
            null,
            "delete-generic-password",
            "-a", account,
            "-s", service
        );
        Arrays.fill(result.output(), (byte) 0);
        if (result.exitCode() != 0 && result.exitCode() != 44) {
            throw new CredentialException("macOS Keychain could not delete the API credential");
        }
    }

    @Override
    public boolean persistent() {
        return true;
    }

    private static CommandResult execute(
        final byte[] standardInput,
        final String... arguments
    ) throws CredentialException {
        final String[] command = new String[arguments.length + 1];
        command[0] = SECURITY.toString();
        System.arraycopy(arguments, 0, command, 1, arguments.length);

        try {
            final ProcessBuilder builder = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
            final Process process = builder.start();
            /* Do not retain the secret in the parent command list after the
             * OS has accepted the child process arguments. */
            Arrays.fill(command, "");
            java.util.Collections.fill(builder.command(), "");
            try (OutputStream output = process.getOutputStream()) {
                if (standardInput != null) {
                    output.write(standardInput);
                }
            }
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new CredentialException("macOS Keychain operation timed out");
            }
            final byte[] response;
            try (var input = process.getInputStream();
                 var buffer = new ByteArrayOutputStream()) {
                input.transferTo(new BoundedOutputStream(buffer, MAXIMUM_OUTPUT_BYTES));
                response = buffer.toByteArray();
            }
            return new CommandResult(process.exitValue(), response);
        } catch (IOException exception) {
            throw new CredentialException("Unable to invoke macOS Keychain", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CredentialException("macOS Keychain operation was interrupted", exception);
        }
    }

    private static byte[] trimLineEnding(final byte[] input) {
        int length = input.length;
        while (length > 0 && (input[length - 1] == '\n' || input[length - 1] == '\r')) {
            length--;
        }
        return Arrays.copyOf(input, length);
    }

    private static void ensureAvailable() throws CredentialException {
        if (!isAvailable()) {
            throw new CredentialException("macOS Keychain is unavailable");
        }
    }

    private static String requireIdentifier(final String value, final String label) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException("Invalid Keychain " + label);
        }
        return value;
    }

    private record CommandResult(int exitCode, byte[] output) {
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final int maximum;
        private int count;

        private BoundedOutputStream(final OutputStream delegate, final int maximum) {
            this.delegate = delegate;
            this.maximum = maximum;
        }

        @Override
        public void write(final int value) throws IOException {
            if (count >= maximum) {
                throw new IOException("Credential command output exceeded limit");
            }
            delegate.write(value);
            count++;
        }
    }
}
