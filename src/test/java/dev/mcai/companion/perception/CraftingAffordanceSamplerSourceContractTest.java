package dev.mcai.companion.perception;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class CraftingAffordanceSamplerSourceContractTest {
    @Test
    void liveSamplerIsBoundToOwnBookInventoryAndCurrentMenu() throws Exception {
        final String source = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/perception/"
                + "CraftingAffordanceSampler.java"
        ));

        assertTrue(source.contains("player.getRecipeBook()"));
        assertTrue(source.contains(".pack()"));
        assertTrue(source.contains(".known()"));
        assertTrue(source.contains(".contains(recipeKey)"));
        assertTrue(source.contains(
                "player.getInventory().fillStackedContents(available)"
        ));
        assertTrue(source.contains("available.canCraft(recipe, 1, null)"));
        assertTrue(source.contains(
                "instanceof AbstractCraftingMenu menu"
        ));
        assertTrue(source.contains("menu.getGridWidth()"));
        assertTrue(source.contains("menu.getGridHeight()"));
        assertTrue(source.contains("recipe.isSpecial()"));
    }
}
