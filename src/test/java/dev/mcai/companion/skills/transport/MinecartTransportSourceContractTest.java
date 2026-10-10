package dev.mcai.companion.skills.transport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class MinecartTransportSourceContractTest {
    @Test
    void productionActuatorSharesTheLeasedVanillaInputPath()
            throws IOException {
        final String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/skills/transport/"
                        + "ServerMinecartSkillActuator.java"
        ));

        assertTrue(source.contains("CoreSkillActuator"));
        assertTrue(source.contains("new LookIntent("));
        assertTrue(source.contains("new MovementIntent("));
        assertFalse(source.contains("ServerboundPlayerInputPacket"));
        assertFalse(source.contains("ServerboundMovePlayerPacket"));
        assertFalse(source.contains(".setPos("));
        assertFalse(source.contains(".setDeltaMovement("));
        assertFalse(source.contains(".teleport"));
    }

    @Test
    void fairDriverDoesNotApplyWalkingPhysicsToPassengers()
            throws IOException {
        final String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/action/"
                        + "FairPlayerActuator.java"
        ));

        assertTrue(source.contains(
                "if (!player.isPassenger()) {\n"
                        + "            applyVanillaTravel(player, frame);"
        ));
    }
}
