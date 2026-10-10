package dev.mcai.companion.skin.network;

import java.util.Objects;
import java.util.UUID;

/**
 * Explicitly removes any custom texture so UUID-stable vanilla fallback is
 * immediate rather than waiting for disconnect.
 */
public record ClientboundSkinClear(UUID companionId) {
    public ClientboundSkinClear {
        companionId = Objects.requireNonNull(companionId, "companionId");
    }
}
