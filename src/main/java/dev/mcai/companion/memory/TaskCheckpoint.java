package dev.mcai.companion.memory;

import java.time.Instant;
import java.util.UUID;

public record TaskCheckpoint(
    UUID taskId,
    long goalRevision,
    String skillName,
    String stateJson,
    Instant updatedAt
) {
    public TaskCheckpoint {
        if (taskId == null || skillName == null || stateJson == null || updatedAt == null) {
            throw new IllegalArgumentException("Checkpoint fields must not be null");
        }
        if (goalRevision < 0 || skillName.isBlank() || skillName.length() > 128 || stateJson.length() > 262_144) {
            throw new IllegalArgumentException("Checkpoint exceeds bounds");
        }
    }
}
