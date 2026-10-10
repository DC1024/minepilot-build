package dev.mcai.companion.skill;

import java.util.Objects;

/**
 * A small, versioned, opaque recovery snapshot produced at a safe boundary.
 *
 * <p>The payload must contain only the state needed to resume the atomic
 * skill. It must never contain credentials or raw untrusted chat.</p>
 */
public record SkillCheckpoint(int formatVersion, String payload) {
    public static final int MAX_PAYLOAD_CHARACTERS = 32_768;

    public SkillCheckpoint {
        if (formatVersion < 1) {
            throw new IllegalArgumentException("formatVersion must be positive");
        }
        payload = Objects.requireNonNull(payload, "payload");
        if (payload.length() > MAX_PAYLOAD_CHARACTERS || payload.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Checkpoint payload is invalid");
        }
    }

    public static SkillCheckpoint empty() {
        return new SkillCheckpoint(1, "");
    }
}
