package dev.mcai.companion.perception;

import java.util.List;
import java.util.Objects;

/**
 * Bounded view of recipes available in the companion's current vanilla
 * crafting grid.
 */
public record CraftingAffordanceSnapshot(
        int currentGridWidth,
        int currentGridHeight,
        List<CraftingAffordance> recipes,
        boolean truncated
) {
    public static final int MAX_RECIPES = 64;

    public CraftingAffordanceSnapshot {
        if (currentGridWidth < 1 || currentGridWidth > 3
                || currentGridHeight < 1 || currentGridHeight > 3) {
            throw new IllegalArgumentException(
                    "Current crafting grid must be between 1x1 and 3x3"
            );
        }
        recipes = List.copyOf(Objects.requireNonNull(recipes, "recipes"));
        if (recipes.size() > MAX_RECIPES) {
            throw new IllegalArgumentException(
                    "Too many crafting affordances"
            );
        }
        String previousId = null;
        for (CraftingAffordance recipe : recipes) {
            Objects.requireNonNull(recipe, "recipe");
            if (recipe.gridWidth() > currentGridWidth
                    || recipe.gridHeight() > currentGridHeight) {
                throw new IllegalArgumentException(
                        "Recipe does not fit the current crafting grid"
                );
            }
            if (previousId != null
                    && previousId.compareTo(recipe.recipeId()) >= 0) {
                throw new IllegalArgumentException(
                        "Crafting recipes must be uniquely sorted by id"
                );
            }
            previousId = recipe.recipeId();
        }
    }
}
