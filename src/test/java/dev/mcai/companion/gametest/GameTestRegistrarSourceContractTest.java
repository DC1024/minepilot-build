package dev.mcai.companion.gametest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class GameTestRegistrarSourceContractTest {
    @Test
    void registersBothEmbodimentAndNaturalRecipeFixtures() throws Exception {
        final String source = Files.readString(Path.of(
            "src/main/java/dev/mcai/companion/gametest/"
                + "GameTestRegistrar.java"
        ));

        assertTrue(source.contains(
            "\"dev.mcai.companion.embodiment.EmbodimentGameTests\""
        ));
        assertTrue(source.contains(
            "\"dev.mcai.companion.skills.inventory.InventoryGameTests\""
        ));
        assertTrue(source.contains(
            "\"dev.mcai.companion.skills.portal.PortalCastGameTests\""
        ));
        assertTrue(source.contains(
            "ForgeGameTestHooks.gatherTests(fixture, null)"
        ));
        assertTrue(source.contains("gathered.putIfAbsent"));
    }
}
