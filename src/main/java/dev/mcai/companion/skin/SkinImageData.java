package dev.mcai.companion.skin;

import java.util.Objects;

/**
 * Validated content-addressed skin data. Byte arrays are copied at both
 * boundaries so packet and cache users cannot mutate trusted content.
 */
public record SkinImageData(
    SkinSpec spec,
    byte[] pngBytes
) {
    public SkinImageData {
        spec = Objects.requireNonNull(spec, "spec");
        pngBytes = Objects.requireNonNull(pngBytes, "pngBytes").clone();
        if (pngBytes.length <= 0 || pngBytes.length > SkinStore.MAX_FILE_BYTES) {
            throw new IllegalArgumentException(
                "Skin PNG must be between 1 byte and 1 MiB"
            );
        }
    }

    @Override
    public byte[] pngBytes() {
        return pngBytes.clone();
    }
}
