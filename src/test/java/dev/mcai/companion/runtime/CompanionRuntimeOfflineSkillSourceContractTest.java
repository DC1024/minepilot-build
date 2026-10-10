package dev.mcai.companion.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Locks the runtime boundary that keeps an already-authorized skill from
 * becoming a permanent RUNNING ghost after the model gateway disconnects.
 */
final class CompanionRuntimeOfflineSkillSourceContractTest {
    @Test
    void offlineBehaviorStillTicksAnActiveSkillAndPreemptsItForEmergency() {
        final Path source = Path.of(
                "src/main/java/dev/mcai/companion/runtime/CompanionRuntime.java"
        );
        final String text;
        try {
            text = Files.readString(source);
        } catch (Exception exception) {
            throw new AssertionError("Cannot read CompanionRuntime source", exception);
        }
        assertTrue(
                text.contains(
                        "modelControlEnabled\n"
                                + "                || isActive(runtime.skillSupervisor().snapshot())"
                ),
                "offline runtime must retain a lane for an already-active skill"
        );
        assertTrue(
                text.contains("abandonForModelDisconnect()"),
                "offline emergency ownership must detach stale model controls"
        );
    }
}
