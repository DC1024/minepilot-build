package dev.mcai.companion.skills.portal;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal non-persistent hand-off for a future verified portal graph.
 */
public final class PortalTraversalBuffer
        implements PortalTraversalObserver {
    private final AtomicReference<PortalTraversalResult> latest =
            new AtomicReference<>();

    @Override
    public void onTraversal(PortalTraversalResult result) {
        latest.set(result);
    }

    public Optional<PortalTraversalResult> latest() {
        return Optional.ofNullable(latest.get());
    }

    public Optional<PortalTraversalResult> drain() {
        return Optional.ofNullable(latest.getAndSet(null));
    }
}
