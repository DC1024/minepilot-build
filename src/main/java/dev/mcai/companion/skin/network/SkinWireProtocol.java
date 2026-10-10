package dev.mcai.companion.skin.network;

import dev.mcai.companion.skin.SkinStore;
import dev.mcai.companion.skin.SkinWireSnapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SkinWireProtocol {
    public static final int CHUNK_BYTES = 32 * 1024;
    public static final int MAX_CHUNK_COUNT =
        Math.toIntExact(
            (SkinStore.MAX_FILE_BYTES + CHUNK_BYTES - 1L) / CHUNK_BYTES
        );

    private SkinWireProtocol() {
    }

    public static List<ClientboundSkinChunk> chunks(
        final SkinWireSnapshot snapshot
    ) {
        final byte[] allBytes = snapshot.pngBytes();
        final int count = expectedChunkCount(allBytes.length);
        final List<ClientboundSkinChunk> chunks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            final int start = index * CHUNK_BYTES;
            final int end = Math.min(start + CHUNK_BYTES, allBytes.length);
            chunks.add(new ClientboundSkinChunk(
                snapshot.companionId(),
                snapshot.spec().sha256(),
                snapshot.spec().armType(),
                allBytes.length,
                index,
                count,
                Arrays.copyOfRange(allBytes, start, end)
            ));
        }
        return List.copyOf(chunks);
    }

    public static int expectedChunkCount(final int totalLength) {
        if (totalLength <= 0 || totalLength > SkinStore.MAX_FILE_BYTES) {
            throw new IllegalArgumentException(
                "Skin transfer length is outside the accepted range"
            );
        }
        return (totalLength + CHUNK_BYTES - 1) / CHUNK_BYTES;
    }

    static void validateChunkShape(
        final int totalLength,
        final int chunkIndex,
        final int chunkCount,
        final int actualChunkLength
    ) {
        final int expectedCount = expectedChunkCount(totalLength);
        if (chunkCount != expectedCount || chunkCount > MAX_CHUNK_COUNT) {
            throw new IllegalArgumentException(
                "Skin transfer has an invalid chunk count"
            );
        }
        if (chunkIndex < 0 || chunkIndex >= chunkCount) {
            throw new IllegalArgumentException(
                "Skin transfer has an invalid chunk index"
            );
        }
        final int expectedLength = chunkIndex == chunkCount - 1
            ? totalLength - (chunkIndex * CHUNK_BYTES)
            : CHUNK_BYTES;
        if (actualChunkLength != expectedLength) {
            throw new IllegalArgumentException(
                "Skin transfer chunk has an invalid length"
            );
        }
    }
}
