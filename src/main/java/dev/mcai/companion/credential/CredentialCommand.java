package dev.mcai.companion.credential;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Executes a fixed credential helper without ever placing secret material in
 * argv or the child environment.
 */
final class CredentialCommand {
    private CredentialCommand() {
    }

    static Result execute(
        final Path executable,
        final byte[] standardInput,
        final Duration timeout,
        final int maximumOutputBytes,
        final String... arguments
    ) throws CredentialException {
        final String[] command = new String[arguments.length + 1];
        command[0] = executable.toString();
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        try {
            final Process process = new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            try (var readers =
                    Executors.newVirtualThreadPerTaskExecutor()) {
                final Future<byte[]> response = readers.submit(() -> {
                    try (var input = process.getInputStream();
                         var buffer = new ByteArrayOutputStream()) {
                        input.transferTo(
                            new BoundedOutputStream(
                                buffer,
                                maximumOutputBytes
                            )
                        );
                        return buffer.toByteArray();
                    }
                });
                try (OutputStream output = process.getOutputStream()) {
                    if (standardInput != null) {
                        output.write(standardInput);
                    }
                }
                if (!process.waitFor(
                        timeout.toMillis(),
                        TimeUnit.MILLISECONDS
                )) {
                    process.destroyForcibly();
                    throw new CredentialException(
                        "Credential helper operation timed out"
                    );
                }
                return new Result(
                    process.exitValue(),
                    response.get()
                );
            } catch (ExecutionException exception) {
                process.destroyForcibly();
                throw new CredentialException(
                    "Credential helper output could not be read",
                    exception.getCause()
                );
            }
        } catch (IOException exception) {
            throw new CredentialException(
                "Unable to invoke the platform credential helper",
                exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CredentialException(
                "Platform credential operation was interrupted",
                exception
            );
        }
    }

    static byte[] trimLineEnding(final byte[] input) {
        int length = input.length;
        while (length > 0
                && (input[length - 1] == '\n'
                    || input[length - 1] == '\r')) {
            length--;
        }
        return Arrays.copyOf(input, length);
    }

    record Result(int exitCode, byte[] output) {
    }

    private static final class BoundedOutputStream
            extends OutputStream {
        private final OutputStream delegate;
        private final int maximum;
        private int count;

        private BoundedOutputStream(
            final OutputStream delegate,
            final int maximum
        ) {
            this.delegate = delegate;
            this.maximum = maximum;
        }

        @Override
        public void write(final int value) throws IOException {
            if (count >= maximum) {
                throw new IOException(
                    "Credential helper output exceeded limit"
                );
            }
            delegate.write(value);
            count++;
        }
    }
}
