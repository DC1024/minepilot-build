package dev.mcai.companion.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Guards the ordinary-world body admission recovery boundary.  This is a
 * source contract only; it is not a claim that the formal client/model gate
 * has run.
 */
final class CompanionRuntimeEmbodimentRecoverySourceContractTest {
    @Test
    void retriesOnlyFailedAdmissionWithASeparateBoundedBackoff()
            throws IOException {
        final String source = Files.readString(Path.of(
                "src/main/java/dev/mcai/companion/runtime/"
                        + "CompanionRuntime.java"
        ));

        assertTrue(source.contains("bodySpawnRetryAfterTick"));
        assertTrue(source.contains(
                "embodiment.state() == SessionState.FAILED"
        ));
        assertTrue(source.contains(
                "!runtime.worldData().hardcoreDead()"
        ));
        assertTrue(source.contains(
                "AiPlayerManager.requestSpawn(server)"
        ));
        assertTrue(source.contains("+ (respawn.accepted() ? 20L : 200L)"));
        assertTrue(source.contains(
                "Retry FAILED (not ABSENT)"
        ));
    }
}
