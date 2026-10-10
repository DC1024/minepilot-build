package dev.mcai.companion.skills.interaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class InteractionSourceContractTest {
    @Test
    void productionBridgeUsesFairActuatorAndNoDirectMutation()
            throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/skills/interaction/"
                        + "ServerOwnedInteractionSkillActuator.java"
        ));

        assertTrue(source.contains("FairPlayerActuator"));
        assertFalse(source.contains(".setPos("));
        assertFalse(source.contains(".teleport"));
        assertFalse(source.contains(".setBlock("));
        assertFalse(source.contains(".removeBlock("));
        assertFalse(source.contains(".destroyBlock("));
        assertFalse(source.contains(".setItem("));
        assertFalse(source.contains("ServerPlayerGameMode"));
        assertTrue(source.contains(
                "blockBreakProtection.protects("
        ));
        assertTrue(
                source.indexOf("blockBreakProtection.protects(")
                        < source.indexOf(
                                "active.actuator().beginMining(target)"
                        )
        );
    }
}
