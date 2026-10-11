package dev.mcai.companion.skills.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mcai.companion.model.SkillArgument;
import java.util.List;
import org.junit.jupiter.api.Test;

final class InventorySkillParametersTest {
    @Test
    void equipRequiresCanonicalItemAndKnownEquipmentSlot() {
        final EquipItemParameters parsed =
                InventorySkillParameters.parseEquip(List.of(
                        new SkillArgument("itemId", "diamond_pickaxe"),
                        new SkillArgument("slot", "mainhand")
                )).value().orElseThrow();

        assertEquals("minecraft:diamond_pickaxe", parsed.itemId());
        assertEquals(EquipmentTarget.MAINHAND, parsed.slot());
        assertFalse(InventorySkillParameters.parseEquip(List.of(
                new SkillArgument("itemId", "minecraft:iron_helmet"),
                new SkillArgument("slot", "body")
        )).value().isPresent());
        assertFalse(InventorySkillParameters.parseEquip(List.of(
                new SkillArgument("itemId", "minecraft:iron_helmet"),
                new SkillArgument("slot", "head"),
                new SkillArgument("extra", "ignored")
        )).value().isPresent());
    }

    @Test
    void dropAndCraftCountsAreStrictBoundedIntegers() {
        assertTrue(InventorySkillParameters.parseDrop(List.of(
                new SkillArgument("itemId", "minecraft:cobblestone"),
                new SkillArgument("count", "64")
        )).value().isPresent());
        assertFalse(InventorySkillParameters.parseDrop(List.of(
                new SkillArgument("itemId", "minecraft:cobblestone"),
                new SkillArgument("count", "0")
        )).value().isPresent());
        assertFalse(InventorySkillParameters.parseDrop(List.of(
                new SkillArgument("itemId", "minecraft:cobblestone"),
                new SkillArgument("count", "1.0")
        )).value().isPresent());

        final CraftRecipeParameters parsed =
                InventorySkillParameters.parseCraft(List.of(
                        new SkillArgument(
                                "recipeId",
                                "minecraft:oak_planks"
                        ),
                        new SkillArgument("crafts", "2")
                )).value().orElseThrow();
        assertEquals("minecraft:oak_planks", parsed.recipeId());
        assertEquals(2, parsed.crafts());
        assertFalse(InventorySkillParameters.parseCraft(List.of(
                new SkillArgument("recipeId", "bad id"),
                new SkillArgument("crafts", "1")
        )).value().isPresent());
    }

    @Test
    void craftAcceptsAnOmittedOrAliasedCountAndStillFailsClosed() {
        assertEquals(
                1,
                InventorySkillParameters.parseCraft(List.of(
                        new SkillArgument(
                                "recipeId",
                                "minecraft:dark_oak_planks"
                        )
                )).value().orElseThrow().crafts(),
                "an omitted count means craft once"
        );
        assertEquals(
                4,
                InventorySkillParameters.parseCraft(List.of(
                        new SkillArgument(
                                "recipeId",
                                "minecraft:dark_oak_planks"
                        ),
                        new SkillArgument("count", "4")
                )).value().orElseThrow().crafts(),
                "providers routinely name the count 'count'"
        );
        assertFalse(InventorySkillParameters.parseCraft(List.of(
                new SkillArgument("recipeId", "minecraft:oak_planks"),
                new SkillArgument("targetBlockType", "minecraft:oak_log")
        )).value().isPresent(), "unknown fields still fail closed");
        assertFalse(InventorySkillParameters.parseCraft(List.of())
                .value().isPresent(), "recipeId is still required");
    }

    @Test
    void duplicateArgumentNamesAreRejected() {
        assertFalse(InventorySkillParameters.parseCraft(List.of(
                new SkillArgument("recipeId", "minecraft:stick"),
                new SkillArgument("recipeId", "minecraft:oak_planks")
        )).value().isPresent());
    }
}
