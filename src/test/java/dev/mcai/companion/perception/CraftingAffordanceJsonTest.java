package dev.mcai.companion.perception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.mcai.companion.model.PlannerInput;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class CraftingAffordanceJsonTest {
    @Test
    void usesJsonEncodingAndRemainsInsideSemanticCharacterBoundary() {
        final String unusualRecipeId =
                "test:quote_\"_slash_\\\\_recipe";
        final CraftingAffordance recipe = new CraftingAffordance(
                unusualRecipeId,
                "test:output_\"_item",
                7,
                2,
                2,
                false
        );
        final SemanticObservation observation = observation(
                new CraftingAffordanceSnapshot(
                        2,
                        2,
                        List.of(recipe),
                        false
                )
        );

        final String encoded =
                new SemanticObservationJsonCodec().encode(observation);
        final var parsed = JsonParser.parseString(encoded)
                .getAsJsonObject()
                .getAsJsonObject("craftingAffordances");
        final var encodedRecipe = parsed.getAsJsonArray("recipes")
                .get(0)
                .getAsJsonObject();

        assertEquals(unusualRecipeId,
                encodedRecipe.get("recipeId").getAsString());
        assertEquals("test:output_\"_item",
                encodedRecipe.get("outputItemId").getAsString());
        assertTrue(
                encoded.contains("\\\"")
                        && encoded.contains("\\\\\\\\"),
                "Affordance identifiers were not JSON escaped"
        );
        assertTrue(encoded.length()
                < SemanticObservationJsonCodec.MAX_JSON_CHARACTERS);
    }

    @Test
    void sixtyFourTypicalRecipesFitThePlannerObservationBoundary() {
        final List<CraftingAffordance> recipes = new ArrayList<>();
        for (int index = 0;
                index < CraftingAffordanceSnapshot.MAX_RECIPES;
                index++) {
            recipes.add(new CraftingAffordance(
                    "minecraft:test_recipe_"
                        + String.format("%03d", index),
                    "minecraft:test_output",
                    1,
                    2,
                    2,
                    false
            ));
        }
        final String encoded = new SemanticObservationJsonCodec().encode(
                observation(new CraftingAffordanceSnapshot(
                        2,
                        2,
                        recipes,
                        true
                ))
        );

        assertTrue(
                encoded.length()
                    <= PlannerInput.MAX_OBSERVATION_JSON_CHARACTERS,
                () -> "Bounded affordance observation was "
                    + encoded.length() + " characters"
        );
    }

    private static SemanticObservation observation(
            final CraftingAffordanceSnapshot snapshot
    ) {
        final PerceptionBudget budget = PerceptionBudget.defaults();
        return new SemanticObservation(
                1,
                new BodySnapshot(
                    UUID.fromString(
                        "00000000-0000-0000-0000-000000000001"
                    ),
                    "minecraft:overworld",
                    1,
                    new PerceptionVec3(0.0, 64.0, 0.0),
                    new PerceptionVec3(0.0, 65.62, 0.0),
                    new PerceptionVec3(0.0, 0.0, 1.0),
                    20.0F,
                    20.0F,
                    0.0F,
                    20,
                    5.0F,
                    300,
                    300,
                    true,
                    false,
                    false,
                    0.0,
                    HeldItemSummary.empty(),
                    HeldItemSummary.empty(),
                    List.of(new InventoryItemSummary(
                            "minecraft:oak_log",
                            1
                    )),
                    List.of(),
                    EnumSet.of(
                        PerceptionProvenance.SELF_PLAYER_STATE,
                        PerceptionProvenance.OWN_INVENTORY,
                        PerceptionProvenance.OWN_STATUS_EFFECT
                    )
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                Optional.empty(),
                Optional.of(snapshot),
                budget,
                new ObservationBudgetUsage(
                    0, 0, 0, 0, 0, 0, 0,
                    false, false, false, false
                ),
                EnumSet.of(
                    PerceptionProvenance.SELF_PLAYER_STATE,
                    PerceptionProvenance.OWN_INVENTORY,
                    PerceptionProvenance.OWN_RECIPE_BOOK
                )
        );
    }
}
