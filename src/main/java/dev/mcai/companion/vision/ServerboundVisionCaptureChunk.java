package dev.mcai.companion.vision;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** One bounded serverbound fragment of an authenticated vision result. */
public final class ServerboundVisionCaptureChunk {
    private final long requestId;
    private final UUID companionId;
    private final String code;
    private final int totalLength;
    private final String sha256;
    private final int chunkIndex;
    private final int chunkCount;
    private final byte[] bytes;

    public ServerboundVisionCaptureChunk(
            final long requestId,
            final UUID companionId,
            final String code,
            final int totalLength,
            final String sha256,
            final int chunkIndex,
            final int chunkCount,
            final byte[] bytes
    ) {
        if (requestId < 1) {
            throw new IllegalArgumentException(
                    "Vision request id must be positive"
            );
        }
        this.requestId = requestId;
        this.companionId = Objects.requireNonNull(
                companionId,
                "companionId"
        );
        this.code = Objects.requireNonNull(code, "code");
        this.totalLength = totalLength;
        this.sha256 = Objects.requireNonNull(sha256, "sha256");
        this.chunkIndex = chunkIndex;
        this.chunkCount = chunkCount;
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
        VisionCaptureWireProtocol.validateChunkShape(this);
    }

    public long requestId() {
        return requestId;
    }

    public UUID companionId() {
        return companionId;
    }

    public String code() {
        return code;
    }

    public int totalLength() {
        return totalLength;
    }

    public String sha256() {
        return sha256;
    }

    public int chunkIndex() {
        return chunkIndex;
    }

    public int chunkCount() {
        return chunkCount;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    byte[] bytesUnsafe() {
        return bytes;
    }

    public void destroy() {
        Arrays.fill(bytes, (byte) 0);
    }
}
