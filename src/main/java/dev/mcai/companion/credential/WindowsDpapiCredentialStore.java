package dev.mcai.companion.credential;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/**
 * Windows user-scoped DPAPI store. The encrypted blob may live in the
 * instance config directory, but only the Windows identity that protected it
 * can normally decrypt it.
 */
public final class WindowsDpapiCredentialStore
        implements ApiCredentialStore {
    private static final Duration COMMAND_TIMEOUT =
        Duration.ofSeconds(15);
    private static final int MAXIMUM_OUTPUT_BYTES = 128 * 1024;
    private static final int MAXIMUM_BLOB_BYTES = 128 * 1024;
    private static final String PROTECT_SCRIPT = """
        $ErrorActionPreference='Stop'
        $raw=[Console]::In.ReadToEnd()
        $plain=[Convert]::FromBase64String($raw)
        try {
          $cipher=[Security.Cryptography.ProtectedData]::Protect(
            $plain,$null,
            [Security.Cryptography.DataProtectionScope]::CurrentUser)
          try {
            [Console]::Out.Write(
              [Convert]::ToBase64String($cipher))
          } finally {
            [Array]::Clear($cipher,0,$cipher.Length)
          }
        } finally {
          [Array]::Clear($plain,0,$plain.Length)
        }
        """;
    private static final String UNPROTECT_SCRIPT = """
        $ErrorActionPreference='Stop'
        $raw=[Console]::In.ReadToEnd()
        $cipher=[Convert]::FromBase64String($raw)
        try {
          $plain=[Security.Cryptography.ProtectedData]::Unprotect(
            $cipher,$null,
            [Security.Cryptography.DataProtectionScope]::CurrentUser)
          try {
            [Console]::Out.Write(
              [Convert]::ToBase64String($plain))
          } finally {
            [Array]::Clear($plain,0,$plain.Length)
          }
        } finally {
          [Array]::Clear($cipher,0,$cipher.Length)
        }
        """;

    private final Path powershell;
    private final Path credentialFile;

    public WindowsDpapiCredentialStore(
        final Path configDirectory
    ) {
        this(
            defaultPowerShell().orElse(
                Path.of("powershell.exe")
            ),
            credentialPath(configDirectory)
        );
    }

    WindowsDpapiCredentialStore(
        final Path powershell,
        final Path credentialFile
    ) {
        this.powershell = powershell;
        this.credentialFile =
            credentialFile.toAbsolutePath().normalize();
    }

    public static boolean isAvailable() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("windows")
            && defaultPowerShell().isPresent();
    }

    static Path credentialPath(final Path configDirectory) {
        return configDirectory.toAbsolutePath().normalize()
            .resolve("mcai-companion")
            .resolve("credentials")
            .resolve("model-api-key.dpapi");
    }

    @Override
    public Optional<char[]> load() throws CredentialException {
        ensureAvailable();
        if (!Files.isRegularFile(credentialFile)) {
            return Optional.empty();
        }
        final byte[] cipher;
        try {
            final long size = Files.size(credentialFile);
            if (size <= 0 || size > MAXIMUM_BLOB_BYTES) {
                throw new CredentialException(
                    "Windows DPAPI credential blob has invalid size"
                );
            }
            cipher = Files.readAllBytes(credentialFile);
        } catch (IOException exception) {
            throw new CredentialException(
                "Windows DPAPI credential blob could not be read",
                exception
            );
        }
        final byte[] request = Base64.getEncoder().encode(cipher);
        Arrays.fill(cipher, (byte) 0);
        try {
            final CredentialCommand.Result result =
                runScript(UNPROTECT_SCRIPT, request);
            try {
                if (result.exitCode() != 0) {
                    throw new CredentialException(
                        "Windows DPAPI could not unlock "
                            + "the API credential"
                    );
                }
                final byte[] encoded = decodeBase64(result.output());
                try {
                    return Optional.of(
                        CredentialUtf8.decode(encoded)
                    );
                } finally {
                    Arrays.fill(encoded, (byte) 0);
                }
            } finally {
                Arrays.fill(result.output(), (byte) 0);
            }
        } finally {
            Arrays.fill(request, (byte) 0);
        }
    }

    @Override
    public void save(final char[] credential)
            throws CredentialException {
        ensureAvailable();
        final byte[] encoded = CredentialUtf8.encode(credential);
        final byte[] request = Base64.getEncoder().encode(encoded);
        Arrays.fill(encoded, (byte) 0);
        try {
            final CredentialCommand.Result result =
                runScript(PROTECT_SCRIPT, request);
            try {
                if (result.exitCode() != 0) {
                    throw new CredentialException(
                        "Windows DPAPI could not protect "
                            + "the API credential"
                    );
                }
                final byte[] cipher = decodeBase64(result.output());
                try {
                    writeAtomically(cipher);
                } finally {
                    Arrays.fill(cipher, (byte) 0);
                }
            } finally {
                Arrays.fill(result.output(), (byte) 0);
            }
        } finally {
            Arrays.fill(request, (byte) 0);
        }
    }

    @Override
    public void clear() throws CredentialException {
        try {
            Files.deleteIfExists(credentialFile);
        } catch (IOException exception) {
            throw new CredentialException(
                "Windows DPAPI credential blob could not be deleted",
                exception
            );
        }
    }

    @Override
    public boolean persistent() {
        return true;
    }

    private CredentialCommand.Result runScript(
        final String script,
        final byte[] standardInput
    ) throws CredentialException {
        final String encodedScript = Base64.getEncoder().encodeToString(
            script.getBytes(StandardCharsets.UTF_16LE)
        );
        return CredentialCommand.execute(
            powershell,
            standardInput,
            COMMAND_TIMEOUT,
            MAXIMUM_OUTPUT_BYTES,
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy", "Bypass",
            "-EncodedCommand", encodedScript
        );
    }

    private void writeAtomically(final byte[] cipher)
            throws CredentialException {
        Path temporary = null;
        try {
            Files.createDirectories(credentialFile.getParent());
            temporary = Files.createTempFile(
                credentialFile.getParent(),
                ".model-api-key-",
                ".tmp"
            );
            Files.write(temporary, cipher);
            try {
                Files.move(
                    temporary,
                    credentialFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                    temporary,
                    credentialFile,
                    StandardCopyOption.REPLACE_EXISTING
                );
            }
            temporary = null;
        } catch (IOException exception) {
            throw new CredentialException(
                "Windows DPAPI credential blob could not be saved",
                exception
            );
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // This temporary contains DPAPI ciphertext only.
                }
            }
        }
    }

    private void ensureAvailable() throws CredentialException {
        if (!Files.isExecutable(powershell)) {
            throw new CredentialException(
                "Windows DPAPI helper is unavailable"
            );
        }
    }

    private static Optional<Path> defaultPowerShell() {
        final String systemRoot = System.getenv("SystemRoot");
        if (systemRoot == null || systemRoot.isBlank()) {
            return Optional.empty();
        }
        final Path candidate = Path.of(
            systemRoot,
            "System32",
            "WindowsPowerShell",
            "v1.0",
            "powershell.exe"
        );
        return Files.isExecutable(candidate)
            ? Optional.of(candidate)
            : Optional.empty();
    }

    private static byte[] decodeBase64(final byte[] response)
            throws CredentialException {
        final byte[] exact =
            CredentialCommand.trimLineEnding(response);
        try {
            return Base64.getDecoder().decode(exact);
        } catch (IllegalArgumentException exception) {
            throw new CredentialException(
                "Platform credential helper returned invalid data",
                exception
            );
        } finally {
            Arrays.fill(exact, (byte) 0);
        }
    }
}
