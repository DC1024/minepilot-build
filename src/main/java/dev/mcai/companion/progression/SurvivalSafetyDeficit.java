package dev.mcai.companion.progression;

/**
 * Live, non-sticky risk signals derived only from the companion's current
 * health, hunger, and owned inventory.
 */
public enum SurvivalSafetyDeficit {
    LOW_HEALTH,
    LOW_HUNGER,
    FOOD_RESERVE_LOW,
    BUILDING_BLOCK_RESERVE_LOW,
    SHIELD_MISSING,
    WATER_BUCKET_MISSING,
    END_ARMOR_LOW
}
