package dev.mcai.companion.integration.xaero;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Fail-closed sender authorization for shared waypoints.
 *
 * <p>Authorization is by stable UUID only: either an explicit allowlist entry
 * or a predicate supplied by the integrated-server adapter that recognizes
 * its singleplayer owner. Names, chat text, Xaero labels, and claimed
 * dimensions never grant authority.</p>
 */
public final class XaeroSenderAuthorizationPolicy {
    private final Set<UUID> explicitlyAllowed;
    private final Predicate<UUID> integratedSingleplayerOwner;

    public XaeroSenderAuthorizationPolicy(
            Set<UUID> explicitlyAllowed,
            Predicate<UUID> integratedSingleplayerOwner
    ) {
        this.explicitlyAllowed = Set.copyOf(
                Objects.requireNonNull(explicitlyAllowed, "explicitlyAllowed")
        );
        this.integratedSingleplayerOwner = Objects.requireNonNull(
                integratedSingleplayerOwner,
                "integratedSingleplayerOwner"
        );
    }

    public boolean isAuthorized(UUID senderId) {
        Objects.requireNonNull(senderId, "senderId");
        if (explicitlyAllowed.contains(senderId)) {
            return true;
        }
        try {
            return integratedSingleplayerOwner.test(senderId);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public Set<UUID> explicitlyAllowed() {
        return explicitlyAllowed;
    }
}
