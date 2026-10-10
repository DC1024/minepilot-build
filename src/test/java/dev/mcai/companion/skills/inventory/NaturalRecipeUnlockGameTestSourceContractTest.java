package dev.mcai.companion.skills.inventory;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class NaturalRecipeUnlockGameTestSourceContractTest {
    @Test
    void timedGateUsesVanillaPickupAndNeverInjectsAnUnlock() throws Exception {
        final String source = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/skills/inventory/"
                + "InventoryGameTests.java"
        ));
        final int start = source.indexOf(
                "public static void naturalRecipeUnlockAfterLogPickup"
        );
        final int end = source.indexOf(
                "public static void vanillaInventoryTransactions",
                start
        );
        final String method = source.substring(start, end);

        assertTrue(method.contains("droppedLog.playerTouch(player)"));
        assertTrue(method.contains(
                "player.inventoryMenu.broadcastChanges()"
        ));
        assertTrue(method.contains(
                "player.getRecipeBook().contains(planksKey)"
        ));
        assertTrue(method.contains(
                "new CraftingAffordanceSampler()"
        ));
        assertFalse(method.contains("recipeBook.add"));
        assertFalse(method.contains("getRecipeBook().add"));
        assertFalse(method.contains("awardRecipes"));
        assertFalse(method.contains("unlock("));
    }

    @Test
    void naturalGateIsRegisteredAsItsOwnDataDrivenForgeTest()
            throws Exception {
        final String source = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/skills/inventory/"
                + "InventoryGameTests.java"
        ));
        final String instance = Files.readString(Path.of(
            "src/main/resources/data/mcai_companion/test_instance/"
                + "natural_recipe_unlock_after_log_pickup.json"
        ));
        final String registrar = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/gametest/"
                + "GameTestRegistrar.java"
        ));

        assertTrue(source.contains(
            "name = \"natural_recipe_unlock_after_log_pickup\""
        ));
        assertTrue(instance.contains(
            "\"function\": "
                + "\"mcai_companion:natural_recipe_unlock_after_log_pickup\""
        ));
        assertTrue(registrar.contains(
            "\"dev.mcai.companion.skills.inventory.InventoryGameTests\""
        ));
    }
}
