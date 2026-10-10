package dev.mcai.companion.perception;

/**
 * Declares exactly how an observation became available to the companion.
 */
public enum PerceptionProvenance {
    SELF_PLAYER_STATE,
    OWN_INVENTORY,
    OWN_RECIPE_BOOK,
    OWN_STATUS_EFFECT,
    OPEN_MENU_CONTENTS,
    ENTITY_DISTANCE_FOV_AND_BLOCK_CLIP,
    BLOCK_SURFACE_RAY_CLIP,
    BLOCK_TRANSLUCENT_RAY_SAMPLE,
    BLOCK_RAY_CLEAR_MISS,
    BLOCK_RAY_CLEAR_BEFORE_HIT,
    PHYSICAL_CONTACT,
    RECENT_DAMAGE_EVENT,
    AUDIBLE_HOSTILE_SOUND,
    AUTHORIZED_PLAYER_WARNING,
    PROXIMITY_THREAT,
    BODY_HAZARD
}
