package dev.mcai.companion.skills.portal;

@FunctionalInterface
public interface PortalTraversalObserver {
    PortalTraversalObserver NOOP = result -> {
    };

    void onTraversal(PortalTraversalResult result);
}
