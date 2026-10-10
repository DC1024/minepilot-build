package dev.mcai.companion.memory;

import java.time.Instant;

public record MemoryEvent(
    Instant occurredAt,
    String type,
    String source,
    String payloadJson,
    long worldRevision,
    long goalRevision
) {
    public MemoryEvent {
        if (occurredAt == null || type == null || source == null || payloadJson == null) {
            throw new IllegalArgumentException("Memory event fields must not be null");
        }
        if (type.isBlank() || type.length() > 128 || source.length() > 128 || payloadJson.length() > 65_536) {
            throw new IllegalArgumentException("Memory event exceeds bounds");
        }
        if (worldRevision < 0 || goalRevision < 0) {
            throw new IllegalArgumentException("Revisions must be non-negative");
        }
    }
}
