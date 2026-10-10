package dev.mcai.companion.action;

import java.util.Objects;
import java.util.UUID;

/**
 * Entity UUID plus a point relative to the entity position, as used by the
 * vanilla interact-at packet.
 */
public record EntityInteractionPoint(UUID entityId, ActionVec3 relativePoint) {
    public EntityInteractionPoint {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(relativePoint, "relativePoint");
    }
}
