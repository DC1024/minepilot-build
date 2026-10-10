package dev.mcai.companion.vision;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/** Server-thread-only bounded reassembly for a single pending frame. */
final class VisionCaptureChunkAssembler {
    private final long requestId;
    private final UUID companionId;
    private final String code;
    private final int totalLength;
    private final String sha256;
    private final int chunkCount;
    private final byte[] bytes;
    private final boolean[] received;
    private int receivedCount;

    VisionCaptureChunkAssembler(
            final ServerboundVisionCaptureChunk first
    ) {
        requestId = first.requestId();
        companionId = first.companionId();
        code = first.code();
        totalLength = first.totalLength();
        sha256 = first.sha256();
        chunkCount = first.chunkCount();
        bytes = new byte[totalLength];
        received = new boolean[chunkCount];
    }

    Optional<ServerboundVisionCaptureResult> accept(
            final ServerboundVisionCaptureChunk chunk
    ) {
        if (chunk.requestId() != requestId
                || !chunk.companionId().equals(companionId)
                || !chunk.code().equals(code)
                || chunk.totalLength() != totalLength
                || !chunk.sha256().equals(sha256)
                || chunk.chunkCount() != chunkCount) {
            throw new IllegalArgumentException(
                    "Vision transfer metadata changed between chunks"
            );
        }
        final int index = chunk.chunkIndex();
        if (!received[index]) {
            final byte[] fragment = chunk.bytesUnsafe();
            System.arraycopy(
                    fragment,
                    0,
                    bytes,
                    index * VisionCaptureWireProtocol.CHUNK_BYTES,
                    fragment.length
            );
            received[index] = true;
            receivedCount++;
        }
        if (receivedCount != chunkCount) {
            return Optional.empty();
        }
        if (totalLength > 0
                && !VisionCaptureWireProtocol.sha256(bytes).equals(sha256)) {
            throw new IllegalArgumentException(
                    "Vision transfer digest did not match"
            );
        }
        return Optional.of(new ServerboundVisionCaptureResult(
                requestId,
                companionId,
                code,
                bytes
        ));
    }

    void destroy() {
        Arrays.fill(bytes, (byte) 0);
        Arrays.fill(received, false);
        receivedCount = 0;
    }
}
