package dev.mcai.companion.navigation;

/**
 * Provenance for one observed voxel's occupancy classification.
 *
 * <p>A single visual ray proves only that its own infinitesimal path was
 * clear; it does not prove that a player's full collision volume fits through
 * the voxel.  Safety-critical skills must therefore reject
 * {@link #SINGLE_RAY_CLEAR}.  {@link #MULTI_RAY_CLEAR} is emitted only after
 * distinct first-person rays traversed the same voxel in one semantic sample,
 * while {@link #BODY_OCCUPIED} is direct player self-state.</p>
 */
public enum OccupancyEvidence {
    UNKNOWN,
    SURFACE_HIT,
    BODY_CONTACT,
    SINGLE_RAY_CLEAR,
    MULTI_RAY_CLEAR,
    COLLISION_SHAPE_CLEAR,
    BODY_OCCUPIED;

    /**
     * Only the player's own occupied cell is a full-volume fact.  Multiple
     * visual rays remain useful navigation evidence, but never become a
     * safety-grade body-clearance proof by themselves.
     */
    public boolean isFullBodyFact() {
        return this == BODY_OCCUPIED;
    }
}
