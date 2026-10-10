package dev.mcai.companion.model;

import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/** One bounded, immutable first-person PNG supplied to a planner request. */
public final class ModelImageInput {
    public static final int MAX_PNG_BYTES = 2_500_000;

    private final byte[] png;
    private final Detail detail;

    public ModelImageInput(final byte[] png, final Detail detail) {
        Objects.requireNonNull(png, "png");
        if (png.length < 24
                || png.length > MAX_PNG_BYTES
                || (png[0] & 0xff) != 0x89
                || png[1] != 0x50
                || png[2] != 0x4e
                || png[3] != 0x47
                || png[4] != 0x0d
                || png[5] != 0x0a
                || png[6] != 0x1a
                || png[7] != 0x0a) {
            throw new IllegalArgumentException(
                    "Model image must be a bounded PNG"
            );
        }
        this.png = png.clone();
        this.detail = Objects.requireNonNull(detail, "detail");
    }

    public String dataUrl() {
        return "data:image/png;base64,"
                + Base64.getEncoder().encodeToString(png);
    }

    public int byteLength() {
        return png.length;
    }

    public Detail detail() {
        return detail;
    }

    public void destroy() {
        Arrays.fill(png, (byte) 0);
    }

    public enum Detail {
        LOW("low"),
        HIGH("high");

        private final String wireName;

        Detail(final String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }
}
