package dev.mcai.companion.vision;

import java.util.Objects;
import java.util.UUID;

public record ClientboundVisionCaptureRequest(
        long requestId,
        UUID companionId
) {
    public ClientboundVisionCaptureRequest {
        if (requestId < 1) {
            throw new IllegalArgumentException(
                    "Vision request id must be positive"
            );
        }
        Objects.requireNonNull(companionId, "companionId");
    }
}
