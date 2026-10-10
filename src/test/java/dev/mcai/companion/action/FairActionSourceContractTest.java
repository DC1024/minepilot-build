package dev.mcai.companion.action;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Makes the most important no-cheat boundary visible in ordinary CI without
 * requiring a running Minecraft server.
 */
final class FairActionSourceContractTest {
    @Test
    void actuatorContainsNoDirectWorldInventoryOrPositionMutation()
            throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/action/FairPlayerActuator.java"
        ));

        assertFalse(source.contains(".setPos("));
        assertFalse(source.contains(".absSnapTo("));
        assertFalse(source.contains(".teleport"));
        assertFalse(source.contains(".setBlock("));
        assertFalse(source.contains(".removeBlock("));
        assertFalse(source.contains(".destroyBlock("));
        assertFalse(source.contains(".setItem("));
        assertFalse(source.contains("CompoundTag"));
        assertTrue(source.contains(
                "picked instanceof EnderDragonPart part"
        ));
        assertTrue(source.contains(
                "part.parentMob == dragon"
        ));
        assertTrue(source.contains(
                "new ServerboundAttackPacket(target.getId())"
        ));
    }
}
