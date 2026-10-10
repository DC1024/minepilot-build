package dev.mcai.companion.perception;

/**
 * One exact, model-copyable recipe-book action that the companion can
 * currently execute through {@code craft_recipe}.
 */
public record CraftingAffordance(
        String recipeId,
        String outputItemId,
        int outputCount,
        int gridWidth,
        int gridHeight,
        boolean requiresCraftingTable
) {
    public CraftingAffordance {
        recipeId = PerceptionValidation.identifier(recipeId, "recipeId");
        outputItemId = PerceptionValidation.identifier(
                outputItemId,
                "outputItemId"
        );
        if (outputCount < 1) {
            throw new IllegalArgumentException(
                    "Crafting output count must be positive"
            );
        }
        if (gridWidth < 1 || gridWidth > 3
                || gridHeight < 1 || gridHeight > 3) {
            throw new IllegalArgumentException(
                    "Crafting recipe grid must be between 1x1 and 3x3"
            );
        }
        if (requiresCraftingTable
                != (gridWidth > 2 || gridHeight > 2)) {
            throw new IllegalArgumentException(
                    "Crafting-table requirement does not match recipe grid"
            );
        }
    }
}
