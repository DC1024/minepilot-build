package dev.mcai.companion.skin;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Complete validated-at-source skin snapshot ready for chunked transport.
 */
public record SkinWireSnapshot(
    UUID companionId,
    SkinSpec spec,
    byte[] pngBytes
) {
    public SkinWireSnapshot {
        companionId = Objects.requireNonNull(companionId, "companionId");
        spec = Objects.requireNonNull(spec, "spec");
        pngBytes = Objects.requireNonNull(pngBytes, "pngBytes").clone();
        if (pngBytes.length <= 0 || pngBytes.length > SkinStore.MAX_FILE_BYTES) {
            throw new IllegalArgumentException(
                "Skin PNG must be between 1 byte and 1 MiB"
            );
        }
        if (!spec.sha256().equals(sha256(pngBytes))) {
            throw new IllegalArgumentException(
                "Skin bytes do not match the declared SHA-256"
            );
        }
    }

    @Override
    public byte[] pngBytes() {
        return pngBytes.clone();
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                "JVM does not provide SHA-256",
                exception
            );
        }
    }
}
