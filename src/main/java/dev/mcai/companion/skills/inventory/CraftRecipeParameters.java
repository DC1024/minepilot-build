package dev.mcai.companion.skills.inventory;

import java.util.Objects;

/**
 * {@code crafts} counts recipe executions, not the number of output items.
 */
public record CraftRecipeParameters(String recipeId, int crafts) {
    public CraftRecipeParameters {
        recipeId = Objects.requireNonNull(recipeId, "recipeId");
        if (crafts < 1 || crafts > 64) {
            throw new IllegalArgumentException("crafts must be between 1 and 64");
        }
    }
}
