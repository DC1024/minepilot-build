package dev.mcai.companion.vision;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import dev.mcai.companion.model.ObservationKind;

public final class VisionCaptureSnapshot {
    private final long requestId;
    private final UUID companionId;
    private final UUID rendererId;
    private final Instant capturedAt;
    private final int width;
    private final int height;
    private final ObservationKind observationKind;
    private final byte[] png;

    public VisionCaptureSnapshot(
            final long requestId,
            final UUID companionId,
            final UUID rendererId,
            final Instant capturedAt,
            final int width,
            final int height,
            final ObservationKind observationKind,
            final byte[] png
    ) {
        if (requestId < 1 || width < 1 || height < 1) {
            throw new IllegalArgumentException(
                    "Vision snapshot metadata is invalid"
            );
        }
        this.requestId = requestId;
        this.companionId = Objects.requireNonNull(
                companionId,
                "companionId"
        );
        this.rendererId = Objects.requireNonNull(
                rendererId,
                "rendererId"
        );
        this.capturedAt = Objects.requireNonNull(
                capturedAt,
                "capturedAt"
        );
        this.width = width;
        this.height = height;
        this.observationKind = Objects.requireNonNull(
                observationKind,
                "observationKind"
        );
        this.png = Objects.requireNonNull(png, "png").clone();
    }

    public long requestId() {
        return requestId;
    }

    public UUID companionId() {
        return companionId;
    }

    public UUID rendererId() {
        return rendererId;
    }

    public Instant capturedAt() {
        return capturedAt;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public ObservationKind observationKind() {
        return observationKind;
    }

    public byte[] png() {
        return png.clone();
    }

    public void destroy() {
        Arrays.fill(png, (byte) 0);
    }
}
