package dev.mcai.companion.perception;

/**
 * Bounded support semantics for the exact block surface hit by a fair
 * first-person ray.
 *
 * <p>The value deliberately exposes no collision boxes or neighboring block
 * state.  It only answers whether the observed {@code UP} face is a full,
 * sturdy standing surface for an ordinary player.</p>
 */
public enum TopSupportAffordance {
    UNKNOWN,
    NON_STURDY_OR_PARTIAL,
    WALKABLE_FULL_FOOTPRINT_TOP,
    STURDY_FULL_TOP;

    public boolean safelySupportsStanding() {
        return this == STURDY_FULL_TOP
                || this == WALKABLE_FULL_FOOTPRINT_TOP;
    }
}
