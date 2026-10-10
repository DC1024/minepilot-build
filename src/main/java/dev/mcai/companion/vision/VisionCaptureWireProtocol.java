package dev.mcai.companion.vision;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** Bounded chunking below Minecraft's 32,767-byte serverbound payload cap. */
public final class VisionCaptureWireProtocol {
    public static final int CHUNK_BYTES = 24 * 1_024;
    public static final int MAX_CHUNK_COUNT = Math.toIntExact(
            (VisionCaptureNetwork.MAX_PNG_BYTES + CHUNK_BYTES - 1L)
                    / CHUNK_BYTES
    );

    private VisionCaptureWireProtocol() {
    }

    public static List<ServerboundVisionCaptureChunk> chunks(
            final ServerboundVisionCaptureResult result
    ) {
        final byte[] png = result.pngUnsafe();
        if (!"ok".equals(result.code())) {
            return List.of(new ServerboundVisionCaptureChunk(
                    result.requestId(),
                    result.companionId(),
                    result.code(),
                    0,
                    "",
                    0,
                    1,
                    new byte[0]
            ));
        }
        if (png.length < 24
                || png.length > VisionCaptureNetwork.MAX_PNG_BYTES) {
            throw new IllegalArgumentException(
                    "Vision PNG length is outside the accepted range"
            );
        }
        final int count = expectedChunkCount(png.length);
        final String digest = sha256(png);
        final List<ServerboundVisionCaptureChunk> chunks =
                new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            final int start = index * CHUNK_BYTES;
            final int end = Math.min(start + CHUNK_BYTES, png.length);
            chunks.add(new ServerboundVisionCaptureChunk(
                    result.requestId(),
                    result.companionId(),
                    result.code(),
                    png.length,
                    digest,
                    index,
                    count,
                    Arrays.copyOfRange(png, start, end)
            ));
        }
        return List.copyOf(chunks);
    }

    public static int expectedChunkCount(final int totalLength) {
        if (totalLength < 24
                || totalLength > VisionCaptureNetwork.MAX_PNG_BYTES) {
            throw new IllegalArgumentException(
                    "Vision transfer length is outside the accepted range"
            );
        }
        return (totalLength + CHUNK_BYTES - 1) / CHUNK_BYTES;
    }

    static void validateChunkShape(
            final ServerboundVisionCaptureChunk chunk
    ) {
        if (!chunk.code().matches("[a-z0-9_]{1,64}")) {
            throw new IllegalArgumentException(
                    "Vision result code is invalid"
            );
        }
        if (!"ok".equals(chunk.code())) {
            if (chunk.totalLength() != 0
                    || !chunk.sha256().isEmpty()
                    || chunk.chunkIndex() != 0
                    || chunk.chunkCount() != 1
                    || chunk.bytesUnsafe().length != 0) {
                throw new IllegalArgumentException(
                        "Failed vision result must have an empty payload"
                );
            }
            return;
        }
        final int expectedCount = expectedChunkCount(chunk.totalLength());
        if (chunk.chunkCount() != expectedCount
                || chunk.chunkCount() > MAX_CHUNK_COUNT) {
            throw new IllegalArgumentException(
                    "Vision transfer has an invalid chunk count"
            );
        }
        if (chunk.chunkIndex() < 0
                || chunk.chunkIndex() >= chunk.chunkCount()) {
            throw new IllegalArgumentException(
                    "Vision transfer has an invalid chunk index"
            );
        }
        if (!chunk.sha256().matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Vision transfer digest is invalid"
            );
        }
        final int expectedLength = chunk.chunkIndex()
                == chunk.chunkCount() - 1
                        ? chunk.totalLength()
                                - chunk.chunkIndex() * CHUNK_BYTES
                        : CHUNK_BYTES;
        if (chunk.bytesUnsafe().length != expectedLength) {
            throw new IllegalArgumentException(
                    "Vision transfer chunk has an invalid length"
            );
        }
    }

    static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
