package dev.mcai.companion.skills.interaction;

import dev.mcai.companion.navigation.GridPos;
import dev.mcai.companion.waypoint.DimensionRef;

/**
 * Server-authoritative policy checked immediately before an ordinary player
 * mining action is dispatched.
 *
 * <p>Planner schemas and skill preconditions reduce bad decisions, but they
 * are not an authorization boundary. Implementations identify blocks that
 * this companion must not dismantle even when a model returns an otherwise
 * valid mining request.</p>
 */
@FunctionalInterface
public interface BlockBreakProtection {
    boolean protects(DimensionRef dimension, GridPos position);

    static BlockBreakProtection none() {
        return (ignoredDimension, ignoredPosition) -> false;
    }
}
