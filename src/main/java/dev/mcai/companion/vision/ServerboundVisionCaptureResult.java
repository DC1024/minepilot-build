package dev.mcai.companion.vision;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public final class ServerboundVisionCaptureResult {
    private final long requestId;
    private final UUID companionId;
    private final String code;
    private final byte[] png;

    public ServerboundVisionCaptureResult(
            final long requestId,
            final UUID companionId,
            final String code,
            final byte[] png
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
        this.png = Objects.requireNonNull(png, "png").clone();
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

    public byte[] png() {
        return png.clone();
    }

    byte[] pngUnsafe() {
        return png;
    }

    public void destroy() {
        Arrays.fill(png, (byte) 0);
    }
}
