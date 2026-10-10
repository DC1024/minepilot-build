package dev.mcai.companion.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class CraftingAffordanceSelectorTest {
    @Test
    void excludesLockedAndMaterialInsufficientRecipes() {
        final CraftingAffordance unlocked = recipe(
                "minecraft:oak_planks",
                1,
                1
        );
        final CraftingAffordance locked = recipe(
                "minecraft:crafting_table",
                2,
                2
        );
        final CraftingAffordance insufficient = recipe(
                "minecraft:stick",
                1,
                2
        );

        final CraftingAffordanceSnapshot result =
                CraftingAffordanceSelector.select(
                    2,
                    2,
                    List.of(
                        new CraftingAffordanceSelector.Candidate(
                            locked,
                            false,
                            true
                        ),
                        new CraftingAffordanceSelector.Candidate(
                            insufficient,
                            true,
                            false
                        ),
                        new CraftingAffordanceSelector.Candidate(
                            unlocked,
                            true,
                            true
                        )
                    )
                );

        assertEquals(List.of(unlocked), result.recipes());
        assertFalse(result.truncated());
    }

    @Test
    void distinguishesInventoryGridFromOpenCraftingTableGrid() {
        final CraftingAffordance inventoryRecipe = recipe(
                "minecraft:oak_planks",
                1,
                1
        );
        final CraftingAffordance tableRecipe = recipe(
                "minecraft:wooden_pickaxe",
                3,
                3
        );
        final List<CraftingAffordanceSelector.Candidate> candidates =
                List.of(
                    available(tableRecipe),
                    available(inventoryRecipe)
                );

        assertEquals(
                List.of(inventoryRecipe),
                CraftingAffordanceSelector.select(2, 2, candidates)
                        .recipes()
        );
        assertEquals(
                List.of(inventoryRecipe, tableRecipe),
                CraftingAffordanceSelector.select(3, 3, candidates)
                        .recipes()
        );
        assertTrue(tableRecipe.requiresCraftingTable());
        assertFalse(inventoryRecipe.requiresCraftingTable());
    }

    @Test
    void deterministicallySortsDeduplicatesAndCapsAtSixtyFour() {
        final List<CraftingAffordanceSelector.Candidate> candidates =
                new ArrayList<>();
        for (int index = 70; index >= 0; index--) {
            candidates.add(available(recipe(
                    "test:recipe_" + String.format("%03d", index),
                    1,
                    1
            )));
        }
        candidates.add(available(recipe("test:recipe_000", 1, 1)));

        final CraftingAffordanceSnapshot result =
                CraftingAffordanceSelector.select(
                        2,
                        2,
                        candidates
                );

        assertEquals(CraftingAffordanceSnapshot.MAX_RECIPES,
                result.recipes().size());
        assertEquals("test:recipe_000",
                result.recipes().getFirst().recipeId());
        assertEquals("test:recipe_063",
                result.recipes().getLast().recipeId());
        assertTrue(result.truncated());
    }

    private static CraftingAffordanceSelector.Candidate available(
            final CraftingAffordance recipe
    ) {
        return new CraftingAffordanceSelector.Candidate(
                recipe,
                true,
                true
        );
    }

    private static CraftingAffordance recipe(
            final String recipeId,
            final int width,
            final int height
    ) {
        return new CraftingAffordance(
                recipeId,
                "minecraft:oak_planks",
                4,
                width,
                height,
                width > 2 || height > 2
        );
    }
}
