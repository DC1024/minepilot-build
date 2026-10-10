package dev.mcai.companion.perception;

/**
 * Coarse collision fact for one block whose visible shape was ray hit.
 *
 * <p>No collision boxes cross the perception boundary.  {@link #EMPTY}
 * means the authoritative block state had an empty vanilla collision shape
 * at the observed position; partial and full collision remain deliberately
 * merged so local navigation cannot infer hidden geometry.</p>
 */
public enum CollisionAffordance {
    UNKNOWN,
    EMPTY,
    OBSTRUCTED_OR_PARTIAL
}
